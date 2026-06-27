import { useState } from "react";
import Editor from "@monaco-editor/react";

const DEFAULT_FIRMWARE = `#include <Arduino.h>
#include <ArduinoJson.h>

// BARF ESP32 firmware — 6 motors on DRV8833 dual H-bridges.
// Each motor uses TWO pins (A/B): forward = PWM on A, B low; reverse = swap.
// Wire format from the phone: {"m":[FL,BL,LIFT1,FR,BR,LIFT2]}, each -1023..1023.

const int NUM_MOTORS = 6;
const int pinA[NUM_MOTORS] = {32, 25, 27, 16, 18, 21};
const int pinB[NUM_MOTORS] = {33, 26, 14, 17, 19, 22};

const int PWM_FREQ_HZ  = 20000;   // 20 kHz carrier (above audible)
const int PWM_RES_BITS = 10;      // 10-bit duty resolution
const int PWM_MAX      = 1023;    // (1 << PWM_RES_BITS) - 1

const unsigned long MOTOR_TIMEOUT_MS = 300;  // stop if no command arrives
unsigned long lastCmdMs = 0;
String lineBuf;

void setMotor(int i, int speed) {
  int chA = i * 2, chB = i * 2 + 1;
  int duty = constrain(abs(speed), 0, PWM_MAX);
  ledcWrite(chA, speed > 0 ? duty : 0);
  ledcWrite(chB, speed < 0 ? duty : 0);
}

void setup() {
  Serial.begin(115200);
  for (int i = 0; i < NUM_MOTORS; i++) {
    int chA = i * 2, chB = i * 2 + 1;
    ledcSetup(chA, PWM_FREQ_HZ, PWM_RES_BITS);   // 20 kHz, 10-bit (0..1023)
    ledcSetup(chB, PWM_FREQ_HZ, PWM_RES_BITS);
    ledcAttachPin(pinA[i], chA);
    ledcAttachPin(pinB[i], chB);
  }
  Serial.println("{\\"c\\":\\"ready\\"}");
}

void loop() {
  while (Serial.available()) {
    char c = Serial.read();
    if (c == '\\n') {
      StaticJsonDocument<256> doc;
      if (!deserializeJson(doc, lineBuf) && doc.containsKey("m")) {
        JsonArray m = doc["m"].as<JsonArray>();
        for (int i = 0; i < NUM_MOTORS && i < (int)m.size(); i++)
          setMotor(i, constrain((int)m[i], -PWM_MAX, PWM_MAX));
        lastCmdMs = millis();
      }
      lineBuf = "";
    } else if (lineBuf.length() < 256) {
      lineBuf += c;
    }
  }

  // Safety: cut motors if the phone stops sending commands.
  if (lastCmdMs && millis() - lastCmdMs > MOTOR_TIMEOUT_MS) {
    lastCmdMs = 0;
    for (int i = 0; i < NUM_MOTORS; i++) setMotor(i, 0);
  }
}
`;

export default function FirmwareEditor() {
  const [source, setSource] = useState(DEFAULT_FIRMWARE);
  const [status, setStatus] = useState<string>("");
  const [output, setOutput] = useState<string>("");

  async function compile() {
    setStatus("Compiling...");
    setOutput("");
    try {
      const result: string = await (window as any).__TAURI_INTERNALS__?.invoke
        ? await (window as any).__TAURI_INTERNALS__.invoke("compile_esp32", { source })
        : null;

      if (!result) {
        setStatus("Tauri backend not available — run as desktop app");
        return;
      }
      setStatus("Compiled! Binary at: " + result);
    } catch (e: any) {
      setStatus("Error: " + e.message);
    }
  }

  return (
    <div className="space-y-4">
      <article className="rounded-xl border border-[#22242b] bg-[linear-gradient(160deg,#131419_0%,#0f1014_100%)] p-4 text-zinc-100 shadow-[0_16px_40px_rgba(0,0,0,0.28)]">
        <div className="mb-3 text-sm font-semibold tracking-wide">Firmware (Arduino C++ → ESP32)</div>
        <Editor height="50vh" defaultLanguage="cpp" value={source} onChange={(v) => setSource(v || "")} theme="vs-dark" />
      </article>

      <div className="flex gap-3 items-center">
        <button onClick={compile} className="btn">Compile</button>
        <span className="text-sm text-zinc-400">{status}</span>
      </div>

      {output && (
        <article className="rounded-xl border border-[#22242b] bg-[linear-gradient(160deg,#131419_0%,#0f1014_100%)] p-4 text-zinc-100 shadow-[0_16px_40px_rgba(0,0,0,0.28)]">
          <div className="mb-3 text-sm font-semibold tracking-wide">Output</div>
          <pre className="text-sm text-zinc-400 max-h-40 overflow-y-auto">{output}</pre>
        </article>
      )}
    </div>
  );
}
