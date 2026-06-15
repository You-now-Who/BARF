import { useState, useEffect, useRef } from "react";
import Editor from "@monaco-editor/react";

const COCO_LABELS: Record<number, string> = {
  0: "person", 1: "bicycle", 2: "car", 14: "bird", 15: "cat", 16: "dog",
  32: "sports ball", 39: "bottle", 46: "banana", 47: "apple",
  49: "orange", 50: "broccoli", 56: "chair", 63: "laptop", 67: "cell phone",
  73: "book",
};

const DEFAULT_JS = `// BARF JS Vision Script
// onDetection(fn)  — called every camera frame (~30fps)
// move(dir, speed) — "forward" | "backward" | "left" | "right", speed 0–1
// rotate(dir, speed) — "left" | "right", speed 0–1
// stop()           — halt motors immediately
// sleep(ms)        — pause (blocks script thread)
// log(msg)         — prints to Android logcat

var ORANGE = 49; // COCO class index — see comment below for full list
// 0=person  32=sports ball  39=bottle  46=banana
// 47=apple  49=orange  50=broccoli  67=cell phone

onDetection(function(frame) {
  var dets = frame.yolo || [];

  for (var i = 0; i < dets.length; i++) {
    var d = dets[i];
    if (d.label === ORANGE && d.score > 0.6) {
      // Steer toward it: frame is 1280px wide, center ~640
      if (d.x < 560) {
        rotate("left", 0.35);
      } else if (d.x > 720) {
        rotate("right", 0.35);
      } else {
        move("forward", 0.5); // roughly centered — charge
      }
      return;
    }
  }

  stop(); // nothing found
});
`;

function labelName(id: number) {
  return COCO_LABELS[id] ?? `class ${id}`;
}

