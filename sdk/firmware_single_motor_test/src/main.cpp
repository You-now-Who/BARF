#include <Arduino.h>
#include <ArduinoJson.h>

/*
  Motor Control Logic (DRV8833):
  IN1 = HIGH, IN2 = LOW  -> Forward
  IN1 = LOW,  IN2 = HIGH -> Reverse
  IN1 = LOW,  IN2 = LOW  -> Coast/Stop

  Pin mapping:
  m[0] -> Driver 1: GPIO32, GPIO33
  m[1] -> Driver 2: GPIO25, GPIO26
  m[2] -> Driver 3: GPIO27, GPIO14 (LIFT)
  m[3] -> Driver 4: GPIO16, GPIO17
  m[4] -> Driver 5: GPIO18, GPIO19
  m[5] -> Driver 6: GPIO21, GPIO22 (LIFT)
*/

const int numMotors = 6;
const int in1Pins[numMotors] = {32, 25, 27, 16, 18, 21};
const int in2Pins[numMotors] = {33, 26, 14, 17, 19, 22};

static String g_serialBuffer;

static void setMotor(int index, int16_t speed) {
  int duty = constrain(abs(speed), 0, 255);

  if (speed > 0) {
    analogWrite(in1Pins[index], duty);
    analogWrite(in2Pins[index], 0);
  } else if (speed < 0) {
    analogWrite(in1Pins[index], 0);
    analogWrite(in2Pins[index], duty);
  } else {
    analogWrite(in1Pins[index], 0);
    analogWrite(in2Pins[index], 0);
  }

  Serial.printf("{\"l\":\"motor %d speed=%d\"}\n", index, speed);
}

static void processLine(const String& line) {
  Serial.print("{\"l\":\"RX: ");
  Serial.print(line);
  Serial.println("\"}");

  if (line.length() == 0 || line.charAt(0) != '{') return;

  StaticJsonDocument<256> doc;
  DeserializationError err = deserializeJson(doc, line);
  if (err) {
    Serial.printf("{\"l\":\"JSON error: %s\"}\n", err.c_str());
    return;
  }

  if (doc.containsKey("m")) {
    JsonArray arr = doc["m"].as<JsonArray>();
    for (int i = 0; i < numMotors && i < (int)arr.size(); i++) {
      int16_t speed = constrain((int)arr[i], -255, 255);
      setMotor(i, speed);
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

  for (int i = 0; i < numMotors; i++) {
    pinMode(in1Pins[i], OUTPUT);
    pinMode(in2Pins[i], OUTPUT);
    analogWrite(in1Pins[i], 0);
    analogWrite(in2Pins[i], 0);
  }

  Serial.println("{\"c\":\"ready\"}");
}

void loop() {
  readSerial();
}