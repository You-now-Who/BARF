// robot_firmware.h — Reference header for the BARF ESP32 firmware
//
// Implements the serial protocol contract: {"m":[...]} in, {"s":[...]} out.
//
// Dependencies: ArduinoJson by Benoit Blanchon (declared in platformio.ini)

#pragma once
#include <Arduino.h>

// ── Motor channel indices ──────────────────────────────

#define MOTOR_FL 0   // Front Left
#define MOTOR_FR 1   // Front Right
#define MOTOR_BL 2   // Back Left
#define MOTOR_BR 3   // Back Right
#define MOTOR_AUX1 4 // Auxiliary mechanism 1 (e.g. arm)
#define MOTOR_AUX2 5 // Auxiliary mechanism 2 (e.g. intake/gripper)

#define MOTOR_CHANNEL_COUNT 6

// ── Motor state ────────────────────────────────────────

struct MotorState {
    int16_t speeds[MOTOR_CHANNEL_COUNT] = {0, 0, 0, 0, 0, 0};
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

// ── PWM (LEDC) configuration ───────────────────────────
// Single source of truth for the motor PWM range. PWM_MAX must match the
// Android side (SerialProtocol.MAX_PWM). Constraint: PWM_FREQ_HZ * 2^PWM_RES_BITS
// must stay below the 80 MHz LEDC clock — 20 kHz @ 10-bit = 20.48 MHz, well within.

static constexpr int     PWM_FREQ_HZ  = 20000;                   // 20 kHz carrier (above audible)
static constexpr uint8_t PWM_RES_BITS = 10;                      // 10-bit duty resolution
static constexpr int     PWM_MAX      = (1 << PWM_RES_BITS) - 1; // 1023

// Configure the standard Arduino analogWrite() path (LEDC-backed on ESP32) for
// motor PWM. analogWrite() lazily allocates an LEDC channel per pin on first
// use, so all we need up front is the global resolution/frequency. Call once
// from setup() before driving any motor.
inline void pwmBeginMotors() {
    analogWriteResolution(PWM_RES_BITS);  // duty range 0..PWM_MAX (0..1023)
    analogWriteFrequency(PWM_FREQ_HZ);    // 20 kHz, above audible
}

// ── Serial protocol constants ──────────────────────────

static constexpr unsigned long SERIAL_BAUD = 115200;
static constexpr char MSG_MOTORS  = 'm';  // Motor command: {"m":[1023,0,-512,0,0,0]}
static constexpr char MSG_SENSORS = 's';  // Sensor data:   {"s":[142,138,0,0]}
static constexpr char MSG_CONTROL = 'c';  // Control msg:   {"c":"ping"}
