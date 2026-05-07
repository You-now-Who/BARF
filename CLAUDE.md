# CLAUDE.md

## Project Overview

BARF (Boring Android Robotics Framework) — an Android app that turns a phone into a robot brain. Built for Unibots 2026 competition.

The architecture has two modes:
- **Competition mode**: Phone is airgapped, everything runs onboard. WASM vision script executes on the phone, serial JSON goes to ESP32 over USB-OTG.
- **Dev mode**: A desktop companion app (Tauri) provides the IDE — C++ editors for WASM and ESP32, serial monitor, one-click compile & deploy.

## What it does

1. **Runs YOLO11 object detection** on the phone camera using Tencent's ncnn inference framework with Vulkan GPU acceleration
2. **Executes WASM vision scripts** via WAMR (WebAssembly Micro Runtime) — C++ compiled to `.wasm`, pushed from desktop app
3. **Communicates with ESP32 over USB-serial** (CDC ACM) using a JSON newline-delimited protocol `{"m":[...]}` / `{"s":[...]}`
4. **Detects AprilTags** using the AprilRobotics C library for visual fiducial localization
5. **Streams video** to the desktop companion app (WebRTC or raw H.264, replacing current MJPEG)
6. **Accepts JavaScript** for quick experimentation via GraalJS (replacing Rhino)

## Architecture

```
┌──────────────────────────────────────────────────────┐
│ Desktop Companion App (dev mode only)                 │
│  ┌────────────┐ ┌────────────┐ ┌──────────────────┐ │
│  │ WASM Editor│ │ ESP32 Edit │ │ Dashboard/Camera │ │
│  │ (C++ args) │ │ (C++/Ard)  │ │ Serial Monitor   │ │
│  └────────────┘ └────────────┘ └──────────────────┘ │
│       │               │                │             │
│  clang→.wasm    arduino-cli→.bin    HTTP/WebRTC      │
│       └───────┬───────┘                │             │
│               │ deploy to phone        │             │
└───────────────┼────────────────────────┼─────────────┘
                │                        │
          WiFi / ADB               WiFi / ADB
                │                        │
                ▼                        ▼
┌───────────────────────────────────────────────────────┐
│ Phone APK (always onboard)                             │
│  ┌──────────┐ ┌──────────┐ ┌────────────────────────┐ │
│  │ ncnn     │ │ WAMR     │ │ USB-serial to ESP32    │ │
│  │ YOLO     │ │ (.wasm)  │ │ JSON protocol          │ │
│  │ AprilTag │ │ GraalJS  │ │                        │ │
│  └──────────┘ └──────────┘ └────────────────────────┘ │
│              │ USB-OTG                                 │
└──────────────┼────────────────────────────────────────┘
               ▼
     ┌─────────────────┐
     │     ESP32        │
     │  Serial JSON→PWM │
     └─────────────────┘
```

Competition mode is identical but without the desktop app connected. The phone loads the last-deployed WASM binary and runs standalone.

## Repo structure

```
ncnn-android-yolo11/
├── app/                          # Android app
│   └── src/main/
│       ├── assets/               # YOLO .param/.bin models + default .wasm
│       ├── java/com/tencent/yolo11ncnn/
│       │   ├── MainActivity.java
│       │   ├── YOLO11Ncnn.java   # JNI bridge
│       │   ├── UsbSerialManager.java       # [NEW] USB Host CDC ACM
│       │   ├── WasmRuntime.java            # [NEW] WAMR JNI wrapper
│       │   ├── PhoneApiServer.java         # [NEW] slim HTTP/WS API for desktop app
│       │   └── VideoStreamServer.java
│       └── jni/
│           ├── yolo11ncnn.cpp    # JNI entry points
│           ├── yolo11*.cpp/h     # YOLO model variants
│           ├── ndkcamera.cpp/h   # NDK Camera2
│           ├── apriltag_detector.cpp/h
│           ├── wasm_runtime.cpp  # [NEW] WAMR JNI bridge
│           └── CMakeLists.txt
├── desktop/                      # [NEW] Tauri companion app
│   ├── src-tauri/                # Rust backend
│   │   └── src/
│   │       ├── main.rs           # Tauri entry, menu, window mgmt
│   │       ├── phone_bridge.rs   # HTTP/WebSocket to phone API
│   │       ├── compile_service.rs # arduino-cli + clang invocation
│   │       └── serial_monitor.rs # Serial port relay
│   └── src/                      # React frontend (moved from react/)
│       ├── App.tsx
│       ├── pages/
│       │   ├── Dashboard.tsx     # Camera feed + robot controls
│       │   ├── WasmEditor.tsx    # C++ editor → compile → deploy WASM
│       │   └── FirmwareEditor.tsx # C++ Arduino editor → compile → flash
│       └── components/
├── sdk/                          # [NEW] Reference headers
│   ├── barf.h                    # WASM host API header (move, rotate, getDetections, etc.)
│   └── robot_firmware.h          # ESP32 Arduino sketch template
├── finetune.ipynb               # Model training + ncnn conversion
└── react/                        # [LEGACY] Old web UI — to be folded into desktop/
```

