// main.cpp — BARF ESP32 firmware, 6-motor build.
//
// Reads motor commands from the phone over USB-UART
// ({"m":[fl,fr,bl,br,aux1,aux2]}, -1023..1023 per channel) and drives 6
// DRV8833 dual H-bridge channels using the standard two-pin PWM scheme:
//   forward -> PWM on pin A, pin B held low
//   reverse -> PWM on pin B, pin A held low
//   stop    -> both pins low
//
// Pin map: [FL, BL, LIFT1, FR, BR, LIFT2]
//   m[0] FL    : GPIO 32, 33
//   m[1] BL    : GPIO 25, 26
//   m[2] LIFT1 : GPIO 27, 14
//   m[3] FR    : GPIO 16, 17  (IN1/IN2 wired reversed — negated by Android)
//   m[4] BR    : GPIO 18, 19
//   m[5] LIFT2 : GPIO 21, 22

#include <Arduino.h>
#include <ArduinoJson.h>
#include "robot_firmware.h"

struct MotorPins {
    uint8_t a;
    uint8_t b;
};

static const MotorPins kMotorPins[MOTOR_CHANNEL_COUNT] = {
    {32, 33},  // m[0] FL
    {25, 26},  // m[1] BL
    {27, 14},  // m[2] LIFT1
    {16, 17},  // m[3] FR  (reversed wiring, negated by Android)
    {18, 19},  // m[4] BR
    {21, 22},  // m[5] LIFT2
};

static MotorState g_motors;
static String g_serialBuffer;
static unsigned long g_lastHeartbeat = 0;

static void setMotor(int channel, int16_t speed) {
    const MotorPins& p = kMotorPins[channel];
    int duty = constrain((int)abs(speed), 0, PWM_MAX);
    if (speed >= 0) {
        analogWrite(p.a, duty);
        analogWrite(p.b, 0);
    } else {
        analogWrite(p.a, 0);
        analogWrite(p.b, duty);
    }
}

static void runMotors() {
    bool timedOut = g_motors.isTimedOut();
    if (timedOut) g_motors.stop();
    for (int i = 0; i < MOTOR_CHANNEL_COUNT; i++) {
        setMotor(i, g_motors.speeds[i]);
    }
}

static void processLine(const String& line) {
    if (line.length() == 0 || line.charAt(0) != '{') return;

    StaticJsonDocument<256> doc;
    DeserializationError err = deserializeJson(doc, line);
    if (err) {
        Serial.print("{\"l\":\"JSON error: ");
        Serial.print(err.c_str());
        Serial.println("\"}");
        return;
    }

    // ── Motor command: {"m":[1023,0,-512,0,0,0]} ──────
    if (doc.containsKey("m")) {
        JsonArray arr = doc["m"].as<JsonArray>();
        int n = min((int)arr.size(), MOTOR_CHANNEL_COUNT);
        for (int i = 0; i < n; i++) {
            int raw = arr[i];
            g_motors.speeds[i] = constrain(raw, -PWM_MAX, PWM_MAX);
        }
        for (int i = n; i < MOTOR_CHANNEL_COUNT; i++) {
            g_motors.speeds[i] = 0;
        }
        g_motors.last_command_ms = millis();
        return;
    }

    // ── Control: {"c":"ping"} / {"c":"reset"} ────────
    if (doc.containsKey("c")) {
        const char* cmd = doc["c"];
        if (strcmp(cmd, "ping") == 0) {
            Serial.println("{\"c\":\"pong\"}");
            g_motors.last_command_ms = millis();  // treat as keepalive
        } else if (strcmp(cmd, "reset") == 0) {
            Serial.println("{\"c\":\"resetting\"}");
            delay(50);
            ESP.restart();
        }
    }
}

static void readSerial() {
    while (Serial.available()) {
        char c = Serial.read();
        if (c == '\n') {
            processLine(g_serialBuffer);
            g_serialBuffer = "";
        } else if (g_serialBuffer.length() < 256) {
            g_serialBuffer += c;
        }
    }
}

static void heartbeat() {
    unsigned long now = millis();
    if (now - g_lastHeartbeat > 2000) {
        Serial.println("{\"c\":\"pong\"}");
        g_lastHeartbeat = now;
    }
}

void setup() {
    Serial.begin(SERIAL_BAUD);
    while (!Serial) delay(10);

    pwmBeginMotors();  // 20 kHz carrier, 10-bit duty (0..1023)
    for (int i = 0; i < MOTOR_CHANNEL_COUNT; i++) {
        analogWrite(kMotorPins[i].a, 0);  // first write attaches the pin + idles low
        analogWrite(kMotorPins[i].b, 0);
    }

    Serial.println("{\"c\":\"ready\"}");
}

void loop() {
    readSerial();
    runMotors();
    heartbeat();
}
