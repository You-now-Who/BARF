# USB-Serial Communication & Reference Headers

## Overview

The phone communicates with the ESP32 over a USB cable using Android's USB Host API and the CDC ACM protocol. The serial protocol is JSON newline-delimited at 115200 baud. This document covers the Android-side USB-serial implementation and the ESP32-side reference firmware.

## Physical connection

```
Phone (USB Host) ── USB-C to micro-USB/USB-C cable ── ESP32 (USB Device)
```

The phone acts as the USB host and powers the ESP32. The ESP32 must be flashed with firmware that presents as a USB CDC ACM device (Arduino boards do this by default when `Serial` is used).

## Serial protocol

JSON over newline-delimited serial, 115200 baud, 8N1.

### Phone → ESP32 (motor commands)

```json
{"m":[255,0,-128,0]}
```

- `m` = motors
- Array of signed 16-bit integers, one per motor channel
- Range: -255 (full reverse) to 255 (full forward), 0 = stop
- Number of channels depends on the robot configuration (4 for mecanum/diff, 2 for simple tank)

### ESP32 → Phone (sensor data)

```json
{"s":[142,138,0,0]}
```

- `s` = sensors
- Array of signed 32-bit integers, meaning defined by the firmware
- Typically encoder counts, but can be any sensor values the firmware wants to report

### Control messages (both directions)

```json
{"c":"ping"}
{"c":"pong"}
{"c":"reset"}
```

- `c` = control message
- `ping`/`pong` for heartbeat/liveness
- `reset` to software-reset the ESP32

### Log messages (ESP32 → Phone)

```json
{"l":"Motor FL overcurrent, shutting down"}
```

- `l` = log message (human-readable)
- Displayed in the serial monitor, useful for firmware debugging

## Android-side implementation

### UsbSerialManager.java

```java
// Key classes and flow:
// 1. Detect ESP32 USB device via UsbManager.getDeviceList()
//    - Filter by USB_CDC_CLASS or VID/PID
//    - Request permission via UsbManager.requestPermission()
//
// 2. Open the device:
//    UsbDeviceConnection connection = usbManager.openDevice(device);
//    UsbInterface cdcInterface = device.getInterface(0);
//    connection.claimInterface(cdcInterface, true);
//
// 3. Find endpoints:
//    UsbEndpoint epOut = ...; // bulk OUT endpoint
//    UsbEndpoint epIn  = ...; // bulk IN endpoint
//
// 4. Read/write loop (runs on a dedicated thread):
//    - Read: connection.bulkTransfer(epIn, buffer, buffer.length, timeout)
//      → accumulate into a StringBuilder until newline '\n'
//      → parse JSON, dispatch to state handler
//    - Write: connection.bulkTransfer(epOut, jsonBytes, jsonBytes.length, timeout)
//      → called from robot control thread
//
// BAUDRATE: 115200
// DATA BITS: 8
// STOP BITS: 1
// PARITY: none
// FLOW CONTROL: none
```

### Dependencies

- No external library needed. Android's `android.hardware.usb` package provides everything.
- ESP32-S3 and ESP32-C3 boards present as native USB CDC ACM — the Android kernel already has the CDC ACM driver.

### AndroidManifest.xml additions

```xml
<uses-feature android:name="android.hardware.usb.host" android:required="true" />

<!-- Auto-launch when ESP32 is plugged in -->
<intent-filter>
    <action android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED" />
</intent-filter>
<meta-data
    android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED"
    android:resource="@xml/device_filter" />
```

### res/xml/device_filter.xml

```xml
<resources>
    <!-- ESP32-S3 USB CDC ACM -->
    <usb-device vendor-id="12346" /> <!-- Common ESP32 USB VID -->
</resources>
```

### Lifecycle

```
PHONE BOOT:
  App starts → check for connected USB devices → ESP32 found?
    YES → open CDC ACM, start read thread
    NO  → wait for USB_DEVICE_ATTACHED broadcast

USB PLUGGED IN:
  Broadcast received → request permission → open CDC ACM → start read thread

USB UNPLUGGED:
  Read thread gets IOException → close connection → notify UI → wait for reattach

COMPETITION MODE:
  ESP32 plugged in at power-on → serial link established → WASM starts
  → motor commands flow continuously
  → if ESP32 unplugged, WASM gets error on host_move(), script should handle gracefully
```

### Error handling

- USB disconnect: IOException on bulkTransfer, UsbSerialManager posts ERROR_DISCONNECTED to state, WASM `host_move()` calls return error codes
- ESP32 crash/unresponsive: heartbeat ping every 2s, if no pong in 5s, UsbSerialManager posts ERROR_NO_RESPONSE
- Buffer overflow: the read buffer is bounded, malformed JSON (missing newline) is discarded after 4KB

## ESP32-side reference firmware

### robot_firmware.h

```cpp
// robot_firmware.h — Reference header for ESP32 Arduino sketches
// This defines the serial protocol contract between phone and ESP32.
// Include this in your firmware sketch and implement setupMotors() and loopMotors().

#pragma once
#include <Arduino.h>

// Motor channel indices
#define MOTOR_FL 0   // Front Left
#define MOTOR_FR 1   // Front Right
#define MOTOR_BL 2   // Back Left
#define MOTOR_BR 3   // Back Right

// MotorState holds the last received motor command
struct MotorState {
    int16_t speeds[4] = {0, 0, 0, 0};
    unsigned long last_command_ms = 0;
    
    // Safety: if no command received in this many ms, stop motors
    static constexpr unsigned long TIMEOUT_MS = 500;
    
    bool isTimedOut() const {
        return (millis() - last_command_ms) > TIMEOUT_MS;
    }
};

// Serial protocol constants
static constexpr unsigned long SERIAL_BAUD = 115200;
static constexpr char MSG_MOTORS = 'm';
static constexpr char MSG_SENSORS = 's';
static constexpr char MSG_CONTROL = 'c';

// ── You implement these ────────────────────────────────

// Called once at startup. Set up your motor pins, encoders, etc.
void setupMotors();

// Called every loop iteration with the latest motor speeds.
// If timedOut is true, all speeds will be zero (safety stop).
void loopMotors(MotorState& state, bool timedOut);

// ── Optional: override to send custom sensor data ──────

// Return sensor values to send back to the phone.
// Default returns zeros. Override to send encoder counts, etc.
void readSensors(int32_t* values, int& count);

// Called when the phone sends a control message ("ping", "reset")
void onControlMessage(const String& cmd);
```

