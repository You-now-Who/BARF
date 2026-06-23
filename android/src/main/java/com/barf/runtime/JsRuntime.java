package com.barf.runtime;

import android.util.Log;

import com.barf.AppLog;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import java.text.SimpleDateFormat;
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
    private volatile ScriptableObject globalScope = null;
    private JsCommandCallback callback;

    public interface JsCommandCallback {
        void onMove(String direction, float speed);
        void onRotate(String direction, float speed);
        void onStop();
    }

    public void setCallback(JsCommandCallback callback) {
        this.callback = callback;
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

        executionThread = new Thread(() -> {
            try {
                executeScript(script);
            } catch (Exception e) {
                lastError = e.getMessage();
                appendOutput("ERROR: " + e.getMessage());
                Log.e(TAG, "Script execution error", e);
            } finally {
                running.set(false);
                if (callback != null) callback.onStop();
            }
        });
        executionThread.start();
    }

    public void stop() {
        if (!running.get()) return;
        running.set(false);

        if (executionThread != null) {
            executionThread.interrupt();
        }

        if (rhinoContext != null) {
            try {
                rhinoContext.setGeneratingDebug(false);
            } catch (Exception ignored) {}
        }

        if (callback != null) callback.onStop();
        appendOutput("Script stopped by user");
    }

    public void pushDetections(String detectionsJson) {
        if (detectionsJson == null) detectionsJson = "[]";
        lastDetectionsJson = detectionsJson;

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
            "var FORWARD = 'forward';\n" +
            "var BACKWARD = 'backward';\n" +
            "var LEFT = 'left';\n" +
            "var RIGHT = 'right';\n" +
            "var console = { log: function(msg) { robot.log(msg); } };\n";

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
        public void move(String direction, double speed) {
            checkRunning();
            float s = Math.max(0f, Math.min(1f, (float) speed));
            appendOutput("move('" + direction + "', " + s + ")");
            if (callback != null) callback.onMove(direction, s);
        }

        public void rotate(String direction, double speed) {
            checkRunning();
            float s = Math.max(0f, Math.min(1f, (float) speed));
            appendOutput("rotate('" + direction + "', " + s + ")");
            if (callback != null) callback.onRotate(direction, s);
        }

        public void stop() {
            appendOutput("stop()");
            if (callback != null) callback.onStop();
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

        private void checkRunning() {
            if (!running.get()) throw new RuntimeException("Script stopped");
        }
    }
}
