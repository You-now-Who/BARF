package com.barf.pairing;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.barf.R;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class PairingActivity extends Activity implements ImageAnalysis.Analyzer, LifecycleOwner {
    private static final String TAG = "PairingActivity";
    private static final String BARF_PREFIX = "barf://pair";
    private static final long SCAN_TIMEOUT_MS = 30000;

    private PreviewView previewView;
    private TextView statusText;
    private BarcodeScanner barcodeScanner;
    private ExecutorService analysisExecutor;
    private Handler mainHandler;
    private boolean paired = false;
    private LifecycleRegistry lifecycleRegistry;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_pairing);
        previewView = findViewById(R.id.pairingPreviewView);
        statusText = findViewById(R.id.pairingStatusText);

        lifecycleRegistry = new LifecycleRegistry(this);
        lifecycleRegistry.setCurrentState(Lifecycle.State.CREATED);

        barcodeScanner = BarcodeScanning.getClient();
        analysisExecutor = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());

        startCamera();

        // Timeout: if no QR scanned in 30s, cancel
        mainHandler.postDelayed(this::onTimeout, SCAN_TIMEOUT_MS);
    }

    @Override
    @NonNull
    public Lifecycle getLifecycle() {
        return lifecycleRegistry;
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                analysis.setAnalyzer(analysisExecutor, this);

                CameraSelector cameraSelector = new CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                        .build();

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, analysis);

            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Camera init failed: " + e.getMessage());
                runOnUiThread(() -> {
                    statusText.setText("Camera error: " + e.getMessage());
                });
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @Override
    public void analyze(@NonNull ImageProxy imageProxy) {
        if (paired) {
            imageProxy.close();
            return;
        }

        @SuppressWarnings("ConstantConditions")
        int rotationDegrees = imageProxy.getImageInfo().getRotationDegrees();

        InputImage inputImage = InputImage.fromMediaImage(
                imageProxy.getImage(), rotationDegrees);

        barcodeScanner.process(inputImage)
                .addOnSuccessListener(barcodes -> {
                    for (Barcode barcode : barcodes) {
                        String raw = barcode.getRawValue();
                        if (raw != null && raw.startsWith(BARF_PREFIX)) {
                            handleQrCode(raw);
                            break;
                        }
                    }
                })
                .addOnCompleteListener(task -> imageProxy.close());
    }

    private void handleQrCode(String uri) {
        if (paired) return;
        paired = true;

        runOnUiThread(() -> statusText.setText("QR detected! Connecting..."));

        // Parse URI: barf://pair?ip=X.X.X.X&key=XXXX&port=9876
        String desktopIp = null;
        String pairKey = null;
        int port = 9876;

        try {
            String query = uri.substring(uri.indexOf('?') + 1);
            String[] params = query.split("&");
            for (String param : params) {
                String[] kv = param.split("=", 2);
                if (kv.length != 2) continue;
                switch (kv[0]) {
                    case "ip": desktopIp = kv[1]; break;
                    case "key": pairKey = kv[1]; break;
                    case "port": port = Integer.parseInt(kv[1]); break;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse QR URI: " + e.getMessage());
            runOnUiThread(() -> {
                statusText.setText("Invalid QR code format");
                paired = false;
            });
            return;
        }

        if (desktopIp == null || pairKey == null) {
            runOnUiThread(() -> {
                statusText.setText("QR missing IP or key");
                paired = false;
            });
            return;
        }

        final String finalDesktopIp = desktopIp;
        final String finalPairKey = pairKey;
        final int finalPort = port;

        // Do the pairing POST on the analysis executor
        analysisExecutor.execute(() -> {
            String phoneIp = getLocalIpAddress();
            if (phoneIp == null) {
                runOnUiThread(() -> {
                    statusText.setText("No WiFi IP found - check network");
                    paired = false;
                });
                return;
            }

            boolean success = sendPairingRequest(finalDesktopIp, finalPort, finalPairKey, phoneIp);
            if (success) {
                Log.i(TAG, "Paired with desktop at " + finalDesktopIp);
                runOnUiThread(() -> {
                    statusText.setText("Paired with " + finalDesktopIp);
                    Intent result = new Intent();
                    result.putExtra("desktop_ip", finalDesktopIp);
                    result.putExtra("phone_ip", phoneIp);
                    setResult(RESULT_OK, result);
                    // Small delay so user sees "Paired" message
                    mainHandler.postDelayed(PairingActivity.this::finish, 1000);
                });
            } else {
                runOnUiThread(() -> {
                    statusText.setText("Failed to reach desktop at " + finalDesktopIp);
                    paired = false;
                });
            }
        });
    }

    private boolean sendPairingRequest(String desktopIp, int port, String pairKey, String phoneIp) {
        try {
            URL url = new URL("http://" + desktopIp + ":" + port + "/api/phone-here");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setDoOutput(true);

            String json = "{\"pair_key\":\"" + pairKey + "\",\"phone_ip\":\"" + phoneIp + "\",\"phone_port\":8080}";
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(body.length);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
                os.flush();
            }

            int code = conn.getResponseCode();
            conn.disconnect();
            return code == 200;

        } catch (Exception e) {
            Log.e(TAG, "Pairing POST failed: " + e.getMessage());
            return false;
        }
    }

    private String getLocalIpAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return null;

            for (NetworkInterface iface : Collections.list(interfaces)) {
                if (iface.isLoopback() || !iface.isUp()) continue;

                for (InetAddress addr : Collections.list(iface.getInetAddresses())) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        String ip = addr.getHostAddress();
                        // Filter out link-local
                        if (ip != null && !ip.startsWith("169.254")) {
                            return ip;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "getLocalIpAddress error: " + e.getMessage());
        }
        return null;
    }

    private void onTimeout() {
        if (!paired && !isFinishing()) {
            runOnUiThread(() -> {
                Toast.makeText(this, "Scan timed out", Toast.LENGTH_SHORT).show();
                setResult(RESULT_CANCELED);
                finish();
            });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        lifecycleRegistry.setCurrentState(Lifecycle.State.RESUMED);
    }

    @Override
    protected void onPause() {
        super.onPause();
        lifecycleRegistry.setCurrentState(Lifecycle.State.STARTED);
    }

    @Override
    protected void onDestroy() {
        lifecycleRegistry.setCurrentState(Lifecycle.State.DESTROYED);
        super.onDestroy();
        if (barcodeScanner != null) barcodeScanner.close();
        if (analysisExecutor != null) {
            analysisExecutor.shutdown();
            try {
                analysisExecutor.awaitTermination(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {}
        }
        mainHandler.removeCallbacksAndMessages(null);
    }
}