## Key files

| File | Role |
|------|------|
| `MainActivity.java` | App entry. Will be slimmed to lifecycle orchestration only. |
| `UsbSerialManager.java` | [NEW] Android USB Host API. Opens CDC ACM device, reads/writes JSON lines. |
| `WasmRuntime.java` | [NEW] Loads `.wasm` file, instantiates WAMR module, registers host functions, calls `onFrame()`. |
| `PhoneApiServer.java` | [NEW] Slim HTTP server (replaces SimpleHttpServer). Endpoints: `POST /wasm` (deploy), `POST /firmware` (forward to ESP32), `GET /video` (WebRTC signaling), `WS /serial` (serial relay). |
| `wasm_runtime.cpp` | [NEW] JNI bridge to WAMR C API. Exposes host function bindings. |
| `yolo11ncnn.cpp` | Native JNI layer. Per-frame pipeline: YOLO → AprilTag → invoke WASM `onFrame()` → serial output. |
| `barf.h` | [NEW] Reference header for WASM scripts. Declares `move()`, `rotate()`, `stop()`, `sleep()`, `getDetections()`, `log()`. |
| `robot_firmware.h` | [NEW] Reference header / template for ESP32 Arduino sketches. |
| `desktop/src-tauri/src/compile_service.rs` | [NEW] Invokes arduino-cli for ESP32 compilation, clang for WASM. |
| `desktop/src-tauri/src/phone_bridge.rs` | [NEW] Connects to phone via HTTP, deploys artifacts, relays serial. |

## Serial protocol (Phone ↔ ESP32)

JSON over newline-delimited serial at 115200 baud:

```json
{"m":[255,0,-128,0]}    ← motor speeds: FL, FR, BL, BR (-255 to 255)
{"s":[142,138,0,0]}     → sensor data: encoder counts or whatever the ESP32 sends
```

The protocol is intentionally trivial. The ESP32 side is ~20 lines of Arduino.

## Phone API (dev mode)

The phone exposes a local HTTP/WebSocket API for the desktop app:

```
POST /api/wasm            ← deploy .wasm binary (multipart)
GET  /api/status          ← phone health, loaded WASM hash, serial state
WS   /api/serial          ← bidirectional serial relay to ESP32
WS   /api/events          ← detection JSON push, robot state updates
GET  /api/video/offer     ← WebRTC SDP exchange for video stream
```

In competition mode with no desktop app, these endpoints are unused. The phone runs standalone.

## Data flow (per frame, competition mode)

```
Camera (NV21)
  → rotate/convert YUV→RGB
  → g_yolo11->detect(rgb, objects)
  → g_yolo11->draw(rgb, objects)
  → g_apriltag->detect(gray, atags)
  → build detection JSON struct
  → WAMR: call wasm_on_frame(detections_json)
    → WASM script decides motor values
    → WASM calls host_move(x, y, r, e)
      → UsbSerialManager.send("{\"m\":[...]}\n")
        → ESP32 receives over USB-serial
  → render RGB to SurfaceView (or encode for WebRTC if dev mode)
```

## Known issues / tech debt

- **God object**: MainActivity.java does everything — being decomposed in current refactor
- **Static globals in native**: g_yolo11, g_camera, g_apriltag are raw owning pointers
- **Hardcoded values**: Ports, default IPs, camera preview size
- **Video streaming double-capture**: Frame is rendered AND separately PixelCopy'd for streaming
- **Error handling**: Catch-log-continue throughout — silent failures. Needs structured error reporting.
- **No tests** at any layer
