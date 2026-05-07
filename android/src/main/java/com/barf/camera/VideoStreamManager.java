package com.barf.camera;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.PixelCopy;
import android.view.SurfaceView;

import com.barf.VideoStreamServer;

/**
 * Manages the video streaming loop: captures frames from SurfaceView via PixelCopy
 * and feeds them to VideoStreamServer for MJPEG/WebRTC distribution.
 */
public class VideoStreamManager {
    private static final String TAG = "VideoStreamManager";

    private final SurfaceView cameraView;
    private final VideoStreamServer videoStreamServer;
    private final CameraManager cameraManager;

    private Thread videoThread;
    private volatile boolean running = false;

    public VideoStreamManager(SurfaceView cameraView, VideoStreamServer videoStreamServer, CameraManager cameraManager) {
        this.cameraView = cameraView;
        this.videoStreamServer = videoStreamServer;
        this.cameraManager = cameraManager;
    }

    public void start() {
        if (running) return;
        running = true;

        videoThread = new Thread(() -> {
            Log.i(TAG, "Video streaming thread started");
            long frameCounter = 0;
            long lastLogTime = System.currentTimeMillis();
            java.util.concurrent.atomic.AtomicInteger pendingCopies = new java.util.concurrent.atomic.AtomicInteger(0);

            while (running) {
                try {
                    if (cameraView != null && cameraView.getHolder().getSurface().isValid()) {
                        int width = cameraView.getWidth();
                        int height = cameraView.getHeight();

                        if (width > 0 && height > 0 && pendingCopies.get() < 3) {
                            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                            pendingCopies.incrementAndGet();

                            Handler mainHandler = new Handler(Looper.getMainLooper());
                            PixelCopy.request(cameraView, bitmap, result -> {
                                try {
                                    if (result == PixelCopy.SUCCESS) {
                                        Bitmap rotated = fixOrientation(bitmap);
                                        videoStreamServer.submitFrame(rotated);
                                        if (rotated != bitmap) bitmap.recycle();
                                    }
                                } catch (Exception e) {
                                    Log.w(TAG, "Error submitting frame: " + e.getMessage());
                                } finally {
                                    pendingCopies.decrementAndGet();
                                }
                            }, mainHandler);

                            frameCounter++;
                        }

                        long now = System.currentTimeMillis();
                        if (now - lastLogTime >= 3000) {
                            Log.i(TAG, "Video: submitted " + frameCounter + " frames, pending: " + pendingCopies.get());
                            lastLogTime = now;
                        }
                    }

                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    Log.e(TAG, "Error in video stream: " + e.getMessage(), e);
                    try { Thread.sleep(500); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            Log.i(TAG, "Video streaming thread stopped");
        });

        videoThread.setName("VideoStream");
        videoThread.start();
    }

    public void stop() {
        running = false;
        if (videoThread != null) {
            videoThread.interrupt();
            try { videoThread.join(2000); } catch (InterruptedException ignored) {}
            videoThread = null;
        }
    }

    private Bitmap fixOrientation(Bitmap src) {
        if (src == null || src.getWidth() >= src.getHeight()) return src;
        int degrees = (cameraManager.getFacing() == 1) ? 270 : 90;
        Matrix matrix = new Matrix();
        matrix.postRotate(degrees);
        return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), matrix, true);
    }
}
