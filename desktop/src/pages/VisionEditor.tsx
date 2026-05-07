import { useState } from "react";
import Editor from "@monaco-editor/react";

const DEFAULT_WASM = `#include "barf.h"

// Called once when the module loads
void setup() {
    log("WASM vision module started");
}

// Called every frame with detection data
void on_frame(const char* detections_json) {
    // Parse JSON, decide movement
    // Example: move forward if anything is detected
    // move(0, 128, 0, 0);
}
`;

export default function VisionEditor() {
  const [source, setSource] = useState(DEFAULT_WASM);
  const [phoneIp, setPhoneIp] = useState("192.168.1.100");
  const [status, setStatus] = useState<string>("");
  const [output, setOutput] = useState<string>("");

  async function compileAndDeploy() {
    setStatus("Compiling...");
    setOutput("");
    try {
      const wasmBytes: number[] = await (window as any).__TAURI_INTERNALS__?.invoke
        ? await (window as any).__TAURI_INTERNALS__.invoke("compile_wasm", { source })
        : null;

      if (!wasmBytes) {
        setStatus("Tauri backend not available — run as desktop app");
        return;
      }

      setStatus("Deploying to phone...");
      const resp = await fetch(`http://${phoneIp}:8080/api/wasm`, {
        method: "POST",
        headers: { "Content-Type": "application/wasm" },
        body: new Uint8Array(wasmBytes),
      });

      if (resp.ok) {
        setStatus("Deployed! WASM running on phone.");
      } else {
        setStatus("Deploy failed: " + (await resp.text()));
      }
    } catch (e: any) {
      setStatus("Error: " + e.message);
    }
  }

  return (
    <div className="space-y-4">
      <article className="rounded-xl border border-[#22242b] bg-[linear-gradient(160deg,#131419_0%,#0f1014_100%)] p-4 text-zinc-100 shadow-[0_16px_40px_rgba(0,0,0,0.28)]">
        <div className="mb-3 text-sm font-semibold tracking-wide">Vision Script (C++ → WASM)</div>
        <Editor height="50vh" defaultLanguage="cpp" value={source} onChange={(v) => setSource(v || "")} theme="vs-dark" />
      </article>

      <div className="flex gap-3 items-center">
        <input
          type="text"
          value={phoneIp}
          onChange={(e) => setPhoneIp(e.target.value)}
          className="bg-zinc-800 border border-zinc-700 rounded px-2 py-1 text-sm w-40"
          placeholder="Phone IP"
        />
        <button onClick={compileAndDeploy} className="btn">Compile & Deploy</button>
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