### robot_firmware_template.ino

```cpp
// robot_firmware_template.ino
// Minimal working ESP32 firmware for BARF.
// Copy this and implement setupMotors() and loopMotors() for your robot.

#include "robot_firmware.h"
#include <ArduinoJson.h>

static MotorState g_motors;         // Current motor state
static String g_serialBuffer;       // Incoming line buffer
static unsigned long g_lastPing = 0;

void setup() {
    Serial.begin(SERIAL_BAUD);
    while (!Serial) delay(10);      // Wait for USB-serial to connect
    
    setupMotors();
    
    // Tell phone we're ready
    Serial.println("{\"c\":\"ready\"}");
}

void loop() {
    // ── Read incoming serial ────────────────────────────
    while (Serial.available()) {
        char c = Serial.read();
        if (c == '\n') {
            processLine(g_serialBuffer);
            g_serialBuffer = "";
        } else if (g_serialBuffer.length() < 256) {
            g_serialBuffer += c;
        }
    }
    
    // ── Safety timeout check ────────────────────────────
    bool timedOut = g_motors.isTimedOut();
    if (timedOut) {
        memset(g_motors.speeds, 0, sizeof(g_motors.speeds));
    }
    
    // ── Run motors ──────────────────────────────────────
    loopMotors(g_motors, timedOut);
    
    // ── Send sensor data ────────────────────────────────
    int32_t sensorValues[8] = {0};
    int sensorCount = 0;
    readSensors(sensorValues, sensorCount);
    
    if (sensorCount > 0) {
        Serial.print("{\"s\":[");
        for (int i = 0; i < sensorCount; i++) {
            if (i > 0) Serial.print(",");
            Serial.print(sensorValues[i]);
        }
        Serial.println("]}");
    }
    
    // ── Heartbeat ───────────────────────────────────────
    if (millis() - g_lastPing > 2000) {
        Serial.println("{\"c\":\"pong\"}");
        g_lastPing = millis();
    }
}

void processLine(const String& line) {
    if (line.length() == 0) return;
    
    // Quick dispatch on first character before full JSON parse
    if (line.charAt(0) != '{') return;
    
    StaticJsonDocument<256> doc;
    DeserializationError err = deserializeJson(doc, line);
    if (err) {
        Serial.print("{\"l\":\"JSON error: ");
        Serial.print(err.c_str());
        Serial.println("\"}");
        return;
    }
    
    if (doc.containsKey("m")) {
        // Motor command: {"m":[255,0,-128,0]}
        JsonArray arr = doc["m"];
        int n = min((int)arr.size(), 4);
        for (int i = 0; i < n; i++) {
            int raw = arr[i];
            g_motors.speeds[i] = constrain(raw, -255, 255);
        }
        for (int i = n; i < 4; i++) {
            g_motors.speeds[i] = 0;
        }
        g_motors.last_command_ms = millis();
        
    } else if (doc.containsKey("c")) {
        // Control message: {"c":"ping"}
        const char* cmd = doc["c"];
        if (strcmp(cmd, "ping") == 0) {
            Serial.println("{\"c\":\"pong\"}");
            g_motors.last_command_ms = millis();
        } else if (strcmp(cmd, "reset") == 0) {
            ESP.restart();
        }
        onControlMessage(String(cmd));
    }
}

// ── Default implementations (override in your sketch) ──

void __attribute__((weak)) setupMotors() {
    // User implements this
}

void __attribute__((weak)) loopMotors(MotorState& state, bool timedOut) {
    // User implements this — e.g.:
    // analogWrite(MOTOR_FL_PIN, abs(state.speeds[MOTOR_FL]));
    // digitalWrite(MOTOR_FL_DIR, state.speeds[MOTOR_FL] > 0 ? HIGH : LOW);
}

void __attribute__((weak)) readSensors(int32_t* values, int& count) {
    count = 0;
    // User implements this — e.g.:
    // values[0] = encoderFL.read();
    // count = 4;
}

void __attribute__((weak)) onControlMessage(const String& cmd) {
    // Optional: handle custom control messages
}
```

### Dependencies (Arduino libraries)

- **ArduinoJson** by Benoit Blanchon (for JSON parsing on the ESP32)
  - Install via Arduino Library Manager or `arduino-cli lib install ArduinoJson`
  - Using `StaticJsonDocument<256>` to avoid heap allocation

### Wiring notes

No special wiring — just a USB cable from phone to ESP32. The ESP32's built-in USB-serial peripheral handles everything. For boards without native USB (original ESP32-DevKitC), use a CP2102 or CH340 USB-UART bridge on the UART0 pins — but prefer ESP32-S3 or ESP32-C3 boards which have native USB CDC.

### Power considerations

- The phone provides 5V over USB-OTG
- Most ESP32 dev boards accept 5V on the USB port and have an onboard regulator
- If the robot has its own battery, power the ESP32 from the robot battery instead. Only connect GND + D+ + D- from the phone, not VBUS
