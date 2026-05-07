package com.barf.serial;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.util.Log;

import java.io.UnsupportedEncodingException;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages USB-serial (CDC ACM) communication with the ESP32.
 * Replaces the old UDP approach with a direct USB-OTG connection.
 */
public class UsbSerialManager {
    private static final String TAG = "UsbSerialManager";
    private static final String ACTION_USB_PERMISSION = "com.barf.serial.USB_PERMISSION";

    // Common ESP32 USB vendor IDs
    private static final int[] ESP32_VENDOR_IDS = {0x10C4, 0x303A, 0x1A86, 0x0403};
    private static final int ESP32_SILICON_CP2102 = 0x10C4;
    private static final int ESP32_SILICON_CH340 = 0x1A86;
    private static final int ESP32_TENSY = 0x0403;

    private final Context context;
    private final UsbManager usbManager;

    private UsbDeviceConnection connection;
    private UsbEndpoint readEndpoint;
    private UsbEndpoint writeEndpoint;
    private UsbInterface usbInterface;

    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread readThread;

    private final ConcurrentLinkedQueue<String> incomingMessages = new ConcurrentLinkedQueue<>();
    private UsbSerialListener listener;

    private long lastHeartbeatSent = 0;
    private long lastHeartbeatReceived = 0;
    private static final long HEARTBEAT_INTERVAL_MS = 2000;
    private static final long HEARTBEAT_TIMEOUT_MS = 3000;

    public interface UsbSerialListener {
        void onConnected();
        void onDisconnected();
        void onMessageReceived(String line);
        void onHeartbeatTimeout();
    }

    public UsbSerialManager(Context context) {
        this.context = context;
        this.usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
    }

    public void setListener(UsbSerialListener listener) {
        this.listener = listener;
    }

    public boolean isConnected() {
        return connected.get();
    }

    /**
     * Find and connect to the first ESP32 device found.
     */
    public boolean connect() {
        if (connected.get()) return true;

        UsbDevice device = findEsp32Device();
        if (device == null) {
            Log.w(TAG, "No ESP32 device found");
            return false;
        }

        return connectToDevice(device);
    }

    /**
     * Connect to a specific USB device.
     */
    public boolean connectToDevice(UsbDevice device) {
        if (connected.get()) return true;

        // Request permission if needed
        if (!usbManager.hasPermission(device)) {
            PendingIntent permissionIntent = PendingIntent.getBroadcast(
                    context, 0, new Intent(ACTION_USB_PERMISSION),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            context.registerReceiver(usbReceiver, new IntentFilter(ACTION_USB_PERMISSION));
            usbManager.requestPermission(device, permissionIntent);
            return false; // Will retry via broadcast receiver
        }

        return openConnection(device);
    }

    private boolean openConnection(UsbDevice device) {
        // Find the CDC ACM data interface (usually the second interface)
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            if (iface.getInterfaceClass() == UsbConstants.USB_CLASS_CDC_DATA) {
                usbInterface = iface;
                break;
            }
        }

        // Fallback: try the first interface with bulk endpoints
        if (usbInterface == null) {
            for (int i = 0; i < device.getInterfaceCount(); i++) {
                UsbInterface iface = device.getInterface(i);
                for (int j = 0; j < iface.getEndpointCount(); j++) {
                    UsbEndpoint ep = iface.getEndpoint(j);
                    if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        usbInterface = iface;
                        break;
                    }
                }
                if (usbInterface != null) break;
            }
        }

        if (usbInterface == null) {
            Log.e(TAG, "No suitable USB interface found");
            return false;
        }

        connection = usbManager.openDevice(device);
        if (connection == null) {
            Log.e(TAG, "Failed to open USB device");
            return false;
        }

        if (!connection.claimInterface(usbInterface, true)) {
            Log.e(TAG, "Failed to claim interface");
            connection.close();
            connection = null;
            return false;
        }