export default function JsEditor() {
  const [script, setScript] = useState(DEFAULT_JS);
  const [phoneIp, setPhoneIp] = useState(() => localStorage.getItem("barf_phone_ip") ?? "");
  const [running, setRunning] = useState(false);
  const [log, setLog] = useState<string[]>(["Ready."]);
  const [lastFrame, setLastFrame] = useState<any>(null);
  const wsRef = useRef<WebSocket | null>(null);
  const logRef = useRef<HTMLDivElement>(null);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

  // Persist phoneIp
  useEffect(() => {
    if (phoneIp) localStorage.setItem("barf_phone_ip", phoneIp);
  }, [phoneIp]);

  // WebSocket for live detection feed
  useEffect(() => {
    if (!phoneIp) return;
    const ws = new WebSocket(`ws://${phoneIp}:8081`);
    wsRef.current = ws;

    ws.onopen = () => addLog("WS connected — live detection feed active");
    ws.onclose = () => addLog("WS disconnected");
    ws.onerror = () => addLog("WS error — check phone IP");

    ws.onmessage = (e) => {
      try {
        const msg = JSON.parse(e.data);
        if (msg.type === "detections") {
          const parsed = typeof msg.detections === "string"
            ? JSON.parse(msg.detections)
            : msg.detections;
          setLastFrame(parsed);
        }
      } catch {}
    };

    return () => { ws.close(); wsRef.current = null; };
  }, [phoneIp]);

  // Poll /api/status to sync running state
  useEffect(() => {
    if (!phoneIp) return;
    pollRef.current = setInterval(async () => {
      try {
        const res = await fetch(`http://${phoneIp}:8080/api/status`);
        if (res.ok) {
          const j = await res.json();
          setRunning(!!j.jsRunning);
        }
      } catch {}
    }, 1500);
    return () => { if (pollRef.current) clearInterval(pollRef.current); };
  }, [phoneIp]);

  // Auto-scroll log
  useEffect(() => {
    logRef.current?.scrollTo(0, logRef.current.scrollHeight);
  }, [log]);

  function addLog(line: string) {
    const ts = new Date().toLocaleTimeString("en-US", { hour12: false });
    setLog((prev) => [...prev.slice(-199), `[${ts}] ${line}`]);
  }

  async function runScript() {
    if (!phoneIp) { addLog("Set phone IP first"); return; }
    addLog("Sending script to phone...");
    try {
      const res = await fetch(`http://${phoneIp}:8080/api/js/run`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ script }),
      });
      const j = await res.json();
      if (res.ok) {
        setRunning(true);
        addLog("Script running on phone");
      } else {
        addLog("Error: " + (j.error ?? res.status));
      }
    } catch (e: any) {
      addLog("Cannot reach phone: " + e.message);
    }
  }

  async function stopScript() {
    if (!phoneIp) return;
    try {
      await fetch(`http://${phoneIp}:8080/api/js/stop`, { method: "POST" });
      setRunning(false);
      addLog("Script stopped");
    } catch (e: any) {
      addLog("Stop failed: " + e.message);
    }
  }

  const yolo: any[] = lastFrame?.yolo ?? [];
  const atags: any[] = lastFrame?.apriltags ?? [];

  return (
    <div className="flex flex-col gap-4">
      {/* Toolbar */}
      <div className="flex items-center gap-3 flex-wrap">
        <input
          type="text"
          value={phoneIp}
          onChange={(e) => setPhoneIp(e.target.value)}
          placeholder="Phone IP (e.g. 192.168.1.42)"
          className="bg-zinc-800 border border-zinc-700 rounded px-2 py-1 text-sm w-48"
        />
        <button
          onClick={runScript}
          disabled={running}
          className="btn disabled:opacity-40 disabled:cursor-not-allowed"
        >
          {running ? "Running…" : "▶ Run"}
        </button>
        <button
          onClick={stopScript}
          disabled={!running}
          className="btn-destructive disabled:opacity-40 disabled:cursor-not-allowed"
        >
          ■ Stop
        </button>
        <span className={`text-xs font-semibold px-2 py-0.5 rounded ${running ? "bg-green-900 text-green-300" : "bg-zinc-800 text-zinc-400"}`}>
          {running ? "RUNNING" : "STOPPED"}
        </span>
      </div>

      <div className="flex gap-4" style={{ minHeight: "70vh" }}>
        {/* Monaco editor */}
        <div className="flex-1 rounded-xl border border-[#22242b] overflow-hidden">
          <Editor
            height="100%"
            defaultLanguage="javascript"
            value={script}
            onChange={(v) => setScript(v ?? "")}
            theme="vs-dark"
            options={{
              fontSize: 14,
              minimap: { enabled: false },
              scrollBeyondLastLine: false,
              wordWrap: "on",
            }}
          />
        </div>

        {/* Right panel: live detections + log */}
        <div className="flex flex-col gap-4 w-72 shrink-0">
          {/* Live detections */}
          <article className="rounded-xl border border-[#22242b] bg-[linear-gradient(160deg,#131419_0%,#0f1014_100%)] p-3 text-zinc-100 shadow-[0_16px_40px_rgba(0,0,0,0.28)]">
            <div className="text-xs font-semibold tracking-wide mb-2 text-zinc-400">LIVE DETECTIONS</div>
            {yolo.length === 0 && atags.length === 0 ? (
              <div className="text-xs text-zinc-600">No objects detected</div>
            ) : (
              <ul className="space-y-1">
                {yolo.map((d: any, i: number) => (
                  <li key={i} className="flex justify-between text-xs">
                    <span className="text-zinc-200 font-medium">{labelName(d.label)}</span>
                    <span className="text-zinc-500">{Math.round(d.score * 100)}%</span>
                  </li>
                ))}
                {atags.map((t: any, i: number) => (
                  <li key={"at" + i} className="flex justify-between text-xs">
                    <span className="text-amber-400 font-medium">AprilTag #{t.id}</span>
                    <span className="text-zinc-500">{t.family ?? ""}</span>
                  </li>
                ))}
              </ul>
            )}
          </article>

          {/* Log */}
          <article className="flex-1 rounded-xl border border-[#22242b] bg-[linear-gradient(160deg,#131419_0%,#0f1014_100%)] p-3 text-zinc-100 shadow-[0_16px_40px_rgba(0,0,0,0.28)] flex flex-col">
            <div className="flex items-center justify-between mb-2">
              <div className="text-xs font-semibold tracking-wide text-zinc-400">LOG</div>
              <button onClick={() => setLog([])} className="text-xs text-zinc-600 hover:text-zinc-400">clear</button>
            </div>
            <div
              ref={logRef}
              className="flex-1 overflow-y-auto font-mono text-xs text-zinc-400 space-y-0.5"
              style={{ maxHeight: "40vh" }}
            >
              {log.map((l, i) => <div key={i}>{l}</div>)}
            </div>
          </article>
        </div>
      </div>
    </div>
  );
}
