// robot_firmware.h — Reference header for ESP32 Arduino sketches
//
// Copy this file into your Arduino sketch directory or include it directly.
// Implements the serial protocol contract: {"m":[...]} in, {"s":[...]} out.
//
// Dependencies: ArduinoJson by Benoit Blanchon
//   arduino-cli lib install ArduinoJson

#pragma once
#include <Arduino.h>

// ── Motor channel indices ──────────────────────────────

#define MOTOR_FL 0   // Front Left
#define MOTOR_FR 1   // Front Right
#define MOTOR_BL 2   // Back Left
#define MOTOR_BR 3   // Back Right

// ── Motor state ────────────────────────────────────────

struct MotorState {
    int16_t speeds[4] = {0, 0, 0, 0};
    unsigned long last_command_ms = 0;

    // If no command received in this window, motors stop (safety feature)
    static constexpr unsigned long TIMEOUT_MS = 500;

    bool isTimedOut() const {
        return (millis() - last_command_ms) > TIMEOUT_MS;
    }

    void stop() {
        memset(speeds, 0, sizeof(speeds));
    }
};

// ── Serial protocol constants ──────────────────────────

static constexpr unsigned long SERIAL_BAUD = 115200;
static constexpr char MSG_MOTORS  = 'm';  // Motor command: {"m":[255,0,-128,0]}
static constexpr char MSG_SENSORS = 's';  // Sensor data:   {"s":[142,138,0,0]}
static constexpr char MSG_CONTROL = 'c';  // Control msg:   {"c":"ping"}

// ── You implement these ────────────────────────────────

// Called once at startup. Configure pins, encoders, etc.
void setupMotors();

// Called every loop iteration with interpreted motor speeds.
// `timedOut` is true when the phone hasn't sent a command recently (safety stop).
void loopMotors(MotorState& state, bool timedOut);

// Populate `values` with current sensor readings and set `count`.
// Default returns zeros. Override to send encoder counts or other data.
void readSensors(int32_t* values, int& count);

// Called when a control message arrives ("ping", "reset", etc.)
void onControlMessage(const String& cmd);
