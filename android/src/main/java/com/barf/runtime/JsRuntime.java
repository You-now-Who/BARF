package com.barf.runtime;

import android.util.Log;

import com.barf.AppLog;
import com.barf.serial.SerialProtocol;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JavaScript execution engine. Currently wraps Rhino (Android-compatible).
 * The JS API surface: move(), rotate(), stop(), sleep(), log(), onDetection(), getLastDetections().
 * Future: swap Rhino for GraalJS once it supports Android.
 */
public class JsRuntime {
    private static final String TAG = "JsRuntime";

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final StringBuilder output = new StringBuilder();
    private String lastError = null;
    private Thread executionThread = null;
    private Context rhinoContext = null;
    private volatile String lastDetectionsJson = "[]";
    private volatile float accelX = 0, accelY = 0, accelZ = 0;
    private volatile ScriptableObject globalScope = null;
    private JsCommandCallback callback;
    private Runnable startListener;

    public interface JsCommandCallback {
        void onMove(String direction, int pwm);
        void onRotate(String direction, int pwm);
        /** JS called stop() — halt motors, but keep session alive (don't reset autoshutdown). */
        void onStop();
        /** Script thread fully exited — do full cleanup (reset autoshutdown, release wake lock). */
        void onScriptEnd();
        void onRawMotors(int[] speeds);
        void onLiftMotors(int speed);
        void onLiftTimed(int speed, int ms);
        void onEnableAutoStop();
    }

    public void setCallback(JsCommandCallback callback) {
        this.callback = callback;
    }

    public void setStartListener(Runnable listener) {
        this.startListener = listener;
    }

    public boolean isRunning() {
        return running.get();
    }

    public String getOutput() {
        return output.toString();
    }

    public String getLastError() {
        return lastError;
    }

    public void clearOutput() {
        output.setLength(0);
        lastError = null;
    }

    public void execute(String script) {
        if (running.get()) {
            throw new IllegalStateException("Script is already running");
        }

        clearOutput();
        running.set(true);
        if (startListener != null) startListener.run();

        executionThread = new Thread(() -> {
            try {
                executeScript(script);
            } catch (Exception e) {
                lastError = e.getMessage();
                appendOutput("ERROR: " + e.getMessage());
                Log.e(TAG, "Script execution error", e);
            } finally {
                running.set(false);
                if (callback != null) callback.onScriptEnd();
            }
        });
        executionThread.start();
    }

    public void stop() {
        if (!running.get()) return;
        running.set(false);

        synchronized (this) {
            globalScope = null;
        }

        if (executionThread != null) {
            executionThread.interrupt();
        }

        if (rhinoContext != null) {
            try {
                rhinoContext.setGeneratingDebug(false);
            } catch (Exception ignored) {}
        }

        if (callback != null) callback.onScriptEnd();
        appendOutput("Script stopped by user");
    }

    public void setAccelerometer(float x, float y, float z) {
        accelX = x; accelY = y; accelZ = z;
    }

    public void pushDetections(String detectionsJson) {
        if (detectionsJson == null) detectionsJson = "[]";
        lastDetectionsJson = detectionsJson;

        if (!running.get()) return;

        ScriptableObject scope;
        synchronized (this) {
            scope = this.globalScope;
        }

        if (scope == null) return;

        Context cx = Context.enter();
        try {
            cx.setOptimizationLevel(-1);
            Object cb = ScriptableObject.getProperty(scope, "__yolo_onDetection");
            if (cb instanceof Function) {
                Function fn = (Function) cb;
                // Eval as a JS expression so frame.yolo / frame["yolo"] work natively.
                // javaToJS(gson.fromJson(...)) produces a LinkedTreeMap wrapper where
                // property access returns undefined instead of map values.
                Object arg = cx.evaluateString(scope, "(" + detectionsJson + ")", "<det>", 1, null);
                fn.call(cx, scope, scope, new Object[]{arg});
            }
        } catch (Exception e) {
            appendOutput("Error calling detection callback: " + e.getMessage());
        } finally {
            Context.exit();
        }
    }

