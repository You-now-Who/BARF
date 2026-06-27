package com.barf;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-app logger that mirrors all messages to both Android logcat and an in-app
 * scrolling console. Call AppLog.i/d/w/e instead of Log.i/d/w/e anywhere you
 * want messages to appear on-screen.
 */
public class AppLog {
    private static final int MAX_LINES = 400;

    // System log
    private static final ArrayDeque<String> lines = new ArrayDeque<>();
    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();

    // JS-only log
    private static final ArrayDeque<String> jsLines = new ArrayDeque<>();
    private static final List<Listener> jsListeners = new CopyOnWriteArrayList<>();

    private static final SimpleDateFormat fmt =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    public interface Listener {
        void onNewLine(String line);
    }

    public static void setListener(Listener l) {
        listeners.clear();
        if (l != null) {
            listeners.add(l);
            synchronized (AppLog.class) {
                for (String line : lines) l.onNewLine(line);
            }
        }
    }

    public static void addListener(Listener l) {
        if (l != null) listeners.add(l);
    }

    public static void setJsListener(Listener l) {
        jsListeners.clear();
        if (l != null) {
            jsListeners.add(l);
            synchronized (AppLog.class) {
                for (String line : jsLines) l.onNewLine(line);
            }
        }
    }

    public static void addJsListener(Listener l) {
        if (l != null) jsListeners.add(l);
    }

    public static void i(String tag, String msg) { log("I", tag, msg); Log.i(tag, msg); }
    public static void d(String tag, String msg) { log("D", tag, msg); Log.d(tag, msg); }
    public static void w(String tag, String msg) { log("W", tag, msg); Log.w(tag, msg); }
    public static void e(String tag, String msg) { log("E", tag, msg); Log.e(tag, msg); }

    /** Routes to the JS log panel only (not the system log). */
    public static void js(String tag, String msg) {
        String line = fmt.format(new Date()) + " JS: " + msg;
        synchronized (AppLog.class) {
            if (jsLines.size() >= MAX_LINES) jsLines.pollFirst();
            jsLines.addLast(line);
        }
        for (Listener l : jsListeners) l.onNewLine(line);
        Log.d(tag, "[JS] " + msg);
    }

    private static synchronized void log(String level, String tag, String msg) {
        String line = fmt.format(new Date()) + " " + level + "/" + tag + ": " + msg;
        if (lines.size() >= MAX_LINES) lines.pollFirst();
        lines.addLast(line);
        for (Listener l : listeners) l.onNewLine(line);
    }

    public static synchronized void clear() {
        lines.clear();
    }

    public static synchronized void clearJs() {
        jsLines.clear();
    }
}
