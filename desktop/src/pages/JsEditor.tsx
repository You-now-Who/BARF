import { useState, useEffect, useLayoutEffect, useRef, useMemo } from "react";
import Editor from "@monaco-editor/react";

// Fine-tuned model (2-class): 0=ping_pong_ball, 1=metal_ball
// Falls back to COCO names for old/COCO models
const COCO_LABELS: Record<number, string> = {
  0: "ping_pong_ball", 1: "metal_ball",
  14: "bird", 15: "cat", 16: "dog",
  32: "sports ball", 39: "bottle", 46: "banana", 47: "apple",
  49: "orange", 50: "broccoli", 56: "chair", 63: "laptop", 67: "cell phone",
  73: "book",
};

const DEFAULT_JS = `// BARF — Ball chaser with lift
// States: SEARCH → STEER → CENTRING → CHARGE
// Lift is fully async — navigation never pauses waiting for it.

var BALL         = 0;    // fine-tuned: ping_pong_ball
var FRAME_W      = 640;
var DEAD_ZONE    = 30;
var CAM_OFFSET_X = 0;
var CAM_ANGLE    = 0;

var SEARCH_SPEED = 360;  // PWM 0-1023
var STEER_MIN    = 160;
var STEER_MAX    = 800;
var DRIVE_FAR    = 720;
var DRIVE_CLOSE  = 360;
var MIN_W        = 30;
var CLOSE_W      = 150;
var STOP_W       = 300;
var COAST_MS     = 500;

var LOCK_FRAMES  = 3;
var CHARGE_SPEED = 920;
var CHARGE_MS    = 800;

autoshutdown();

// ── Lift position tracker (0=ground, 1=half, 2=full) ──────────────────────
var liftPosition = 0;

function liftSetHalf() {
  if (liftPosition == 2) { liftMoveDown(480, 350); }
  else if (liftPosition == 0) { liftMoveUp(480, 500); }
  liftPosition = 1;
}

function liftSetFull() {
  if (liftPosition == 1) { liftMoveDown(480, 500); }
  else if (liftPosition == 0) { liftMoveUp(480, 1000); }
  liftPosition = 2;
}

function liftSetGround() {
  if (liftPosition == 2) { liftMoveDown(480, 700); }
  else if (liftPosition == 1) { liftMoveDown(480, 350); }
  liftPosition = 0;
}

var _wantedLift = -1;
function goLift(pos) {
  if (_wantedLift === pos) return;
  _wantedLift = pos;
  if (pos === 0) liftSetGround();
  else if (pos === 1) liftSetHalf();
  else if (pos === 2) liftSetFull();
}

// ── Navigation state ──────────────────────────────────────────────────────
var lastSeenMs    = 0;
var centredFrames = 0;
var charging      = false;
var chargeEnd     = 0;

onDetection(function(frame) {
  var now = new Date().getTime();

  // CHARGE: drive blind, lift at ground
  if (charging) {
    if (now < chargeEnd) {
      goLift(0);
      move("forward", CHARGE_SPEED);
      return;
    }
    // Charge done — immediately back to search, lift rises in background
    charging = false;
    centredFrames = 0;
    goLift(1);
  }

  var dets = frame.yolo || [];
  var best = null;
  for (var i = 0; i < dets.length; i++) {
    var d = dets[i];
    if (d.label === BALL && d.score > 0.3) {
      if (best === null || d.score > best.score) best = d;
    }
  }

  // SEARCH: no ball — lift half, rotate
  if (!best) {
    centredFrames = 0;
    goLift(1);
    if (now - lastSeenMs < COAST_MS) {
      move("forward", DRIVE_CLOSE);
    } else {
      rotate("right", SEARCH_SPEED);
    }
    return;
  }

  lastSeenMs = now;
  var aimX = FRAME_W / 2 + CAM_OFFSET_X;
  var cx   = best.x + best.w / 2;
  var dx   = cx - aimX;

  if (Math.abs(dx) > DEAD_ZONE) {
    // STEER: lift half while steering
    centredFrames = 0;
    goLift(1);
    var steer = Math.round(Math.min(STEER_MAX, Math.max(STEER_MIN, Math.abs(dx) / (FRAME_W / 2) * STEER_MAX)));
    rotate(dx < 0 ? "left" : "right", steer);
  } else {
    centredFrames++;
    if (centredFrames >= LOCK_FRAMES) {
      // LOCKED: drop lift, charge
      charging  = true;
      chargeEnd = now + CHARGE_MS;
      centredFrames = 0;
      goLift(0);
      move("forward", CHARGE_SPEED);
    } else {
      // CENTRING: lift half, creep forward
      goLift(1);
      if (best.w < MIN_W || best.w > STOP_W) {
        stop();
      } else if (best.w > CLOSE_W) {
        move("forward", DRIVE_CLOSE);
      } else {
        move("forward", DRIVE_FAR);
      }
    }
  }
});
`;

function labelName(id: number) {
  return COCO_LABELS[id] ?? `class ${id}`;
}

