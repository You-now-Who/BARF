package com.barf.runtime;

import android.util.Log;

/**
 * WAMR (WebAssembly Micro Runtime) wrapper.
 * Loads .wasm files and calls setup() / on_frame() host functions.
 * The native implementation lives in wasm_runtime.cpp.
 */
public class WasmRuntime {
    private static final String TAG = "WasmRuntime";

    static {
        try {
            System.loadLibrary("barf_runtime");
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "barf_runtime native library not available: " + e.getMessage());
        }
    }

    private long nativeHandle = 0;
    private boolean loaded = false;

    // Native methods
    private native long nativeInit();
    private native boolean nativeLoad(long handle, byte[] wasmBytes);
    private native boolean nativeCallSetup(long handle);
    private native String nativeCallOnFrame(long handle, String detectionsJson);
    private native void nativeDestroy(long handle);

    /**
     * Initialize the WASM runtime.
     */
    public boolean init() {
        if (nativeHandle != 0) return true;
        nativeHandle = nativeInit();
        return nativeHandle != 0;
    }

    /**
     * Load a WASM module from bytes.
     */
    public boolean load(byte[] wasmBytes) {
        if (nativeHandle == 0 && !init()) return false;
        boolean ok = nativeLoad(nativeHandle, wasmBytes);
        if (ok) loaded = true;
        return ok;
    }

    /**
     * Call the WASM setup() function.
     */
    public boolean setup() {
        if (!loaded) return false;
        return nativeCallSetup(nativeHandle);
    }

    /**
     * Call the WASM on_frame() function with detection JSON.
     * Returns any motor command result or null.
     */
    public String onFrame(String detectionsJson) {
        if (!loaded) return null;
        return nativeCallOnFrame(nativeHandle, detectionsJson);
    }

    public boolean isLoaded() {
        return loaded;
    }

    /**
     * Clean up and release native resources.
     */
    public void destroy() {
        if (nativeHandle != 0) {
            nativeDestroy(nativeHandle);
            nativeHandle = 0;
            loaded = false;
        }
    }
}