    private void executeScript(String script) {
        appendOutput("Starting script execution...");

        rhinoContext = Context.enter();
        try {
            rhinoContext.setOptimizationLevel(-1);
            ScriptableObject scope = rhinoContext.initStandardObjects();

            JsApi api = new JsApi();
            Object wrappedApi = Context.javaToJS(api, scope);
            ScriptableObject.putProperty(scope, "robot", wrappedApi);

            injectHelpers(rhinoContext, scope);

            rhinoContext.evaluateString(scope, script, "script", 1, null);

            // If the script registered an onDetection callback, it's event-driven —
            // keep this thread alive so globalScope stays valid for pushDetections calls.
            // Without this the finally block nulls globalScope before any frame arrives.
            Object cb = ScriptableObject.getProperty(scope, "__yolo_onDetection");
            if (cb instanceof Function) {
                appendOutput("Detection callback registered — listening for frames...");
                while (running.get()) {
                    Thread.sleep(50);
                }
            } else {
                appendOutput("Script completed");
            }

        } catch (RhinoException e) {
            lastError = e.getMessage();
            appendOutput("Script error at line " + e.lineNumber() + ": " + e.details());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            lastError = e.getMessage();
            appendOutput("Error: " + e.getMessage());
        } finally {
            synchronized (this) {
                this.globalScope = null;
            }
            Context.exit();
            rhinoContext = null;
        }
    }

    private void injectHelpers(Context cx, ScriptableObject scope) {
        String helpers =
            "function move(direction, speed) { robot.move(direction, speed); }\n" +
            "function rotate(direction, speed) { robot.rotate(direction, speed); }\n" +
            "function stop() { robot.stop(); }\n" +
            "function sleep(ms) { robot.sleep(ms); }\n" +
            "function log(msg) { robot.log(msg); }\n" +
            "function print(msg) { robot.log(msg); }\n" +
            "function onDetection(fn) { this.__yolo_onDetection = fn; }\n" +
            "function getLastDetections() { try { return JSON.parse(robot.getLastDetections()); } catch(e) { return []; } }\n" +
            "function getAccelerometer() { try { return JSON.parse(robot.getAccelerometer()); } catch(e) { return {x:0,y:0,z:0}; } }\n" +
            "var FORWARD = 'forward';\n" +
            "var BACKWARD = 'backward';\n" +
            "var LEFT = 'left';\n" +
            "var RIGHT = 'right';\n" +
            "var console = { log: function(msg) { robot.log(msg); } };\n" +
            "function rawMotors(arr) { robot.rawMotors(arr); }\n" +
            "function liftMotors(speed) { robot.liftMotors(speed); }\n" +
            "function liftMoveUp(speed, ms) {\n" +
            "  robot.liftTimed(-Math.abs(speed), ms || 0);\n" +
            "}\n" +
            "function liftMoveDown(speed, ms) {\n" +
            "  robot.liftTimed(Math.abs(speed), ms || 0);\n" +
            "}\n" +
            "function autoshutdown() { robot.enableAutoStop(); }\n";

        cx.evaluateString(scope, helpers, "helpers", 1, null);

        synchronized (this) {
            this.globalScope = scope;
        }
    }

    private void appendOutput(String message) {
        String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        output.append("[").append(timestamp).append("] ").append(message).append("\n");
        AppLog.js(TAG, message);
    }

    public class JsApi {
        public void move(String direction, Object pwmObj) {
            checkRunning();
            int pwm = toPwm(pwmObj, 512);
            appendOutput("move('" + direction + "', " + pwm + ")");
            if (callback != null) callback.onMove(direction, pwm);
        }

        public void rotate(String direction, Object pwmObj) {
            checkRunning();
            int pwm = toPwm(pwmObj, 360);
            appendOutput("rotate('" + direction + "', " + pwm + ")");
            if (callback != null) callback.onRotate(direction, pwm);
        }