export default function JsEditor() {
  const [script, setScript] = useState(() => localStorage.getItem("barf_js_script") ?? DEFAULT_JS);
  const [phoneIp, setPhoneIp] = useState(() => localStorage.getItem("barf_phone_ip") ?? "");
  const [running, setRunning] = useState(false);
  const [log, setLog] = useState<string[]>(["Ready."]);
  const [lastFrame, setLastFrame] = useState<any>(null);
  const wsRef = useRef<WebSocket | null>(null);
  const logRef = useRef<HTMLDivElement>(null);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);

  const camOffset = useMemo(() => {
    const m = script.match(/CAM_OFFSET_X\s*=\s*(-?\d+)/);
    return m ? parseInt(m[1]) : 0;
  }, [script]);

  const deadZone = useMemo(() => {
    const m = script.match(/DEAD_ZONE\s*=\s*(-?\d+)/);
    return m ? parseInt(m[1]) : 30;
  }, [script]);

  const camAngle = useMemo(() => {
    const m = script.match(/CAM_ANGLE\s*=\s*(-?[\d.]+)/);
    return m ? parseFloat(m[1]) : 0;
  }, [script]);

  // Persist phoneIp and script
  useEffect(() => {
    if (phoneIp) localStorage.setItem("barf_phone_ip", phoneIp);
  }, [phoneIp]);

  useEffect(() => {
    localStorage.setItem("barf_js_script", script);
  }, [script]);

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
        } else if (msg.type === "log" && msg.channel === "js") {
          addLog(msg.line);
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

  // Draw aim overlay on canvas
  useLayoutEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext("2d");
    if (!ctx) return;

    const CW = canvas.width;
    const CH = canvas.height;
    const FW = 640, FH = 480;
    const sx = CW / FW, sy = CH / FH;

    ctx.clearRect(0, 0, CW, CH);
    ctx.fillStyle = "#0a0a0f";
    ctx.fillRect(0, 0, CW, CH);

    const aimX = (FW / 2 + camOffset) * sx;
    const dz = deadZone * sx;
    const rad = camAngle * Math.PI / 180;
    // Half-diagonal guarantees the rotated line always reaches canvas edges
    const halfLen = Math.sqrt(CW * CW + CH * CH) / 2;
    const ldx = Math.sin(rad) * halfLen;
    const ldy = Math.cos(rad) * halfLen;
    const cy = CH / 2;

    // Dead zone band — negate rad because canvas rotate() maps local y to (-sin,cos)
    // but the line goes in direction (+sin,cos), so they'd mirror without the negation.
    ctx.fillStyle = "rgba(34,197,94,0.07)";
    ctx.save();
    ctx.translate(aimX, cy);
    ctx.rotate(-rad);
    ctx.fillRect(-dz, -halfLen, dz * 2, halfLen * 2);
    ctx.restore();

    // Detection boxes
    const yolo: any[] = lastFrame?.yolo ?? [];
    for (const d of yolo) {
      const color = d.label === 0 ? "#f97316" : "#a78bfa";
      ctx.strokeStyle = color;
      ctx.lineWidth = 2;
      ctx.strokeRect(d.x * sx, d.y * sy, d.w * sx, d.h * sy);
      // Ball center dot
      ctx.fillStyle = color;
      ctx.beginPath();
      ctx.arc((d.x + d.w / 2) * sx, (d.y + d.h / 2) * sy, 4, 0, Math.PI * 2);
      ctx.fill();
    }

    // True frame centre (white, faint)
    ctx.strokeStyle = "rgba(255,255,255,0.18)";
    ctx.lineWidth = 1;
    ctx.setLineDash([3, 6]);
    ctx.beginPath(); ctx.moveTo(CW / 2, 0); ctx.lineTo(CW / 2, CH); ctx.stroke();
    ctx.setLineDash([]);

    // Aim line (green, solid, rotated)
    ctx.strokeStyle = "#22c55e";
    ctx.lineWidth = 2;
    ctx.beginPath();
    ctx.moveTo(aimX - ldx, cy - ldy);
    ctx.lineTo(aimX + ldx, cy + ldy);
    ctx.stroke();

    // Label
    ctx.fillStyle = "#22c55e";
    ctx.font = "bold 10px monospace";
    ctx.fillText(
      `aim ${camOffset >= 0 ? "+" : ""}${camOffset}px  ${camAngle >= 0 ? "+" : ""}${camAngle}°`,
      aimX + 6, 14,
    );
  }, [lastFrame, camOffset, deadZone, camAngle]);

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

        {/* Right panel: aim overlay + live detections + log */}
        <div className="flex flex-col gap-4 w-72 shrink-0">
          {/* Aim overlay canvas */}
          <article className="rounded-xl border border-[#22242b] overflow-hidden bg-[#0a0a0f]">
            <div className="text-xs font-semibold tracking-wide px-3 pt-2 pb-1 text-zinc-400">
              AIM OVERLAY
              <span className="ml-2 text-green-400">── aim</span>
              <span className="ml-2 text-zinc-500">── centre</span>
              <span className="ml-2 text-zinc-600">(CAM_OFFSET_X / CAM_ANGLE)</span>
            </div>
            <canvas
              ref={canvasRef}
              width={288}
              height={216}
              className="w-full block"
            />
          </article>

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
