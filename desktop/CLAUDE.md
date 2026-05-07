# Desktop Companion App + Compile Service

## Overview

A Tauri desktop app (Rust backend, React frontend) that serves as the IDE, compiler, and deployment tool for the BARF robot. Runs on the dev's laptop (Windows/macOS/Linux). During competition, the laptop is disconnected — the phone runs standalone with the last-deployed artifacts.

## What it does

1. **C++ editor for vision scripts** — Monaco with clangd-based autocomplete against `barf.h`, compiles to WASM via Emscripten or WAMR's `wamrc`
2. **C++ editor for ESP32 firmware** — Monaco with Arduino API autocomplete, compiles via bundled `arduino-cli`
3. **One-click deploy** — pushes `.wasm` to phone over HTTP, sends `.bin` to phone which forwards to ESP32 over USB-serial
4. **Camera feed** — receives WebRTC or MJPEG stream from the phone
5. **Serial monitor** — bidirectional relay of the phone↔ESP32 serial traffic
6. **Robot controls** — gamepad-style buttons for manual drive testing

## Architecture

```
desktop/
├── src/                          # React frontend
│   ├── App.tsx
│   ├── pages/
│   │   ├── Dashboard.tsx         # Camera feed + manual controls + status
│   │   ├── VisionEditor.tsx      # C++ WASM editor (Monaco) + compile/deploy
│   │   ├── FirmwareEditor.tsx    # C++ Arduino editor (Monaco) + compile/flash
│   │   └── Settings.tsx          # Phone connection, toolchain paths, serial config
│   └── components/
├── src-tauri/                    # Rust backend
│   ├── Cargo.toml
│   ├── tauri.conf.json
│   └── src/
│       ├── main.rs               # Tauri entry, setup, menu
│       ├── phone_bridge.rs       # HTTP/WebSocket client to phone API
│       ├── compile.rs            # arduino-cli + clang/wamrc invocation
│       ├── serial_relay.rs       # WebSocket ↔ serial port relay
│       └── state.rs              # App state management (connection, builds)
└── sdk/                          # Reference headers (symlinked or copied)
    ├── barf.h                    # WASM host API for vision scripts
    └── robot_firmware.h          # Arduino template for ESP32
```

## Phone connection

The desktop app discovers and connects to the phone via:

1. **ADB over TCP** (preferred) — `adb connect <phone-ip>:5555`. Zero config on the phone side beyond enabling USB debugging once. The desktop app forwards the phone's HTTP port: `adb forward tcp:8080 tcp:8080`. Then all API calls go to `http://localhost:8080`.

2. **Direct IP** — user types the phone's IP. Phone runs a tiny HTTP server on port 8080. Works over WiFi hotspot.

3. **USB cable** — ADB over USB, same port forwarding as #1.

The desktop app tries ADB first, falls back to direct IP.

## Compile service (Rust side)

### WASM compilation

```
User clicks "Compile & Deploy" in VisionEditor.tsx
  → Tauri invoke('compile_wasm', { source: string })
    → compile.rs: clang --target=wasm32 -O3 -c barf_wasm.cpp -o barf_wasm.o
    → compile.rs: wasm-ld barf_wasm.o -o barf_wasm.wasm --no-entry --import-memory
    → phone_bridge.rs: POST /api/wasm multipart upload to phone
    → Phone: WAMR loads new module, hot-swaps the running script
```

The WASM compilation toolchain is embedded in the Tauri app:
- `clang` + `wasm-ld` from LLVM (or the WAMR SDK's `wamrc` for AOT compilation)
- These are bundled as sidecar binaries or expected on PATH

### ESP32 compilation

```
User clicks "Compile & Flash" in FirmwareEditor.tsx
  → Tauri invoke('compile_esp32', { source: string, board: string })
    → compile.rs: write source to temp sketch dir
    → compile.rs: spawn arduino-cli compile --fqbn esp32:esp32:esp32 sketch/
    → returns .bin path or compiler errors
  → User clicks "Flash"
    → compile.rs: spawn arduino-cli upload --fqbn esp32:esp32:esp32 -p <port>
    → OR: POST /api/firmware to phone, phone flashes ESP32 over USB-OTG
```

`arduino-cli` must be installed and on PATH. The desktop app settings page lets the user configure the path and installed board packages.

## Phone API (what the desktop app talks to)

The phone exposes these endpoints in dev mode:

```
POST /api/wasm                  Deploy .wasm binary (multipart file upload)
                                Response: { "loaded": true, "hash": "abc123", "size": 4096 }

POST /api/wasm/start            Start executing the loaded WASM module
POST /api/wasm/stop             Stop WASM execution

POST /api/firmware              Send .bin to phone, phone flashes ESP32 over USB-OTG
                                Body: multipart .bin file
                                Response: { "flashed": true, "size": 262144 }

WS   /api/serial                Bidirectional serial relay
                                → desktop sends: {"type":"serial_tx","data":"...."}
                                ← phone sends:  {"type":"serial_rx","data":"...."}

WS   /api/events                Streaming detection data + robot state
                                ← {"type":"detections","yolo":[...],"apriltags":[...]}
                                ← {"type":"robot_state","moving":true,"command":"forward"}

GET  /api/status                { "wasm_loaded": true, "wasm_hash": "abc123",
                                  "serial_connected": true, "camera_ok": true,
                                  "model_loaded": true, "fps": 28.5 }

POST /api/js/run                Execute a JS snippet on GraalJS (quick experimentation)
                                Body: { "script": "move('forward', 0.5); sleep(2000); stop();" }

POST /api/js/stop               Stop running JS script

GET  /api/video/offer           WebRTC signaling — SDP offer from phone
POST /api/video/answer          SDP answer from desktop
```

## State machine

```
DISCONNECTED → CONNECTING (trying ADB or IP) → CONNECTED → DEPLOYING → RUNNING
                                                     ↓              ↓
                                                  DISCONNECTED   STOPPED
```

Only one WASM module is loaded at a time. Deploying a new one stops the old one.

## Editor experience

The C++ editors (both WASM and ESP32) provide:
- **Autocomplete** via Monaco's built-in C++ language server, with `barf.h` symbols added to the workspace
- **Diagnostics** — compiler errors underlined in real-time (runs `clang -fsyntax-only` or `arduino-cli compile --only-compilation-database` on save)
- **Templates** — "New vision script" and "New firmware sketch" insert working boilerplate
- **Examples** — a dropdown of example scripts (follow ball, avoid obstacles, AprilTag follower, etc.)

## Bundled examples

The desktop app ships with example scripts that the user can load with one click:

- `examples/follow_ball.cpp` — WASM: pan/tilt toward the largest detected "sports ball"
- `examples/apriltag_nav.cpp` — WASM: drive toward AprilTag ID 0
- `examples/obstacle_avoid.cpp` — WASM: stop if any detection covers >30% of frame
- `examples/diff_drive.cpp` — ESP32: standard differential drive with encoder feedback
- `examples/mecanum_drive.cpp` — ESP32: mecanum wheel drive (FL, FR, BL, BR)
