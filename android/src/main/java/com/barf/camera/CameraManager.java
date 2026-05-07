package com.barf.camera;

import com.barf.YoloBridge;

/**
 * Manages camera open/close/switch lifecycle.
 * Owns the facing state and delegates to YoloBridge for native camera operations.
 */
public class CameraManager {
    private final YoloBridge yolo;
    private int facing = 1; // 0=back, 1=front

    public CameraManager(YoloBridge yolo) {
        this.yolo = yolo;
    }

    public void open() {
        yolo.openCamera(facing);
    }

    public void open(int facing) {
        this.facing = facing;
        yolo.openCamera(facing);
    }

    public void close() {
        yolo.closeCamera();
    }

    public void switchCamera() {
        int newFacing = 1 - facing;
        yolo.closeCamera();
        yolo.openCamera(newFacing);
        facing = newFacing;
    }

    public int getFacing() {
        return facing;
    }

    public void setDisplayOrientation(int degrees) {
        yolo.setDisplayOrientation(degrees);
    }
}
