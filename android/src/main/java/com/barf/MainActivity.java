// Copyright (C) 2025 THL A29 Limited, a Tencent company. All rights reserved.
// Licensed under the BSD 3-Clause License.
package com.barf;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.content.pm.ActivityInfo;
import android.text.Editable;
import android.text.TextUtils;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewFlipper;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.barf.camera.CameraManager;
import com.barf.camera.VideoStreamManager;
import com.barf.pairing.CameraHandoffState;
import com.barf.pairing.PairingManager;
import com.barf.pairing.WireGuardManager;
import com.barf.robot.RobotController;
import com.barf.runtime.JsRuntime;
import com.barf.runtime.WasmRuntime;
import com.barf.serial.SerialProtocol;
import com.barf.serial.UsbSerialManager;
import com.barf.server.PhoneApiServer;

public class MainActivity extends Activity implements SurfaceHolder.Callback, PhoneApiServer.ServerCallback, android.hardware.SensorEventListener {
    public static final int REQUEST_CAMERA = 100;
    private static final int REQUEST_PAIR = 200;
    private static final String TAG = "MainActivity";

    private YoloBridge yolo = new YoloBridge();
    private CameraManager cameraManager;
    private RobotController robotController;
    private android.hardware.SensorManager sensorManager;
    private android.hardware.Sensor accelerometer;
    private PhoneApiServer apiServer;
    private JsRuntime jsRuntime;
    private WasmRuntime wasmRuntime;
    private VideoStreamManager videoStreamManager;
    private UsbSerialManager usbSerialManager;
    private SurfaceView cameraView;
    private static PhoneApiServer sApiServerStatic = null;
    private final CameraHandoffState cameraHandoff = new CameraHandoffState();
    
    // Pairing info
    private String desktopIp;
    private String phoneIp;
    private PairingManager pairingManager;
    private WireGuardManager wireGuardManager;

    private Spinner spinnerTask, spinnerModel, spinnerCPUGPU;
    private int current_task = 0, current_model = 0, current_cpugpu = 0;

    private PowerManager.WakeLock jsWakeLock;

