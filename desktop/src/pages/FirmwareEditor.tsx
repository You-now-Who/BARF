import { useState } from "react";
import Editor from "@monaco-editor/react";

const DEFAULT_FIRMWARE = `#include "robot_firmware.h"

// Motor pins for 4-wheel drive
const int MOTOR_FL_PWM = 32;
const int MOTOR_FR_PWM = 33;
const int MOTOR_BL_PWM = 25;
const int MOTOR_BR_PWM = 26;

void setup() {
    Serial.begin(115200);
    ledcSetup(0, 5000, 8);
    ledcSetup(1, 5000, 8);
    ledcSetup(2, 5000, 8);
    ledcSetup(3, 5000, 8);
    ledcAttachPin(MOTOR_FL_PWM, 0);
    ledcAttachPin(MOTOR_FR_PWM, 1);
    ledcAttachPin(MOTOR_BL_PWM, 2);
    ledcAttachPin(MOTOR_BR_PWM, 3);
}

void loop() {
    // Handle incoming JSON motor commands
    if (Serial.available()) {
        String line = Serial.readStringUntil('\n');
        // Parse {"m":[fl,fr,bl,br]} and set PWMs
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
