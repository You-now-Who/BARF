// Copyright (C) 2025 THL A29 Limited, a Tencent company. All rights reserved.
// Licensed under the BSD 3-Clause License.
package com.barf;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.content.pm.ActivityInfo;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.barf.camera.CameraManager;
import com.barf.camera.VideoStreamManager;
import com.barf.robot.RobotController;
import com.barf.runtime.JsRuntime;
import com.barf.server.PhoneApiServer;

public class MainActivity extends Activity implements SurfaceHolder.Callback, PhoneApiServer.ServerCallback {
    public static final int REQUEST_CAMERA = 100;
    private static final String TAG = "MainActivity";

    private YoloBridge yolo = new YoloBridge();
    private CameraManager cameraManager;
    private RobotController robotController;
    private PhoneApiServer apiServer;
    private JsRuntime jsRuntime;
    private VideoStreamManager videoStreamManager;
    private SurfaceView cameraView;
    private static PhoneApiServer sApiServerStatic = null;

    private Spinner spinnerTask, spinnerModel, spinnerCPUGPU;
    private int current_task = 0, current_model = 0, current_cpugpu = 0;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getActionBar() != null) getActionBar().hide();
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        setContentView(R.layout.main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        cameraView = findViewById(R.id.cameraview);
        cameraView.getHolder().setFormat(PixelFormat.RGBA_8888);
        cameraView.getHolder().addCallback(this);
        cameraView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> adjustAspectRatio());

        cameraManager = new CameraManager(yolo);

        Button switchCam = findViewById(R.id.buttonSwitchCamera);
        switchCam.setOnClickListener(v -> {
            cameraManager.switchCamera();
            broadcast("Camera switched to " + (cameraManager.getFacing() == 0 ? "back" : "front"));
        });

        spinnerTask = findViewById(R.id.spinnerTask);
        spinnerTask.setOnItemSelectedListener(spinnerListener(0, () -> current_task, p -> current_task = p));
        spinnerModel = findViewById(R.id.spinnerModel);
        spinnerModel.setOnItemSelectedListener(spinnerListener(1, () -> current_model, p -> current_model = p));
        spinnerCPUGPU = findViewById(R.id.spinnerCPUGPU);
        spinnerCPUGPU.setOnItemSelectedListener(spinnerListener(2, () -> current_cpugpu, p -> current_cpugpu = p));

        reload();
        robotController = new RobotController();
        startServer();
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
            jsRuntime = new JsRuntime();
            jsRuntime.setCallback(new JsRuntime.JsCommandCallback() {
                @Override public void onMove(String d, float s) { robotController.move(d, s); }
                @Override public void onRotate(String d, float s) { robotController.rotate(d, s); }
                @Override public void onStop() { robotController.stop(); }
            });
            apiServer.setJsRuntime(jsRuntime);
            apiServer.setCallback(this);
            yolo.registerActivity(this);
            apiServer.start();
            videoStreamManager = new VideoStreamManager(cameraView, apiServer.getVideoStreamServer(), cameraManager);
            videoStreamManager.start();
            Log.i(TAG, "Server started on port 8080");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start server: " + e.getMessage());
            Toast.makeText(this, "Server failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    public static void pushDetectionsToScripts(String json) {
        if (sApiServerStatic != null) sApiServerStatic.pushDetections(json);
    }

    private void broadcast(String msg) {
        if (apiServer != null && apiServer.getWebSocketServer() != null)
            apiServer.getWebSocketServer().broadcast(msg);
    }

    private void reload() {
        if (!yolo.loadModel(getAssets(), current_task, current_model, current_cpugpu))
            Log.e(TAG, "loadModel failed");
    }

    // ========== ServerCallback ==========
    @Override public void onMove(String d, float s) { robotController.move(d, s); }
    @Override public void onRotate(String d, float s) { robotController.rotate(d, s); }
    @Override public void onStop() { robotController.stop(); }
    @Override public int getCameraFacing() { return cameraManager.getFacing(); }

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
            Log.w(TAG, "surfaceChanged error: " + e.getMessage());
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_DENIED)
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
        cameraManager.open();
    }

    @Override
    public void onPause() { super.onPause(); cameraManager.close(); }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (videoStreamManager != null) videoStreamManager.stop();
        if (apiServer != null) apiServer.stop();
        if (robotController != null) robotController.shutdown();
    }
}