    // Debug UI
    private TextView tvEspStatus;
    private TextView tvDeviceInfo;
    private TextView tvLog;
    private ScrollView scrollLog;
    private TextView tvLogJs;
    private ScrollView scrollLogJs;
    private ViewFlipper logFlipper;
    private Button btnTabSys;
    private Button btnTabJs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getActionBar() != null) getActionBar().hide();
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        setContentView(R.layout.main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Initialize pairing managers
        pairingManager = new PairingManager(this);
        wireGuardManager = new WireGuardManager(this);

        // Get pairing info from intent
        Intent intent = getIntent();
        if (intent != null) {
            desktopIp = intent.getStringExtra("desktop_ip");
            phoneIp = intent.getStringExtra("phone_ip");
            String wgServerIp = intent.getStringExtra("wg_server_ip");
            String wgPublicKey = intent.getStringExtra("wg_public_key");
            String wgClientIp = intent.getStringExtra("wg_client_ip");
            int wgPort = intent.getIntExtra("wg_port", 51820);
            
            // Re-establish WireGuard connection if needed
            if (wgServerIp != null && wgPublicKey != null && wgClientIp != null && !wireGuardManager.isConnected()) {
                try {
                    wireGuardManager.connect(wgServerIp, wgPublicKey, wgClientIp, wgPort);
                } catch (Exception e) {
                    AppLog.e(TAG, "Failed to reconnect WireGuard: " + e.getMessage());
                }
            }
        } else if (pairingManager.isPaired()) {
            // Use saved pairing info
            desktopIp = pairingManager.getDesktopIp();
            String wgServerIp = pairingManager.getWgServerIp();
            String wgPublicKey = pairingManager.getWgPublicKey();
            String wgClientIp = pairingManager.getWgClientIp();
            int wgPort = pairingManager.getWgPort();
            
            if (wgServerIp != null && wgPublicKey != null && wgClientIp != null && !wireGuardManager.isConnected()) {
                try {
                    wireGuardManager.connect(wgServerIp, wgPublicKey, wgClientIp, wgPort);
                } catch (Exception e) {
                    AppLog.e(TAG, "Failed to reconnect WireGuard: " + e.getMessage());
                }
            }
        }

        cameraView = findViewById(R.id.cameraview);
        cameraView.getHolder().setFormat(PixelFormat.RGBA_8888);
        cameraView.getHolder().addCallback(this);
        cameraView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> adjustAspectRatio());

        cameraManager = new CameraManager(yolo);

        // Debug UI views
        tvEspStatus = findViewById(R.id.tvEspStatus);
        tvDeviceInfo = findViewById(R.id.tvDeviceInfo);
        tvLog = findViewById(R.id.tvLog);
        scrollLog = findViewById(R.id.scrollLog);
        tvLogJs = findViewById(R.id.tvLogJs);
        scrollLogJs = findViewById(R.id.scrollLogJs);
        logFlipper = findViewById(R.id.logFlipper);
        btnTabSys = findViewById(R.id.btnTabSys);
        btnTabJs = findViewById(R.id.btnTabJs);

        // Wire in-app loggers
        AppLog.setListener(line -> mainHandler.post(() -> appendLog(tvLog, scrollLog, line)));
        AppLog.setJsListener(line -> mainHandler.post(() -> appendLog(tvLogJs, scrollLogJs, line)));

        // Tab buttons
        btnTabSys.setOnClickListener(v -> showLogTab(0));
        btnTabJs.setOnClickListener(v -> showLogTab(1));

        // Swipe to switch log tabs
        GestureDetector gestureDetector = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    private static final int SWIPE_MIN_DISTANCE = 80;
                    private static final int SWIPE_MIN_VELOCITY = 100;

                    @Override
                    public boolean onFling(MotionEvent e1, MotionEvent e2,
                                          float vX, float vY) {
                        if (e1 == null || e2 == null) return false;
                        float dx = e2.getX() - e1.getX();
                        if (Math.abs(dx) < SWIPE_MIN_DISTANCE) return false;
                        if (Math.abs(vX) < SWIPE_MIN_VELOCITY) return false;
                        if (dx < 0) showLogTab(1); // swipe left → JS
                        else        showLogTab(0); // swipe right → SYS
                        return true;
                    }
                });
        logFlipper.setOnTouchListener((v, event) -> gestureDetector.onTouchEvent(event));

        AppLog.i(TAG, "BARF starting up");

        Button switchCam = findViewById(R.id.buttonSwitchCamera);
        switchCam.setOnClickListener(v -> {
            cameraManager.switchCamera();
            AppLog.i(TAG, "Camera switched to " + (cameraManager.getFacing() == 0 ? "back" : "front"));
        });

        Button pairButton = findViewById(R.id.buttonPair);
        pairButton.setOnClickListener(v -> {
            try {
                cameraManager.close();
                cameraHandoff.setCameraClosedForPairing(true);
            } catch (Exception e) {
                AppLog.w(TAG, "Error closing camera for pairing: " + e.getMessage());
            }
            // 300ms delay: NDK camera2 close is async at the OS level.
            // Without this, CameraX in PairingActivity races the NDK camera for the hardware.
            mainHandler.postDelayed(() -> {
                Intent pairIntent = new Intent(MainActivity.this, com.barf.pairing.PairingActivity.class);
                startActivityForResult(pairIntent, REQUEST_PAIR);
            }, 300);
        });

        Button btnMotorTest = findViewById(R.id.btnMotorTest);
        btnMotorTest.setOnClickListener(v -> sendMotorTest());

        Button btnStop = findViewById(R.id.btnStop);
        btnStop.setOnClickListener(v -> {
            if (usbSerialManager != null && usbSerialManager.isConnected()) {
                String cmd = "{\"m\":[0,0,0,0]}\n";
                usbSerialManager.write(cmd);
                AppLog.i(TAG, "TX: " + cmd.trim());
            } else {
                AppLog.w(TAG, "STOP pressed but ESP32 not connected");
            }
        });

        Button btnReconnect = findViewById(R.id.btnReconnect);
        btnReconnect.setOnClickListener(v -> {
            AppLog.i(TAG, "Manual USB reconnect triggered");
            if (usbSerialManager != null) usbSerialManager.reconnect();
        });

        Button btnClearLog = findViewById(R.id.btnClearLog);
        btnClearLog.setOnClickListener(v -> {
            if (logFlipper.getDisplayedChild() == 1) {
                AppLog.clearJs();
                tvLogJs.setText("");
            } else {
                AppLog.clear();
                tvLog.setText("");
            }
        });

        spinnerTask = findViewById(R.id.spinnerTask);
        spinnerTask.setOnItemSelectedListener(spinnerListener(0, () -> current_task, p -> current_task = p));
        spinnerModel = findViewById(R.id.spinnerModel);
        spinnerModel.setOnItemSelectedListener(spinnerListener(1, () -> current_model, p -> current_model = p));
        spinnerCPUGPU = findViewById(R.id.spinnerCPUGPU);
        spinnerCPUGPU.setOnItemSelectedListener(spinnerListener(2, () -> current_cpugpu, p -> current_cpugpu = p));

        reload();
        robotController = new RobotController();
        sensorManager = (android.hardware.SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER);
        }
        startServer();
    }

    // Keep the on-screen log views bounded. AppLog caps its deque at 400 lines,
    // but TextView.append() grows the backing Editable forever — at frame-rate
    // logging it eats the whole heap and OOMs (was crashing at append()).
    private static final int MAX_LOG_LINES = 400;

    /** Append a line, then trim the oldest lines so the Editable can't grow unbounded. */
    private void appendLog(TextView tv, ScrollView scroll, String line) {
        tv.append(line + "\n");

        CharSequence text = tv.getText();
        if (!(text instanceof Editable)) return; // append() makes it Editable; defensive
        Editable e = (Editable) text;

        int newlines = 0;
        for (int i = 0; i < e.length(); i++) {
            if (e.charAt(i) == '\n') newlines++;
        }
        if (newlines > MAX_LOG_LINES) {
            int drop = newlines - MAX_LOG_LINES;
            int cut = 0;
            for (int i = 0; i < drop; i++) {
                int nl = TextUtils.indexOf(e, '\n', cut);
                if (nl < 0) { cut = e.length(); break; }
                cut = nl + 1;
            }
            e.delete(0, cut);
        }

        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void showLogTab(int idx) {
        logFlipper.setDisplayedChild(idx);
        btnTabSys.setTextColor(idx == 0 ? 0xFF22CC22 : 0xFF444444);
        btnTabSys.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                idx == 0 ? 0xFF0d2b0d : 0xFF1a1a1a));
        btnTabJs.setTextColor(idx == 1 ? 0xFFFFAA33 : 0xFF444444);
        btnTabJs.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                idx == 1 ? 0xFF2b1a00 : 0xFF1a1a1a));
    }

    private AdapterView.OnItemSelectedListener spinnerListener(int idx, java.util.function.Supplier<Integer> getter, java.util.function.Consumer<Integer> setter) {
        return new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (pos != getter.get()) { setter.accept(pos); reload(); }
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        };
    }

    private void adjustAspectRatio() {
        int pw = 1280, ph = 720;
        float ar = (float) pw / ph;
        int vw = cameraView.getWidth(), vh = cameraView.getHeight();
        if (vw == 0 || vh == 0) return;
        int nw = vw, nh = (int) (vw / ar);
        if (nh > vh) { nh = vh; nw = (int) (vh * ar); }
        cameraView.getLayoutParams().width = nw;
        cameraView.getLayoutParams().height = nh;
        cameraView.requestLayout();
    }

    private void startServer() {
        try {
            apiServer = new PhoneApiServer(this, 8080);
            sApiServerStatic = apiServer;
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            jsWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BARF:JsRuntime");
            jsWakeLock.setReferenceCounted(false);

            jsRuntime = new JsRuntime();
            jsRuntime.setCallback(new JsRuntime.JsCommandCallback() {
                @Override public void onMove(String d, int pwm) {
                    AppLog.d(TAG, "JS→onMove: " + d + " " + pwm);
                    robotController.move(d, pwm);
                }
                @Override public void onRotate(String d, int pwm) {
                    AppLog.d(TAG, "JS→onRotate: " + d + " " + pwm);
                    robotController.rotate(d, pwm);
                }
                @Override public void onRawMotors(int[] speeds) {
                    AppLog.d(TAG, "JS→onRawMotors: " + java.util.Arrays.toString(speeds));
                    robotController.setRawSpeeds(speeds);
                }
                @Override public void onLiftMotors(int speed) {
                    AppLog.d(TAG, "JS→onLiftMotors: " + speed);
                    robotController.setLiftSpeed(speed);
                }
                @Override public void onLiftTimed(int speed, int ms) {
                    AppLog.d(TAG, "JS→onLiftTimed: " + speed + " for " + ms + "ms");
                    robotController.setLiftSpeedTimed(speed, ms);
                }
                @Override public void onEnableAutoStop() {
                    AppLog.d(TAG, "JS→autoshutdown()");
                    robotController.enableAutoStop();
                }
                @Override public void onStop() {
                    AppLog.d(TAG, "JS→onStop (motors halt, session continues)");
                    robotController.stop();
                }
                @Override public void onScriptEnd() {
                    AppLog.d(TAG, "JS→onScriptEnd (full cleanup)");
                    robotController.endSession();
                    if (jsWakeLock != null && jsWakeLock.isHeld()) jsWakeLock.release();
                }
            });
            jsRuntime.setStartListener(() -> {
                if (jsWakeLock != null && !jsWakeLock.isHeld()) jsWakeLock.acquire();
            });
            apiServer.setJsRuntime(jsRuntime);
            wasmRuntime = new WasmRuntime();
            apiServer.setWasmRuntime(wasmRuntime);
            apiServer.setCallback(this);

            // Broadcast JS logs to desktop over WebSocket
            final PhoneApiServer srv = apiServer;
            AppLog.addJsListener(line -> srv.broadcastLog(line, "js"));

            // Setup USB-Serial for ESP32 communication
            usbSerialManager = new UsbSerialManager(this);
            usbSerialManager.setListener(new UsbSerialManager.UsbSerialListener() {
                @Override
                public void onConnected() {
                    AppLog.i(TAG, "ESP32 connected");
                    runOnUiThread(() -> setEspStatus(true));
                    if (apiServer != null) apiServer.broadcastSerialStatus(true);
                }

                @Override
                public void onDisconnected() {
                    AppLog.i(TAG, "ESP32 disconnected");
                    runOnUiThread(() -> setEspStatus(false));
                    if (apiServer != null) apiServer.broadcastSerialStatus(false);
                }

                @Override
                public void onMessageReceived(String line) {
                    AppLog.d(TAG, "RX: " + line);
                    if (apiServer != null) apiServer.broadcastSerialRx(line);
                }

                @Override
                public void onHeartbeatTimeout() {
                    AppLog.w(TAG, "Heartbeat timeout");
                }
            });
            apiServer.setUsbSerialManager(usbSerialManager);
            robotController.setUsbSerial(usbSerialManager);

            yolo.registerActivity(this);
            apiServer.start();
            apiServer.getWebSocketServer().setConnectionListener(new SimpleWebSocketServer.ConnectionListener() {
                @Override
                public void onDesktopConnected() {
                    AppLog.i(TAG, "Desktop connected via WebSocket");
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Desktop connected", Toast.LENGTH_SHORT).show());
                }

                @Override
                public void onDesktopDisconnected() {
                    AppLog.i(TAG, "Desktop disconnected — switching to solo mode");
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Desktop disconnected", Toast.LENGTH_SHORT).show());
                }
            });
            videoStreamManager = new VideoStreamManager(cameraView, apiServer.getVideoStreamServer(), cameraManager);
            videoStreamManager.start();
            AppLog.i(TAG, "Server started on port 8080");

            // Auto-connect after WebSocket server is up so broadcastSerialStatus reaches clients
            usbSerialManager.connect();

            // Auto-run saved JS script (competition solo mode)
            String savedScript = apiServer.loadSavedScript();
            if (savedScript != null && !savedScript.isEmpty()) {
                AppLog.i(TAG, "Auto-running saved JS script");
                mainHandler.postDelayed(() -> {
                    try {
                        if (jsRuntime != null && !jsRuntime.isRunning()) {
                            jsRuntime.execute(savedScript);
                        }
                    } catch (Exception e2) {
                        AppLog.e(TAG, "Auto-run JS failed: " + e2.getMessage());
                    }
                }, 1500);
            }
        } catch (Exception e) {
            AppLog.e(TAG, "Failed to start server: " + e.getMessage());
            Toast.makeText(this, "Server failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void setEspStatus(boolean connected) {
        if (tvEspStatus == null) return;
        if (connected) {
            tvEspStatus.setText("⬤  ESP32  CONNECTED");
            tvEspStatus.setTextColor(0xFF33FF33);
            tvEspStatus.setBackgroundColor(0xFF001a00);
            tvDeviceInfo.setText(usbSerialManager != null ? usbSerialManager.getDeviceInfo() : "");
        } else {
            tvEspStatus.setText("⬤  ESP32  DISCONNECTED");
            tvEspStatus.setTextColor(0xFFFF4444);
            tvEspStatus.setBackgroundColor(0xFF1a0000);
            tvDeviceInfo.setText("no device detected");
        }
    }

    private void sendMotorTest() {
        if (usbSerialManager == null || !usbSerialManager.isConnected()) {
            AppLog.w(TAG, "Motor test: ESP32 not connected");
            runOnUiThread(() -> Toast.makeText(this, "ESP32 not connected", Toast.LENGTH_SHORT).show());
            return;
        }
        // Wah wah: forward pulse → stop → reverse pulse → stop
        String fwd  = "{\"m\":[200,200,200,200]}\n";
        String rev  = "{\"m\":[-200,-200,-200,-200]}\n";
        String stop = "{\"m\":[0,0,0,0]}\n";
        AppLog.i(TAG, "Motor test: wah wah");
        usbSerialManager.write(fwd);
        mainHandler.postDelayed(() -> usbSerialManager.write(stop),  300);
        mainHandler.postDelayed(() -> usbSerialManager.write(rev),   450);
        mainHandler.postDelayed(() -> usbSerialManager.write(stop),  750);
    }

    public static void pushDetectionsToScripts(String json) {
        if (sApiServerStatic != null) sApiServerStatic.pushDetections(json);
    }

    private void broadcast(String msg) {
        if (apiServer != null && apiServer.getWebSocketServer() != null)
            apiServer.getWebSocketServer().broadcast(msg);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PAIR) {
            if (resultCode == RESULT_OK && data != null) {
                String desktopIp = data.getStringExtra("desktop_ip");
                String phoneIp = data.getStringExtra("phone_ip");
                AppLog.i(TAG, "Paired with desktop at " + desktopIp + " (phone IP: " + phoneIp + ")");
                Toast.makeText(this, "Paired with " + desktopIp, Toast.LENGTH_SHORT).show();
            }
            // Always re-open camera after pairing attempt
            cameraHandoff.onPairingComplete();
            cameraManager.open();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (device != null && usbSerialManager != null) {
                AppLog.i(TAG, "USB device attached via onNewIntent: " + device.getProductName());
                usbSerialManager.connectToDevice(device);
            }
        }
    }

    private void reload() {
        if (!yolo.loadModel(getAssets(), current_task, current_model, current_cpugpu))
            AppLog.e(TAG, "loadModel failed");
    }

    // ========== ServerCallback ==========
    // The desktop control endpoints send a normalized speed (0.0..1.0); RobotController
    // takes a PWM int (0..MAX_PWM), so scale here. Passing the raw float would both fail
    // to compile and truncate 0.5 → 0 (robot never moves).
    @Override public void onMove(String d, float s) { robotController.move(d, speedToPwm(s)); }
    @Override public void onRotate(String d, float s) { robotController.rotate(d, speedToPwm(s)); }

    /** Convert a normalized 0..1 speed (from the desktop API) to a 0..MAX_PWM PWM value. */
    private static int speedToPwm(float speed) {
        int pwm = Math.round(Math.abs(speed) * SerialProtocol.MAX_PWM);
        return Math.max(0, Math.min(SerialProtocol.MAX_PWM, pwm));
    }
    @Override public void onSwitchCamera() {
        try { cameraManager.switchCamera(); } catch (Exception e) { AppLog.w(TAG, "switchCamera error: " + e.getMessage()); }
    }
    @Override public int getCameraFacing() { return cameraManager.getFacing(); }

    // Activity lifecycle onStop — does NOT reset cameraClosedForPairing
    @Override
    public void onStop() {
        super.onStop();
        cameraHandoff.onLifecycleStop();
        // intentionally empty — do not reset cameraHandoff here
    }

    // ServerCallback: called when desktop sends robot stop command
    @Override
    public void onRobotStop() {
        robotController.stop();
    }

    // ========== SurfaceHolder.Callback ==========
    @Override public void surfaceCreated(SurfaceHolder h) {}
    @Override public void surfaceDestroyed(SurfaceHolder h) {}

    @Override
    public void surfaceChanged(SurfaceHolder h, int fmt, int w, int h_) {
        yolo.setOutputWindow(h.getSurface());
        int rot = 0;
        try {
            if (w < h_) {
                cameraView.setRotation(90f);
                ViewGroup.LayoutParams lp = cameraView.getLayoutParams();
                lp.width = h_; lp.height = w;
                cameraView.setLayoutParams(lp);
                rot = (cameraManager.getFacing() == 1) ? 270 : 90;
            }
            cameraManager.setDisplayOrientation(rot);
        } catch (Exception e) {
            AppLog.w(TAG, "surfaceChanged error: " + e.getMessage());
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_DENIED)
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
        // Don't open camera if we're returning from pairing (onActivityResult handles it)
        if (!cameraHandoff.isCameraClosedForPairing()) {
            cameraManager.open();
        }
        // Retry USB connection on every resume — handles USB re-plug and post-permission-grant cases
        if (usbSerialManager != null && !usbSerialManager.isConnected()) {
            usbSerialManager.reconnect();
        }
        if (sensorManager != null && accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, android.hardware.SensorManager.SENSOR_DELAY_GAME);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (!cameraHandoff.isCameraClosedForPairing()) {
            try { cameraManager.close(); } catch (Exception e) { AppLog.w(TAG, "close error: " + e.getMessage()); }
        }
        if (sensorManager != null) sensorManager.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(android.hardware.SensorEvent event) {
        if (event.sensor.getType() == android.hardware.Sensor.TYPE_ACCELEROMETER && jsRuntime != null) {
            jsRuntime.setAccelerometer(event.values[0], event.values[1], event.values[2]);
        }
    }

    @Override
    public void onAccuracyChanged(android.hardware.Sensor sensor, int accuracy) {}

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (jsWakeLock != null && jsWakeLock.isHeld()) jsWakeLock.release();
        if (jsRuntime != null && jsRuntime.isRunning()) jsRuntime.stop();
        if (usbSerialManager != null) usbSerialManager.disconnect();
        if (videoStreamManager != null) videoStreamManager.stop();
        if (apiServer != null) apiServer.stop();
        if (robotController != null) robotController.shutdown();
    }
}
