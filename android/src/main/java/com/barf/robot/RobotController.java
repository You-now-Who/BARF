package com.barf.robot;

import android.util.Log;

import com.barf.serial.UsbSerialManager;
import com.barf.serial.SerialProtocol;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Controls robot movement via USB-serial to ESP32.
 *
 * Motor commands are sent at a fixed 20 Hz rate from a dedicated loop thread.
 * Callers just update the desired motor state — commands never queue up in the
 * serial buffer, so stop() takes effect within one tick (~50 ms) even if the
 * JS script was flooding move() at camera frame rate.
 */
public class RobotController {
    private static final String TAG = "RobotController";
    private static final int ROBOT_UDP_PORT = 4210;
    private static final int MOTOR_HZ = 20;

    private volatile boolean isMoving = false;
    private volatile String lastCommand = "none";
    private volatile int robotX = 0;
    private volatile int robotY = 0;
    private volatile int robotR = 0;

    // Latest desired motor speeds — written by callers, read by loop thread.
    private volatile int[] latestMotorSpeeds = new int[]{0, 0, 0, 0, 0, 0};

    private final ScheduledExecutorService motorLoop =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "motor-loop");
            t.setDaemon(true);
            return t;
        });

    private DatagramSocket udpSocket;
    private String robotIp = "192.168.1.100";
    private UsbSerialManager usbSerial;

    public RobotController() {
        initializeUdpSocket();
        motorLoop.scheduleAtFixedRate(this::flushMotorCommand, 0, 1000 / MOTOR_HZ, TimeUnit.MILLISECONDS);
    }

    /** Called at MOTOR_HZ — sends current desired state to ESP32. */
    private void flushMotorCommand() {
        if (usbSerial != null && usbSerial.isConnected()) {
            String msg = SerialProtocol.motorCommand(latestMotorSpeeds);
            usbSerial.write(msg);
        }
    }

    private void initializeUdpSocket() {
        try {
            if (udpSocket != null && !udpSocket.isClosed()) udpSocket.close();
            udpSocket = new DatagramSocket();
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize UDP socket: " + e.getMessage());
        }
    }

    public void setUsbSerial(UsbSerialManager usbSerial) {
        this.usbSerial = usbSerial;
    }

    public void setRobotIp(String ip) { this.robotIp = ip; }
    public String getRobotIp() { return robotIp; }

    public void move(String direction, float speed) {
        Log.i(TAG, "move: " + direction + " speed=" + speed);
        isMoving = true;
        lastCommand = "move:" + direction + ":" + speed;

        int ms = (int) (speed * 255);
        int x = 0, y = 0;

        switch (direction.toLowerCase()) {
            case "forward":  y = -ms; break;
            case "backward": y =  ms; break;
            case "left":     x = -ms; break;
            case "right":    x =  ms; break;
        }

        setDesiredState(x, y, 0);
    }

    public void rotate(String direction, float speed) {
        Log.i(TAG, "rotate: " + direction + " speed=" + speed);
        isMoving = true;
        lastCommand = "rotate:" + direction + ":" + speed;

        int ms = (int) (speed * 255);
        int r = 0;

        switch (direction.toLowerCase()) {
            case "left":  r = -ms; break;
            case "right": r =  ms; break;
        }

        setDesiredState(0, 0, r);
    }

    public void stop() {
        Log.i(TAG, "stop");
        isMoving = false;
        lastCommand = "stop";
        latestMotorSpeeds = new int[]{0, 0, 0, 0, 0, 0};
    }

    /**
     * Bypass the mixing formula and send exact per-motor values.
     * Useful for diagnosing wiring: call from JS with rawMotors([255,0,0,0,0,0])
     * to spin just motor 0, etc.
     * Expected: 6 values for [FL, FR, LIFT, BL, BR, LIFT], range -255..255.
     */
    public void setRawSpeeds(int[] speeds) {
        if (speeds == null || speeds.length < 6) return;
        latestMotorSpeeds = speeds;
        Log.d(TAG, "rawMotors: " + Arrays.toString(latestMotorSpeeds));
    }

    public boolean isMoving() { return isMoving; }
    public String getLastCommand() { return lastCommand; }
    public int[] getMotorValues() { return new int[]{robotX, robotY, robotR, 0}; }

    public RobotStatus getStatus() {
        RobotStatus s = new RobotStatus();
        s.isMoving = isMoving;
        s.lastCommand = lastCommand;
        s.timestamp = System.currentTimeMillis();
        return s;
    }

    /**
     * Motor mixing for a 4-wheel drive chassis.
     * Indices: m[0]=FL, m[1]=FR, m[2]=LIFT(0), m[3]=BL, m[4]=BR, m[5]=LIFT(0).
     * x = strafe (positive = right), y = drive (positive = forward in code convention),
     * r = rotation (positive = clockwise when viewed from above).
     *
     * If rotate still drives the robot straight after physical testing, the left/right
     * motor indices (0+3 vs 1+4) don't match your wiring — use rawMotors() in JS
     * to identify which motor index is which physical wheel, then swap accordingly.
     */
    int[] computeMotorSpeeds(int x, int y, int r) {
        int fl = clamp(y + x + r);
        int fr = clamp(y - x - r);
        int bl = clamp(y + x - r);  // note: y+x-r keeps BL in phase with FL for differential
        int br = clamp(y - x + r);
        return new int[]{fl, fr, 0, bl, br, 0};
    }

    private int clamp(int v) { return Math.max(-255, Math.min(255, v)); }

    private void setDesiredState(int x, int y, int r) {
        robotX = x; robotY = y; robotR = r;
        if (usbSerial != null && usbSerial.isConnected()) {
            latestMotorSpeeds = computeMotorSpeeds(x, y, r);
            Log.d(TAG, "desiredMotors: " + Arrays.toString(latestMotorSpeeds));
        } else {
            // UDP fallback (dev mode without USB cable)
            sendUdp(x, y, r);
        }
    }

    private void sendUdp(int x, int y, int r) {
        if (udpSocket == null || udpSocket.isClosed()) return;
        new Thread(() -> {
            try {
                String cmd = x + "," + y + "," + r + ",0";
                byte[] b = cmd.getBytes();
                udpSocket.send(new DatagramPacket(b, b.length, InetAddress.getByName(robotIp), ROBOT_UDP_PORT));
            } catch (Exception e) {
                Log.e(TAG, "UDP send failed: " + e.getMessage());
            }
        }).start();
    }

    public void shutdown() {
        latestMotorSpeeds = new int[]{0, 0, 0, 0, 0, 0};
        motorLoop.shutdown();
        if (udpSocket != null && !udpSocket.isClosed()) udpSocket.close();
    }

    public static class RobotStatus {
        public boolean isMoving;
        public String lastCommand;
        public int cameraFacing;
        public long timestamp;
    }
}
