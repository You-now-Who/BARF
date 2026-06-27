package com.barf.robot;

import com.barf.AppLog;
import com.barf.serial.UsbSerialManager;
import com.barf.serial.SerialProtocol;

import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class RobotController {
    private static final String TAG = "RobotController";

    // Fixed-rate sender: one thread owns all serial writes at 20 Hz.
    // JS commands only update latestMotorSpeeds; the sender picks up whatever
    // the latest state is at each tick. Queue depth is always 0.
    private static final long SEND_INTERVAL_MS = 50;
    private final ScheduledExecutorService senderExecutor =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "motor-sender");
            t.setDaemon(true);
            return t;
        });

    // Auto-stop: opt-in via autoshutdown() in JS.
    private static final long AUTO_STOP_MS = 150;
    private volatile boolean autoStopEnabled = false;
    private final ScheduledExecutorService autoStopExecutor =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "auto-stop");
            t.setDaemon(true);  
            return t;
        });
    private volatile ScheduledFuture<?> pendingAutoStop;

    private volatile int[] latestMotorSpeeds = new int[]{0, 0, 0, 0, 0, 0};
    private volatile ScheduledFuture<?> pendingLiftStop;
    private volatile int liftSpeed = 0;
    private volatile boolean isMoving = false;
    private volatile String lastCommand = "none";
    private UsbSerialManager usbSerial;

    public RobotController() {
        senderExecutor.scheduleAtFixedRate(this::sendCurrentState,
            SEND_INTERVAL_MS, SEND_INTERVAL_MS, TimeUnit.MILLISECONDS);
        AppLog.i(TAG, "RobotController created — sender running at " + (1000 / SEND_INTERVAL_MS) + " Hz");
    }

    private void sendCurrentState() {
        try {
            if (usbSerial == null || !usbSerial.isConnected()) return;
            usbSerial.write(SerialProtocol.motorCommand(latestMotorSpeeds));
        } catch (Exception e) {
            AppLog.e(TAG, "Sender: " + e.getMessage());
        }
    }

    public void setUsbSerial(UsbSerialManager usb) {
        this.usbSerial = usb;
        AppLog.i(TAG, "USB serial wired into RobotController");
    }

    public void enableAutoStop() {
        autoStopEnabled = true;
        AppLog.i(TAG, "Auto-stop enabled (" + AUTO_STOP_MS + " ms)");
    }

    public void move(String direction, int pwm) {
        int x = 0, y = 0;
        switch (direction.toLowerCase()) {
            case "forward":  y = -pwm; break;
            case "backward": y =  pwm; break;
            case "left":     x = -pwm; break;
            case "right":    x =  pwm; break;
        }
        AppLog.d(TAG, "move(" + direction + ", " + pwm + ")");
        isMoving = true;
        lastCommand = "move:" + direction + ":" + pwm;
        setDesiredState(x, y, 0);
    }

    public void rotate(String direction, int pwm) {
        int r = 0;
        switch (direction.toLowerCase()) {
            case "left":  r = -pwm; break;
            case "right": r =  pwm; break;
        }
        AppLog.d(TAG, "rotate(" + direction + ", " + pwm + ")");
        isMoving = true;
        lastCommand = "rotate:" + direction + ":" + pwm;
        setDesiredState(0, 0, r);
    }

    public void stop() {
        AppLog.d(TAG, "stop()");
        isMoving = false;
        lastCommand = "stop";
        liftSpeed = 0;
        ScheduledFuture<?> prev = pendingAutoStop;
        if (prev != null) prev.cancel(false);
        latestMotorSpeeds = new int[]{0, 0, 0, 0, 0, 0};
        // Send zeros immediately rather than waiting for the next sender tick.
        senderExecutor.execute(this::sendCurrentState);
    }

    public void endSession() {
        autoStopEnabled = false;
        stop();
    }

    public void setRawSpeeds(int[] speeds) {
        if (speeds == null || speeds.length == 0) return;
        int[] padded = new int[6];
        for (int i = 0; i < Math.min(6, speeds.length); i++) padded[i] = speeds[i];
        ScheduledFuture<?> prev = pendingAutoStop;
        if (prev != null) prev.cancel(false);
        latestMotorSpeeds = padded;
        AppLog.d(TAG, "rawMotors → " + Arrays.toString(padded));
    }

    private void setDesiredState(int x, int y, int r) {
        latestMotorSpeeds = computeMotorSpeeds(x, y, r);
        AppLog.d(TAG, "desired → " + Arrays.toString(latestMotorSpeeds));
        if (autoStopEnabled) {
            ScheduledFuture<?> prev = pendingAutoStop;
            if (prev != null) prev.cancel(false);
            pendingAutoStop = autoStopExecutor.schedule(() -> {
                AppLog.d(TAG, "auto-stop fired");
                stop();
            }, AUTO_STOP_MS, TimeUnit.MILLISECONDS);
        }
    }

    int[] computeMotorSpeeds(int x, int y, int r) {
        // Array layout: [FL(0), BL(1), LIFT1(2), FR(3), BR(4), LIFT2(5)]
        // FR (index 3) wired reversed → negate its value in the output
        // LIFT1 and LIFT2 are mounted mirror-image, so the SAME PWM value drives the
        // same lift motion — both channels get identical liftSpeed (see setLiftSpeed).
        int fl = clamp(y + x + r);
        int bl = clamp(y - x + r);
        int fr = clamp(y - x - r);
        int br = clamp(y + x - r);
        int ls = clamp(liftSpeed);
        return new int[]{fl, bl, ls, -fr, br, ls};
    }

    public void setLiftSpeed(int speed) {
        liftSpeed = Math.max(-SerialProtocol.MAX_PWM, Math.min(SerialProtocol.MAX_PWM, speed));
        int[] current = latestMotorSpeeds.clone();
        current[2] = liftSpeed;
        current[5] = liftSpeed;
        latestMotorSpeeds = current;
        AppLog.d(TAG, "lift → " + liftSpeed);
    }

    public void setLiftSpeedTimed(int speed, int ms) {
        ScheduledFuture<?> prev = pendingLiftStop;
        if (prev != null) prev.cancel(false);
        setLiftSpeed(speed);
        if (ms > 0) {
            pendingLiftStop = senderExecutor.schedule(
                () -> setLiftSpeed(0), ms, TimeUnit.MILLISECONDS);
        }
    }

    private int clamp(int v) { return Math.max(-SerialProtocol.MAX_PWM, Math.min(SerialProtocol.MAX_PWM, v)); }

    public boolean isMoving() { return isMoving; }
    public String getLastCommand() { return lastCommand; }

    public RobotStatus getStatus() {
        RobotStatus s = new RobotStatus();
        s.isMoving = isMoving;
        s.lastCommand = lastCommand;
        s.timestamp = System.currentTimeMillis();
        return s;
    }

    public void shutdown() {
        senderExecutor.shutdownNow();
        autoStopExecutor.shutdownNow();
        if (usbSerial != null && usbSerial.isConnected()) {
            try { usbSerial.write(SerialProtocol.motorCommand(new int[]{0,0,0,0,0,0})); } catch (Exception ignored) {}
        }
    }

    public static class RobotStatus {
        public boolean isMoving;
        public String lastCommand;
        public int cameraFacing;
        public long timestamp;
    }
}
