// main.cpp — single-motor test firmware.
//
// Drives ONE motor channel (GPIO 32/33, DRV8833 two-pin PWM) from
// {"m":[...]} commands. Only m[0] is read; everything else is ignored.
// Logs every step over Serial so you can tell exactly where things break:
//   - "RX line: ..."      -> a line of serial data arrived at all
//   - "parsed m[0] = ..."  -> it was valid JSON with an "m" array
//   - "PWM a=.. b=.."      -> the GPIO/PWM write actually happened
//
// Wiring: GPIO 32 -> DRV8833 AIN1, GPIO 33 -> DRV8833 AIN2 (or however your
// breakout labels its first channel's two inputs).
//
// Send e.g. {"m":[150]} or {"m":[100,100,100,100]} (only index 0 is used)
// over the serial monitor at 115200 baud, newline-terminated.

#include <Arduino.h>
#include <ArduinoJson.h>

static const uint8_t PIN_A = 32;
static const uint8_t PIN_B = 33;

static String g_serialBuffer;

static void setMotor(int16_t speed) {
    int duty = constrain((int)abs(speed), 0, 255);
    int a = speed >= 0 ? duty : 0;
    int b = speed >= 0 ? 0 : duty;
    analogWrite(PIN_A, a);
    analogWrite(PIN_B, b);
    Serial.printf("{\"l\":\"PWM a=%d b=%d (speed=%d)\"}\n", a, b, speed);
}

static void processLine(const String& line) {
    Serial.print("{\"l\":\"RX line: ");
    Serial.print(line);
    Serial.println("\"}");

    if (line.length() == 0 || line.charAt(0) != '{') return;

    StaticJsonDocument<256> doc;
    DeserializationError err = deserializeJson(doc, line);
    if (err) {
        Serial.print("{\"l\":\"JSON error: ");
        Serial.print(err.c_str());
        Serial.println("\"}");
        return;
    }

    if (doc.containsKey("m")) {
        JsonArray arr = doc["m"].as<JsonArray>();
        if (arr.size() == 0) {
            Serial.println("{\"l\":\"m array empty\"}");
            return;
        }
        int16_t speed = constrain((int)arr[0], -255, 255);
        Serial.print("{\"l\":\"parsed m[0] = ");
        Serial.print(speed);
        Serial.println("\"}");
        setMotor(speed);
    } else if (doc.containsKey("c")) {
        const char* cmd = doc["c"];
        if (strcmp(cmd, "ping") == 0) {
            Serial.println("{\"c\":\"pong\"}");
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

void setup() {
    Serial.begin(115200);
    while (!Serial) delay(10);

    pinMode(PIN_A, OUTPUT);
    pinMode(PIN_B, OUTPUT);
    analogWrite(PIN_A, 0);
    analogWrite(PIN_B, 0);

    Serial.println("{\"c\":\"ready\"}");
    Serial.println("{\"l\":\"single-motor test firmware — GPIO 32/33\"}");
}

void loop() {
    readSerial();
}