        // Find bulk endpoints
        for (int i = 0; i < usbInterface.getEndpointCount(); i++) {
            UsbEndpoint ep = usbInterface.getEndpoint(i);
            if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.getDirection() == UsbConstants.USB_DIR_IN) {
                    readEndpoint = ep;
                } else {
                    writeEndpoint = ep;
                }
            }
        }

        if (readEndpoint == null || writeEndpoint == null) {
            Log.e(TAG, "Could not find bulk endpoints");
            connection.releaseInterface(usbInterface);
            connection.close();
            connection = null;
            return false;
        }

        connected.set(true);
        running.set(true);

        // Start read thread
        readThread = new Thread(this::readLoop);
        readThread.setName("UsbSerialRead");
        readThread.start();

        lastHeartbeatReceived = System.currentTimeMillis();
        Log.i(TAG, "USB serial connected: " + device.getProductName());
        if (listener != null) listener.onConnected();
        return true;
    }

    /**
     * Send motor command via USB serial.
     */
    public void sendMotorCommand(int[] speeds) {
        String msg = SerialProtocol.motorCommand(speeds);
        write(msg);
    }

    /**
     * Send raw string via USB serial.
     */
    public synchronized void write(String data) {
        if (!connected.get() || connection == null) return;
        try {
            byte[] buf = data.getBytes("UTF-8");
            int sent = connection.bulkTransfer(writeEndpoint, buf, buf.length, 100);
            if (sent < 0) {
                Log.w(TAG, "Write failed");
            }
        } catch (UnsupportedEncodingException e) {
            Log.e(TAG, "Encoding error: " + e.getMessage());
        }
    }

    /**
     * Poll for incoming messages (non-blocking).
     */
    public String pollMessage() {
        return incomingMessages.poll();
    }

    /**
     * Check heartbeat health.
     */
    public void checkHeartbeat() {
        long now = System.currentTimeMillis();

        // Send heartbeat if due
        if (now - lastHeartbeatSent > HEARTBEAT_INTERVAL_MS) {
            write(SerialProtocol.ping());
            lastHeartbeatSent = now;
        }

        // Check for timeout
        if (now - lastHeartbeatReceived > HEARTBEAT_TIMEOUT_MS) {
            Log.w(TAG, "Heartbeat timeout");
            if (listener != null) listener.onHeartbeatTimeout();
        }
    }

    /**
     * Disconnect from USB device.
     */
    public void disconnect() {
        running.set(false);
        connected.set(false);

        if (readThread != null) {
            readThread.interrupt();
            try { readThread.join(1000); } catch (InterruptedException ignored) {}
            readThread = null;
        }

        if (connection != null) {
            if (usbInterface != null) connection.releaseInterface(usbInterface);
            connection.close();
            connection = null;
        }

        incomingMessages.clear();
        Log.i(TAG, "USB serial disconnected");
        if (listener != null) listener.onDisconnected();
    }

    private void readLoop() {
        byte[] buffer = new byte[512];
        StringBuilder lineBuffer = new StringBuilder();

        while (running.get() && connected.get()) {
            try {
                int len = connection.bulkTransfer(readEndpoint, buffer, buffer.length, 100);
                if (len > 0) {
                    String chunk = new String(buffer, 0, len, "UTF-8");
                    lineBuffer.append(chunk);

                    // Process complete lines
                    String current = lineBuffer.toString();
                    int newlineIdx;
                    while ((newlineIdx = current.indexOf('\n')) >= 0) {
                        String line = current.substring(0, newlineIdx).trim();
                        current = current.substring(newlineIdx + 1);
                        if (!line.isEmpty()) {
                            onLineReceived(line);
                        }
                    }
                    lineBuffer.setLength(0);
                    lineBuffer.append(current);
                }
            } catch (Exception e) {
                if (running.get()) {
                    Log.e(TAG, "Read error: " + e.getMessage());
                }
            }
        }
    }

    private void onLineReceived(String line) {
        Log.d(TAG, "RX: " + line);
        incomingMessages.offer(line);

        // Check for pong heartbeat response
        if (line.contains("\"c\"") && line.contains("pong")) {
            lastHeartbeatReceived = System.currentTimeMillis();
        }

        if (listener != null) listener.onMessageReceived(line);
    }

    private UsbDevice findEsp32Device() {
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            int vid = device.getVendorId();
            for (int expectedVid : ESP32_VENDOR_IDS) {
                if (vid == expectedVid) return device;
            }
        }
        return null;
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_USB_PERMISSION.equals(intent.getAction())) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && device != null) {
                    openConnection(device);
                } else {
                    Log.w(TAG, "USB permission denied");
                }
                try {
                    context.unregisterReceiver(this);
                } catch (Exception ignored) {}
            }
        }
    };
}
