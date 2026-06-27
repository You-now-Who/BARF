// robot_firmware_template.ino
// Minimal working ESP32 firmware for BARF.
// 1. Install ArduinoJson: arduino-cli lib install ArduinoJson
// 2. Copy this file into a sketch folder
// 3. Implement setupMotors() and loopMotors() for your robot
// 4. Compile & flash from the BARF desktop app

#include "robot_firmware.h"
#include <ArduinoJson.h>

static MotorState g_motors;
static String g_serialBuffer;
static unsigned long g_lastHeartbeat = 0;

// ───────────────────────────────────────────────────────
// SETUP
// ───────────────────────────────────────────────────────

void setup() {
    Serial.begin(SERIAL_BAUD);
    while (!Serial) delay(10);

    setupMotors();

    // Tell the phone we're alive
    Serial.println("{\"c\":\"ready\"}");
}

// ───────────────────────────────────────────────────────
// MAIN LOOP
// ───────────────────────────────────────────────────────

void loop() {
    readSerial();
    runMotors();
    sendSensors();
    heartbeat();
}

// ── Serial read ────────────────────────────────────────

void readSerial() {
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

void processLine(const String& line) {
    if (line.length() == 0 || line.charAt(0) != '{') return;

    StaticJsonDocument<256> doc;
    DeserializationError err = deserializeJson(doc, line);
    if (err) {
        Serial.print("{\"l\":\"JSON error: ");
        Serial.print(err.c_str());
        Serial.println("\"}");
        return;
    }

    // ── Motor command: {"m":[1023,0,-512,0,0,0]} ─────
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
        onControlMessage(String(cmd));
    }
}

// ── Motor output ───────────────────────────────────────

void runMotors() {
    bool timedOut = g_motors.isTimedOut();
    if (timedOut) {
        g_motors.stop();
    }
    loopMotors(g_motors, timedOut);
}

// ── Sensor reporting ───────────────────────────────────

void sendSensors() {
    int32_t values[8] = {0};
    int count = 0;
    readSensors(values, count);

    if (count > 0) {
        Serial.print("{\"s\":[");
        for (int i = 0; i < count; i++) {
            if (i > 0) Serial.print(",");
            Serial.print(values[i]);
        }
        Serial.println("]}");
    }
}

// ── Heartbeat ──────────────────────────────────────────

void heartbeat() {
    unsigned long now = millis();
    if (now - g_lastHeartbeat > 2000) {
        Serial.println("{\"c\":\"pong\"}");
        g_lastHeartbeat = now;
    }
}

// ───────────────────────────────────────────────────────
// DEFAULT IMPLEMENTATIONS (override in your sketch)
// ───────────────────────────────────────────────────────

void __attribute__((weak)) setupMotors() {
    // Example for a simple 2-motor differential drive:
    //
    // pinMode(4, OUTPUT);   // FL PWM
    // pinMode(5, OUTPUT);   // FL direction
    // pinMode(6, OUTPUT);   // FR PWM
    // pinMode(7, OUTPUT);   // FR direction
}

void __attribute__((weak)) loopMotors(MotorState& state, bool timedOut) {
    // Example for 2-motor differential drive:
    //
    // void setMotor(int pwmPin, int dirPin, int16_t speed) {
    //     digitalWrite(dirPin, speed >= 0 ? HIGH : LOW);
    //     analogWrite(pwmPin, abs(speed));
    // }
    // setMotor(4, 5, state.speeds[MOTOR_FL]);
    // setMotor(6, 7, state.speeds[MOTOR_FR]);
}

void __attribute__((weak)) readSensors(int32_t* values, int& count) {
    count = 0;
    // Example:
    // values[0] = encoderFL.read();
    // values[1] = encoderFR.read();
    // count = 2;
}

void __attribute__((weak)) onControlMessage(const String& cmd) {
    // Optional: handle custom control messages from phone
}
