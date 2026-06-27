#include <Arduino.h>
#include <ArduinoJson.h>

/*
  Motor Control Logic (DRV8833):
  IN1 = HIGH, IN2 = LOW  -> Forward
  IN1 = LOW,  IN2 = HIGH -> Reverse
  IN1 = LOW,  IN2 = LOW  -> Coast/Stop

  Pin mapping:
  m[0] -> Driver 1: GPIO32, GPIO33  (FL)
  m[1] -> Driver 2: GPIO25, GPIO26  (BL)
  m[2] -> Driver 3: GPIO27, GPIO14  (LIFT1)
  m[3] -> Driver 4: GPIO16, GPIO17  (FR)
  m[4] -> Driver 5: GPIO18, GPIO19  (BR)
  m[5] -> Driver 6: GPIO21, GPIO22  (LIFT2)
*/

const int numMotors = 6;
const int in1Pins[numMotors] = {32, 25, 27, 16, 18, 21};
const int in2Pins[numMotors] = {33, 26, 14, 17, 19, 22};

// PWM (LEDC): 20 kHz carrier (above audible), 10-bit duty (0..1023).
// PWM_MAX must match SerialProtocol.MAX_PWM on the Android side.
static const int     PWM_FREQ_HZ  = 20000;
static const uint8_t PWM_RES_BITS = 10;
static const int     PWM_MAX      = (1 << PWM_RES_BITS) - 1;  // 1023

static String g_serialBuffer;
static unsigned long g_lastCmdMs = 0;
static const unsigned long MOTOR_TIMEOUT_MS = 300;

static void setMotor(int index, int16_t speed) {
  int duty = constrain(abs(speed), 0, PWM_MAX);
  if (speed >= 0) {
    analogWrite(in1Pins[index], duty);
    analogWrite(in2Pins[index], 0);
  } else {
    analogWrite(in1Pins[index], 0);
    analogWrite(in2Pins[index], duty);
  }
}

static void processLine(const String& line) {
  if (line.length() == 0 || line.charAt(0) != '{') return;

  StaticJsonDocument<256> doc;
  DeserializationError err = deserializeJson(doc, line);
  if (err) return;

  if (doc.containsKey("m")) {
    g_lastCmdMs = millis();
    JsonArray arr = doc["m"].as<JsonArray>();
    for (int i = 0; i < numMotors && i < (int)arr.size(); i++) {
      setMotor(i, constrain((int)arr[i], -PWM_MAX, PWM_MAX));
    }
  } else if (doc.containsKey("c")) {
    const char* cmd = doc["c"];
    if (strcmp(cmd, "ping") == 0) {
      Serial.println("{\"c\":\"pong\"}");
    } else if (strcmp(cmd, "stop") == 0) {
      for (int i = 0; i < numMotors; i++) setMotor(i, 0);
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

  analogWriteResolution(PWM_RES_BITS);  // 10-bit duty: 0..1023
  analogWriteFrequency(PWM_FREQ_HZ);    // 20 kHz carrier (above audible)
  for (int i = 0; i < numMotors; i++) {
    analogWrite(in1Pins[i], 0);         // first write attaches the pin + idles low
    analogWrite(in2Pins[i], 0);
  }

  Serial.println("{\"c\":\"ready\"}");
}

void loop() {
  readSerial();
  if (g_lastCmdMs > 0 && millis() - g_lastCmdMs > MOTOR_TIMEOUT_MS) {
    g_lastCmdMs = 0;
    for (int i = 0; i < numMotors; i++) setMotor(i, 0);
  }
}