        private int toPwm(Object v, int def) {
            if (v == null || v instanceof org.mozilla.javascript.Undefined) return def;
            int raw = 0;
            if (v instanceof Number) raw = ((Number) v).intValue();
            else { try { raw = Integer.parseInt(v.toString()); } catch (Exception e) { return def; } }
            return Math.max(0, Math.min(SerialProtocol.MAX_PWM, raw));
        }

        public void stop() {
            appendOutput("stop()");
            if (callback != null) callback.onStop();
        }

        /** Enable auto-stop: motors cut automatically ~150ms after the last move/rotate call. */
        @SuppressWarnings("unused")
        public void enableAutoStop() {
            if (callback != null) callback.onEnableAutoStop();
        }

        /**
         * Send raw per-motor values directly, bypassing the mixing formula.
         * Use this to identify motor wiring: rawMotors([1023,0,0,0,0,0]) spins motor 0 only.
         * Indices: [FL, BL, LIFT1, FR, BR, LIFT2]. Range -1023..1023.
         */
        @SuppressWarnings("unused")
        public void liftMotors(Object speedObj) {
            checkRunning();
            int speed = 0;
            if (speedObj instanceof Number) speed = Math.max(-SerialProtocol.MAX_PWM, Math.min(SerialProtocol.MAX_PWM, ((Number) speedObj).intValue()));
            appendOutput("liftMotors(" + speed + ")");
            if (callback != null) callback.onLiftMotors(speed);
        }

        @SuppressWarnings("unused")
        public void liftTimed(Object speedObj, Object msObj) {
            checkRunning();
            int speed = 0, ms = 0;
            if (speedObj instanceof Number) speed = Math.max(-SerialProtocol.MAX_PWM, Math.min(SerialProtocol.MAX_PWM, ((Number) speedObj).intValue()));
            if (msObj instanceof Number) ms = Math.max(0, ((Number) msObj).intValue());
            if (callback != null) callback.onLiftTimed(speed, ms);
        }

        @SuppressWarnings("unused")
        public void rawMotors(Object arr) {
            if (callback == null) return;
            int[] speeds = new int[6];
            try {
                if (arr instanceof org.mozilla.javascript.NativeArray) {
                    org.mozilla.javascript.NativeArray na = (org.mozilla.javascript.NativeArray) arr;
                    for (int i = 0; i < Math.min(6, (int) na.getLength()); i++) {
                        Object v = na.get(i, na);
                        speeds[i] = (v instanceof Number) ? Math.max(-SerialProtocol.MAX_PWM, Math.min(SerialProtocol.MAX_PWM, ((Number) v).intValue())) : 0;
                    }
                }
            } catch (Exception e) {
                appendOutput("rawMotors error: " + e.getMessage());
                return;
            }
            appendOutput("rawMotors(" + Arrays.toString(speeds) + ")");
            callback.onRawMotors(speeds);
        }

        @SuppressWarnings("unused")
        public void sleep(int ms) throws InterruptedException {
            checkRunning();
            appendOutput("wait(" + ms + "ms)");
            int remaining = ms;
            while (remaining > 0 && running.get()) {
                int sleepTime = Math.min(remaining, 100);
                Thread.sleep(sleepTime);
                remaining -= sleepTime;
            }
            if (!running.get()) throw new InterruptedException("Script stopped");
        }

        @SuppressWarnings("unused")
        public void log(Object message) {
            appendOutput("LOG: " + (message != null ? message.toString() : "null"));
        }

        public String getLastDetections() {
            return lastDetectionsJson != null ? lastDetectionsJson : "[]";
        }

        public String getAccelerometer() {
            return String.format(java.util.Locale.US,
                "{\"x\":%.4f,\"y\":%.4f,\"z\":%.4f}", accelX, accelY, accelZ);
        }

        private void checkRunning() {
            if (!running.get()) throw new RuntimeException("Script stopped");
        }
    }
}
