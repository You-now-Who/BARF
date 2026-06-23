// robot_firmware_6motor.ino
// ESP32 firmware for a 4-wheel-drive + 2-mechanism robot, driven over the
// BARF serial protocol ({"m":[fl,fr,bl,br,aux1,aux2]} -> motors).
//
// Drivers: 6x DRV8833 dual H-bridge channels, 2 GPIO pins each.
// Each channel uses the standard DRV8833 two-pin PWM scheme:
//   forward -> PWM on pin A, pin B held low
//   reverse -> PWM on pin B, pin A held low
//   stop    -> both pins low
//
// Pin map:
//   MOTOR_FL   (0): GPIO 32, 33
//   MOTOR_FR   (1): GPIO 25, 26
//   MOTOR_BL   (2): GPIO 27, 14
//   MOTOR_BR   (3): GPIO 16, 17
//   MOTOR_AUX1 (4): GPIO 18, 19
//   MOTOR_AUX2 (5): GPIO 21, 22
//
// This file only implements setupMotors()/loopMotors()/readSensors()/
// onControlMessage() — it relies on robot_firmware_template.ino for
// setup()/loop() and serial parsing. Put all three files in the same
// sketch folder:
//   robot_firmware.h
//   robot_firmware_template.ino
//   robot_firmware_6motor.ino  (this file)
//
// 1. Install ArduinoJson: arduino-cli lib install ArduinoJson
// 2. Copy all three files into a sketch folder
// 3. Compile & flash from the BARF desktop app

#include "robot_firmware.h"
#include <ArduinoJson.h>

struct MotorPins {
    uint8_t a;
    uint8_t b;
};

static const MotorPins kMotorPins[MOTOR_CHANNEL_COUNT] = {
    {32, 33},  // MOTOR_FL
    {25, 26},  // MOTOR_FR
    {27, 14},  // MOTOR_BL
    {16, 17},  // MOTOR_BR
    {18, 19},  // MOTOR_AUX1
    {21, 22},  // MOTOR_AUX2
};

static void setMotor(int channel, int16_t speed) {
    const MotorPins& p = kMotorPins[channel];
    int duty = constrain((int)abs(speed), 0, 255);
    if (speed >= 0) {
        analogWrite(p.a, duty);
        analogWrite(p.b, 0);
    } else {
        analogWrite(p.a, 0);
        analogWrite(p.b, duty);
    }
}

void setupMotors() {
    for (int i = 0; i < MOTOR_CHANNEL_COUNT; i++) {
        pinMode(kMotorPins[i].a, OUTPUT);
        pinMode(kMotorPins[i].b, OUTPUT);
        analogWrite(kMotorPins[i].a, 0);
        analogWrite(kMotorPins[i].b, 0);
    }
}

void loopMotors(MotorState& state, bool timedOut) {
    for (int i = 0; i < MOTOR_CHANNEL_COUNT; i++) {
        setMotor(i, timedOut ? 0 : state.speeds[i]);
    }
}

void readSensors(int32_t* values, int& count) {
    count = 0;  // no sensors wired yet
}

void onControlMessage(const String& cmd) {
    // no custom control messages
}
