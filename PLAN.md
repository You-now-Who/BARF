# PLAN.md — BARF Restructure & Rebuild

## Audit findings (2026-05-07)

| Problem | Detail |
|---------|--------|
| `ncnn-android-yolo11/` | Empty legacy dir on disk, not tracked. Delete. |
| `yolo26n.pt` | 5.5MB model file tracked in git root. Move to `tools/models/`. |
| `app/src/main/assets/ROBOT_BACKEND_README.md` | References `robot/` subpackage that doesn't exist. Delete. |
| `app/src/main/assets/web/` | Built React output tracked in git. Should be build artifact, generate during build. |
| `.gitignore` | Duplicate entries, build artifact rules, missing rules for new paths. |
| Package `com.tencent.yolo11ncnn` | Wrong namespace entirely. Rename to `com.barf`. |
| 6 Java files in flat package | No separation of concerns. Split into `camera/`, `vision/`, `serial/`, `runtime/`, `server/`, `robot/`. |
| 5 native globals | Raw owning pointers in `yolo11ncnn.cpp`. Wrap in context struct. |
| `react/` is standalone | No integration with Android Gradle build. Move into monorepo structure. |
| No SDK reference files | The `sdk/` dir we created is untracked. Need to integrate. |

---

## ✅ PHASE 0 — Repo Cleanup & Git Hygiene (done)

### Step 0.1 — Clean up .gitignore

**What:** Rewrite `.gitignore` with proper rules. Remove duplicates. Add rules for new directory structure.

**Files touched:**
- `.gitignore` (rewrite)

**New `.gitignore` content:**
```gitignore
# Gradle
.gradle/
build/
*/build/
!build.gradle

# Android Studio
.idea/caches/
.idea/deploymentTargetSelector.xml
.idea/deviceManager.xml
.idea/gradle.xml
.idea/studiobot.xml
.idea/runConfigurations.xml
*.iml
local.properties
app/.cxx/

# Dependencies
node_modules/
venv/

# Build output
app/src/main/assets/web/
desktop/dist/

# IDE
.vscode/
*.swp
*.swo

# OS
.DS_Store
Thumbs.db

# Claude
.claude/settings.local.json

# Python
__pycache__/
*.pyc
.venv/
```

**Validation:**
- Run `git status` — should NOT show `.gradle/`, `build/`, `.idea/caches/`, `venv/`, `node_modules/` as untracked
- Run `git ls-files --others --exclude-standard` — should only show intentional new files

---

### Step 0.2 — Remove cruft from disk

**What:** Delete files and directories that shouldn't exist.

**Commands:**
```bash
rm -rf ncnn-android-yolo11/          # empty legacy dir
rm -f app/src/main/assets/ROBOT_BACKEND_README.md  # outdated, inaccurate doc
```

**Remove from git tracking:**
```bash
git rm app/src/main/assets/ROBOT_BACKEND_README.md   # if tracked
git rm -r app/src/main/assets/web/                    # stop tracking build output
git rm .idea/caches/deviceStreaming.xml               # stop tracking IDE cache
```

**Validation:**
- `ls ncnn-android-yolo11/` → "No such file or directory"
- `ls app/src/main/assets/ROBOT_BACKEND_README.md` → file not found
- `git status` shows the removals staged

---

### Step 0.3 — Handle large model files

**What:** Move `yolo26n.pt` out of root. Optionally set up Git LFS for model files.

**Commands:**
```bash
mkdir -p tools/models
git mv yolo26n.pt tools/models/yolo26n.pt
```

**Add to `.gitattributes`:** (create if not exists)
```
*.pt filter=lfs diff=lfs merge=lfs -text
*.bin filter=lfs diff=lfs merge=lfs -text
*.param filter=lfs diff=lfs merge=lfs -text
```

**Validation:**
- `ls yolo26n.pt` → not found in root
- `ls tools/models/yolo26n.pt` → exists
- `git status` shows the rename

---

### Step 0.4 — Commit the cleanup

**What:** Single atomic commit for all cleanup operations.

```bash
git add -A
git commit -m "chore: repo cleanup — remove cruft, fix gitignore, organize models"
```

**Validation:**
- `git status` is clean
- `git log --oneline -1` shows the commit

---

## PHASE 1 — Directory Restructure (Monorepo)

### ✅ Step 1.1 — Move and rename directories (app→android, react→desktop)

**Target structure:**
```
ncnn-android-yolo11/
├── android/                  ← was app/
│   ├── build.gradle
│   └── src/main/
│       ├── assets/           ← model files only (no web build output)
│       ├── java/com/barf/    ← was com/tencent/yolo11ncnn/
│       └── jni/
├── desktop/                  ← was react/, now Tauri project
│   ├── src/                  ← React frontend
│   └── src-tauri/            ← Rust backend (new)
├── sdk/                      ← reference headers + examples (new)
│   ├── barf.h
│   ├── robot_firmware.h
│   ├── robot_firmware_template.ino
│   └── examples/
├── tools/                    ← Python scripts, notebooks, models
│   ├── finetune.ipynb
│   └── models/
├── README.md
├── CLAUDE.md
├── PLAN.md
├── settings.gradle
├── build.gradle
└── gradle/
```

**Commands:**
```bash
# Rename app/ → android/
git mv app android

# Move react/ into desktop/src/  
mkdir -p desktop
git mv react/src desktop/src
git mv react/public desktop/public
git mv react/index.html desktop/
git mv react/package.json desktop/
git mv react/package-lock.json desktop/
git mv react/tsconfig.json desktop/
git mv react/tsconfig.app.json desktop/
git mv react/tsconfig.node.json desktop/
git mv react/vite.config.ts desktop/
git mv react/eslint.config.js desktop/
git mv react/components.json desktop/
git mv react/README.md desktop/
# react/.gitignore gets absorbed into root .gitignore
rm react/.gitignore

# Move finetune.ipynb into tools/
git mv finetune.ipynb tools/

# The sdk/ files we already created — they'll show as new untracked
```

**Validation:**
- `ls android/` → contains the Android app
- `ls desktop/` → contains the React/desktop app files
- `ls tools/` → contains finetune.ipynb
- `ls sdk/` → contains barf.h, robot_firmware.h, template, examples/
- `ls app/` → "No such file or directory"
- `ls react/` → "No such file or directory"

---

### ✅ Step 1.2 — Rename Java package

**What:** Rename `com.tencent.yolo11ncnn` → `com.barf` across all Java files, AndroidManifest.xml, build.gradle, and native JNI function signatures.

**Files touched:**
- `android/src/main/java/com/tencent/yolo11ncnn/*.java` → move to `android/src/main/java/com/barf/*.java`
- `android/build.gradle` → update `namespace` and `applicationId`
- `android/src/main/AndroidManifest.xml` → update package references
- `android/src/main/jni/yolo11ncnn.cpp` → update all JNI function name strings
- `android/src/main/res/` → update any layout references (if any)

**Detailed steps:**

1. Create new package directory:
```bash
mkdir -p android/src/main/java/com/barf
```

2. Move Java files and update package declarations:
```bash
# Move files
for f in android/src/main/java/com/tencent/yolo11ncnn/*.java; do
    git mv "$f" "android/src/main/java/com/barf/$(basename $f)"
done
```

3. Edit each Java file — change `package com.tencent.yolo11ncnn;` → `package com.barf;`

4. Edit `android/build.gradle`:
```groovy
namespace 'com.barf'
applicationId "com.barf"
archivesBaseName = "barf"
```

5. Edit `yolo11ncnn.cpp` — update all JNI function names:
   - `Java_com_tencent_yolo11ncnn_YOLO11Ncnn_*` → `Java_com_barf_YoloBridge_*`
   - Also update the class name string passed to `FindClass` if any

6. Delete old empty package directory:
```bash
rmdir android/src/main/java/com/tencent/yolo11ncnn/
rmdir android/src/main/java/com/tencent/
```

**Validation:**
- `grep -r "tencent" android/src/main/java/` → no matches
- `grep -r "yolo11ncnn" android/src/main/java/` → no matches (or only in JNI bridge class name)
- `grep "com.barf" android/build.gradle` → finds the new namespace
- `grep "com.barf" android/src/main/AndroidManifest.xml` → correct package
- `grep "Java_com_barf" android/src/main/jni/yolo11ncnn.cpp` → all JNI functions renamed

---

### ✅ Step 1.3 — Fix Gradle paths

**What:** Update all Gradle references from `app/` to `android/`.

**Files touched:**
- `settings.gradle` → change `include ':app'` to `include ':android'`
- `build.gradle` (root) → any module references
- `android/build.gradle` → CMake path updated if needed (should still be `src/main/jni/CMakeLists.txt` relative to module)

**Validation:**
- Run `./gradlew projects` from root — shows `:android` not `:app`
- Run `./gradlew :android:assembleDebug` — compiles successfully (or fails with expected errors from remaining refactors)

---

### ✅ Step 1.4 — Rename YOLO11Ncnn.java → YoloBridge.java

**What:** Rename the JNI bridge class to reflect its new role.

```bash
git mv android/src/main/java/com/barf/YOLO11Ncnn.java android/src/main/java/com/barf/YoloBridge.java
```

Edit the file:
- Class declaration: `public class YoloBridge`
- Native method `registerActivity` stays
- Update `System.loadLibrary("yolo11ncnn")` → keep the .so name the same for now

Update references in MainActivity.java:
- `YOLO11Ncnn yolo11ncnn = new YOLO11Ncnn()` → `YoloBridge yolo = new YoloBridge()`
- All `yolo11ncnn.` → `yolo.`

Update native JNI function names in `yolo11ncnn.cpp`:
- `Java_com_barf_YOLO11Ncnn_*` → `Java_com_barf_YoloBridge_*`

**Validation:**
- `grep "YOLO11Ncnn" android/src/main/java/` → no matches (or only in comments)
- `grep "YOLO11Ncnn" android/src/main/jni/yolo11ncnn.cpp` → no matches in JNI function names
- `grep "YoloBridge" android/src/main/java/com/barf/*.java` → matches in MainActivity and YoloBridge

---

### ✅ Step 1.5 — Commit the restructure

```bash
git add -A
git commit -m "refactor: monorepo restructure — rename app→android, package→com.barf, react→desktop, add sdk/"
```

**Validation:**
- `git status` is clean
- Directory tree matches the target structure above
- Android build compiles: `./gradlew :android:assembleDebug`

---

## PHASE 2 — Android Java Refactor (Split God Object)

### Step 2.1 — Create subpackage structure

**What:** Create subpackages under `com.barf` for separation of concerns.

```bash
mkdir -p android/src/main/java/com/barf/camera
mkdir -p android/src/main/java/com/barf/vision
mkdir -p android/src/main/java/com/barf/serial
mkdir -p android/src/main/java/com/barf/runtime
mkdir -p android/src/main/java/com/barf/server
mkdir -p android/src/main/java/com/barf/robot
```

**Validation:**
- Directories exist
- They are empty (no files yet)

---

### Step 2.2 — Extract CameraManager

**What:** Pull camera open/close/switch/facing logic out of MainActivity.

**New file:** `android/src/main/java/com/barf/camera/CameraManager.java`

```java
package com.barf.camera;

import com.barf.vision.YoloBridge;

public class CameraManager {
    private final YoloBridge yolo;
    private int facing = 1; // 0=back, 1=front
    
    public CameraManager(YoloBridge yolo) { this.yolo = yolo; }
    
    public void open() { yolo.openCamera(facing); }
    public void close() { yolo.closeCamera(); }
    public void switchCamera() {
        close();
        facing = 1 - facing;
        open();
    }
    public int getFacing() { return facing; }
    public void setDisplayOrientation(int degrees) { yolo.setDisplayOrientation(degrees); }
}
```

**From MainActivity, remove:**
- Field `private int facing = 1`
- Camera open/close/switch logic (lines that call `yolo11ncnn.openCamera/closeCamera`)
- The `buttonSwitchCamera` click listener body (delegate to CameraManager)

**Add to MainActivity:**
```java
private CameraManager cameraManager;
// in onCreate: cameraManager = new CameraManager(yolo);
```

**Validation:**
- `grep "openCamera\|closeCamera\|facing =" android/src/main/java/com/barf/MainActivity.java` → no matches
- `grep "openCamera\|closeCamera" android/src/main/java/com/barf/camera/CameraManager.java` → matches
- App compiles

---

### Step 2.3 — Extract RobotController

**What:** Pull robot movement logic (currently in `MainActivity implements RobotControlCallback`) into its own class.

**New file:** `android/src/main/java/com/barf/robot/RobotController.java`

```java
package com.barf.robot;

public class RobotController {
    private volatile boolean isMoving = false;
    private volatile String lastCommand = "none";
    private volatile int robotX = 0, robotY = 0, robotR = 0, robotE = 0;
    
    public void move(String direction, float speed) {
        isMoving = true;
        lastCommand = "move:" + direction + ":" + speed;
        int motorSpeed = (int) (speed * 255);
        int x = 0, y = 0;
        switch (direction.toLowerCase()) {
            case "forward": y = motorSpeed; break;
            case "backward": y = -motorSpeed; break;
            case "left": x = -motorSpeed; break;
            case "right": x = motorSpeed; break;
        }
        robotX = x; robotY = y; robotR = 0; robotE = 0;
        // Serial send delegated to SerialManager (wired in Step 2.4)
    }
    
    public void rotate(String direction, float speed) { /* similar */ }
    public void stop() {
        isMoving = false;
        lastCommand = "stop";
        robotX = 0; robotY = 0; robotR = 0; robotE = 0;
    }
    
    public RobotStatus getStatus() { /* return snapshot */ }
    public int[] getMotorValues() { return new int[]{robotX, robotY, robotR, robotE}; }
    
    public static class RobotStatus {
        public boolean isMoving;
        public String lastCommand;
        public int cameraFacing;
        public long timestamp;
    }
}
```

**From MainActivity, remove:**
- All `isMoving`, `lastCommand`, `robotX/Y/R/E` fields
- `onMove()`, `onRotate()`, `onStop()`, `onCameraSwitch()`, `getRobotStatus()` implementations
- `implements SimpleHttpServer.RobotControlCallback`
- `RobotControlCallback` interface — move to RobotController or delete (replaced by RobotController as the API)

**Update SimpleHttpServer:**
- Replace `RobotControlCallback` interface dependency with direct `RobotController` reference
- OR keep callback but delegate to RobotController from MainActivity

**Validation:**
- `MainActivity.java` no longer implements RobotControlCallback
- `RobotController.java` contains all movement logic
- Compiles

---

### Step 2.4 — Add UsbSerialManager (replaces UDP)

**What:** New class for USB-serial communication with ESP32. Replaces the UDP socket code entirely.

**New file:** `android/src/main/java/com/barf/serial/UsbSerialManager.java`

This implements the USB Host CDC ACM flow from `sdk/CLAUDE.md`:
- Detect ESP32 via UsbManager
- Open CDC ACM interface
- Read thread: accumulate lines, parse JSON, dispatch `{"s":[...]}` and `{"c":"..."}` 
- Write method: `sendMotorCommand(int[] speeds)` → `{"m":[...]}\n`
- Heartbeat: ping every 2s, check for pong

**New file:** `android/src/main/java/com/barf/serial/SerialProtocol.java`

```java
package com.barf.serial;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public class SerialProtocol {
    public static String motorCommand(int[] speeds) {
        JsonObject msg = new JsonObject();
        JsonArray arr = new JsonArray();
        for (int s : speeds) arr.add(s);
        msg.add("m", arr);
        return msg.toString() + "\n";
    }
    
    public static String ping() {
        return "{\"c\":\"ping\"}\n";
    }
    
    public static class SensorData {
        public int[] values;
        public static SensorData fromJson(String json) { /* parse */ }
    }
}
```

**From MainActivity, remove:**
- `DatagramSocket udpSocket` field
- `ROBOT_UDP_PORT` constant
- `initializeUdpSocket()` method
- `sendUdpCommand()` method
- All UDP imports (`java.net.DatagramPacket`, etc.)

**Add to AndroidManifest.xml:**
```xml
<uses-feature android:name="android.hardware.usb.host" android:required="true" />
```

**Add to res/xml/device_filter.xml:** (new file)
```xml
<resources>
    <usb-device vendor-id="12346" />
</resources>
```

**Validation:**
- `grep -i "udp\|datagram" android/src/main/java/com/barf/` → no matches
- `grep "UsbSerialManager" android/src/main/java/com/barf/MainActivity.java` → wired in
- `UsbSerialManager.java` compiles
- `SerialProtocol.java` compiles
- `device_filter.xml` exists

---

### Step 2.5 — Add WasmRuntime (WAMR JNI wrapper)

**What:** New Java class that loads and manages WASM modules via WAMR.

**New file:** `android/src/main/java/com/barf/runtime/WasmRuntime.java`

```java
package com.barf.runtime;

public class WasmRuntime {
    static { System.loadLibrary("barf_runtime"); } // new .so for WAMR JNI
    
    private long nativeHandle = 0;
    private boolean loaded = false;
    
    // JNI methods
    private native long nativeInit();
    private native boolean nativeLoad(long handle, byte[] wasmBytes);
    private native boolean nativeCallSetup(long handle);
    private native boolean nativeCallOnFrame(long handle, String detectionsJson);
    private native void nativeDestroy(long handle);
    
    public boolean load(byte[] wasmBytes) { /* calls native */ }
    public boolean start() { /* calls setup() */ }
    public boolean onFrame(String detectionsJson) { /* calls on_frame() */ }
    public void stop() { /* cleanup */ }
    public String getLastLog() { /* host function log capture */ }
}
```

**New file:** `android/src/main/jni/wasm_runtime.cpp`
- JNI bridge to WAMR C API
- Registers host functions: `host_move`, `host_rotate`, `host_stop`, `host_sleep_ms`, `host_get_detections`, `host_log`
- Host functions call back into Java via JNI to RobotController and YoloBridge

**Update CMakeLists.txt:**
- Add WAMR as FetchContent or prebuilt .a
- Add `wasm_runtime.cpp` to the library sources
- Add a second library target `barf_runtime` or add to existing `yolo11ncnn`

**Validation:**
- `WasmRuntime.java` compiles
- `wasm_runtime.cpp` compiles and links
- `libbarf_runtime.so` (or unified .so) built successfully
- A test .wasm file runs and logs output

---

### Step 2.6 — Replace Rhino with GraalJS

**What:** Swap the JS engine dependency.

**Update android/build.gradle:**
```groovy
// Remove:
// implementation 'io.apisense:rhino-android:1.1.1'

// Add:
implementation 'org.graalvm.js:js:23.1.2'
implementation 'org.graalvm.js:js-scriptengine:23.1.2'
```

**Update:** `RhinoScriptExecutor.java` → rename to `JsRuntime.java`

**Changes:**
- Replace Rhino `Context`, `ScriptableObject` with GraalJS `Context`, `Value`
- The JS API surface stays identical (`move()`, `rotate()`, `stop()`, `sleep()`, `log()`, `onDetection()`)
- GraalJS supports ES6+ so users get arrow functions, let/const, template literals

**Validation:**
- `JsRuntime.java` compiles
- Quick JS snippet executes: `move("forward", 0.5); sleep(1000); stop();`
- Error messages are clearer than Rhino's raw exceptions
- Old Rhino dependency removed from build.gradle

---

### Step 2.7 — Create slim PhoneApiServer

**What:** Rewrite the HTTP server as a focused API server. Remove static file serving (that's the desktop app's job now). Remove MJPEG streaming (replaced by WebRTC in Phase 4).

**New file:** `android/src/main/java/com/barf/server/PhoneApiServer.java`

Endpoints (from `desktop/CLAUDE.md`):
```
POST /api/wasm          ← deploy .wasm binary
POST /api/wasm/start    ← start WASM execution
POST /api/wasm/stop     ← stop WASM execution
POST /api/firmware      ← forward .bin to ESP32 over serial
POST /api/js/run        ← execute JS snippet
POST /api/js/stop       ← stop JS execution
GET  /api/status        ← health, WASM hash, serial state, FPS
WS   /api/serial        ← bidirectional serial relay
WS   /api/events        ← detection JSON push, robot state
```

**From old SimpleHttpServer, keep:**
- NanoHTTPD extension pattern
- JSON response helpers
- CORS headers

**Delete:**
- `serveStaticFile()` — no more web UI serving
- MJPEG streaming classes (`MjpegResponse`, `MjpegInputStream`)
- Script storage in SharedPreferences (the desktop app manages files)
- `RobotControlCallback` — replaced by RobotController
- All `/api/robot/*` endpoints (replaced by WASM/JS execution + serial)

**Keep but move to PhoneApiServer:**
- WebSocket server management
- Script execution endpoints (refactored to delegate to JsRuntime + WasmRuntime)

**Validation:**
- `PhoneApiServer.java` < 400 lines (down from SimpleHttpServer's 962)
- All old endpoints removed
- New endpoints wired to appropriate services
- `POST /api/status` returns correct health data
- `SimpleHttpServer.java` → deleted or deprecated with @Deprecated

---

### Step 2.8 — Slim down MainActivity

**What:** After all extractions, MainActivity should only handle:
- Android lifecycle (onCreate, onResume, onPause, onDestroy)
- SurfaceView management (callbacks)
- Wiring services together (constructor DI)
- Starting/stopping PhoneApiServer

**Target: MainActivity.java < 150 lines** (down from 657).

**Final MainActivity structure:**
```java
public class MainActivity extends Activity implements SurfaceHolder.Callback {
    private YoloBridge yolo;
    private CameraManager camera;
    private RobotController robot;
    private UsbSerialManager serial;
    private WasmRuntime wasm;
    private JsRuntime js;
    private PhoneApiServer server;
    private SurfaceView cameraView;
    
    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Setup UI
        // Create services
        // Wire dependencies
        // Start server
    }
    
    // SurfaceHolder.Callback — delegate to camera + yolo
    // Lifecycle — delegate to camera, server, serial
}
```

**Validation:**
- `wc -l MainActivity.java` → < 150 lines
- All robot control, camera, server logic lives in dedicated classes
- App starts, camera works, serial connects

---

### Step 2.9 — Commit the Java refactor

```bash
git add -A
git commit -m "refactor: split MainActivity god object into camera/vision/serial/runtime/server/robot packages"
```

**Validation:**
- Full build: `./gradlew :android:assembleDebug`
- No compile errors
- App installs and runs on device
- Camera preview works

---

## ✅ PHASE 3 — Native C++ Cleanup (done)

### Step 3.1 — Wrap native globals in context struct

**What:** Replace 5 static globals with a single `AppContext` struct.

**File:** `android/src/main/jni/yolo11ncnn.cpp`

**Before:**
```cpp
static YOLO11* g_yolo11 = 0;
static ncnn::Mutex lock;
static std::atomic<int> g_display_rotation{0};
static JavaVM* g_jvm_global = nullptr;
static jobject g_main_activity_global = nullptr;
static AprilTagDetector* g_apriltag = nullptr;
static MyNdkCamera* g_camera = 0;
```

**After:**
```cpp
struct AppContext {
    YOLO11* yolo = nullptr;
    MyNdkCamera* camera = nullptr;
    AprilTagDetector* apriltag = nullptr;
    JavaVM* jvm = nullptr;
    jobject main_activity = nullptr;
    std::atomic<int> display_rotation{0};
    ncnn::Mutex lock;
};

static AppContext g_ctx;  // single global
```

**Update all references:**
- `g_yolo11` → `g_ctx.yolo`
- `g_camera` → `g_ctx.camera`
- `g_apriltag` → `g_ctx.apriltag`
- `g_jvm_global` → `g_ctx.jvm`
- `g_main_activity_global` → `g_ctx.main_activity`
- `g_display_rotation` → `g_ctx.display_rotation`
- `lock` → `g_ctx.lock`

**Validation:**
- `grep "g_yolo11\|g_camera\|g_apriltag\|g_jvm_global\|g_main_activity_global\|g_display_rotation" android/src/main/jni/yolo11ncnn.cpp` → no matches
- `grep "g_ctx\." android/src/main/jni/yolo11ncnn.cpp` → matches for all the above
- Compiles and links

---

### Step 3.2 — Add WASM runtime JNI bridge

**What:** Implement `wasm_runtime.cpp` with WAMR integration.

**File:** `android/src/main/jni/wasm_runtime.cpp`

**Content outline:**
- `#include <wasm_export.h>` (WAMR)
- JNI functions for `WasmRuntime.java` native methods
- Host function implementations that call back to Java RobotController
- Module lifecycle: `nativeInit` → `nativeLoad` → `nativeCallSetup` → `nativeCallOnFrame` → `nativeDestroy`

**Update CMakeLists.txt:**
```cmake
# Add WAMR
FetchContent_Declare(
    wamr
    GIT_REPOSITORY https://github.com/bytecodealliance/wasm-micro-runtime.git
    GIT_TAG WAMR-2.3.0
    GIT_SHALLOW TRUE
)

# ... configure WAMR for Android ...

add_library(barf_runtime SHARED wasm_runtime.cpp)
target_link_libraries(barf_runtime wamr)
```

**Validation:**
- WAMR compiles for arm64-v8a
- `libbarf_runtime.so` built
- A simple `.wasm` file (exporting `setup` and `on_frame`) loads and runs
- Host function `host_log("hello")` produces output visible in logcat

---

### Step 3.3 — Integrate WASM into frame pipeline

**What:** Call WASM `on_frame()` from the per-frame render callback instead of (or in addition to) the JNI detection callback.

**Modify `MyNdkCamera::on_image_render()`:**

After detection JSON is built, instead of calling `pushDetectionsToScripts` via JNI, pass the JSON to the WASM runtime:
```cpp
if (g_ctx.wasm_runtime && g_ctx.wasm_loaded) {
    wasm_call_on_frame(g_ctx.wasm_runtime, json.c_str());
}
```

The WASM module's `on_frame()` implementation calls `move()`/`rotate()`/`stop()` which are host functions that call back to Java `RobotController`, which calls `UsbSerialManager.sendMotorCommand()`.

**Validation:**
- A test WASM module that calls `log_info("frame")` on every frame produces log output
- A test WASM module that calls `move("forward", 0.5)` results in serial output `{"m":[0,128,0,0]}`
- Frame rate unaffected (WASM call < 1ms)

---

### Step 3.4 — Commit the native refactor

```bash
git add -A
git commit -m "refactor: wrap native globals in context struct, add WAMR JNI bridge, integrate WASM in frame pipeline"
```

**Validation:**
- `libyolo11ncnn.so` and `libbarf_runtime.so` build
- Camera works, YOLO detects, WASM receives detections
- Full APK installs and runs

---

## ✅ PHASE 4 — Desktop Companion App (Tauri) (done)

### Step 4.1 — Scaffold Tauri project

**What:** Create the Tauri project structure inside `desktop/`.

```bash
cd desktop
npm create vite@latest . -- --template react-ts   # overwrites existing if needed
npm install
npm install -D @tauri-apps/cli
npx tauri init
```

**Configure `src-tauri/tauri.conf.json`:**
```json
{
  "build": {
    "devUrl": "http://localhost:5173",
    "frontendDist": "../dist"
  },
  "app": {
    "title": "BARF Console",
    "windows": [{"title": "BARF Console", "width": 1400, "height": 900}]
  }
}
```

**Update `src-tauri/Cargo.toml`:**
```toml
[dependencies]
tauri = "2"
serde = { version = "1", features = ["derive"] }
serde_json = "1"
reqwest = { version = "0.12", features = ["json", "multipart"] }
tokio = { version = "1", features = ["full"] }
tokio-tungstenite = "0.24"
```

**Validation:**
- `cargo build` in `src-tauri/` succeeds
- `npm run tauri dev` opens the app window
- App displays the React UI

---

### Step 4.2 — Port React UI to desktop app

**What:** Adapt the existing React pages to the desktop app layout.

**Keep / update:**
- `ControlPage.tsx` → `Dashboard.tsx` — add serial monitor panel, WASM status, ESP32 status
- `CodePage.tsx` → `VisionEditor.tsx` + `FirmwareEditor.tsx` — two separate C++ editor tabs
- `SettingsPage.tsx` → keep, add toolchain paths (arduino-cli, clang)

**Remove:**
- Old camera feed via `<img src=/stream/video>` (replace with WebRTC component — stub for now)
- Old JS-only script execution flow
- `getHttpBase()` function (now connects to phone via ADB-forwarded or direct IP)

**Add:**
- `PhoneConnect.tsx` — connection status, ADB or IP selector
- `SerialMonitor.tsx` — scrolling log of phone↔ESP32 serial messages
- `Toolbar.tsx` — build/deploy/stop buttons

**Validation:**
- App opens with 4 tabs: Dashboard, Vision (WASM), Firmware (ESP32), Settings
- Dashboard shows placeholder camera area, serial monitor, robot status
- Settings lets you configure phone IP and toolchain paths

---

### Step 4.3 — Implement Rust backend: compile service

**What:** `src-tauri/src/compile.rs`

```rust
// Tauri commands registered in main.rs:
// #[tauri::command] async fn compile_wasm(source: String) -> Result<Vec<u8>, String>
// #[tauri::command] async fn compile_esp32(source: String, board: String) -> Result<Vec<u8>, String>

pub async fn compile_wasm(source: &str) -> Result<Vec<u8>, String> {
    // 1. Write source to temp file
    // 2. Run: clang --target=wasm32 -O3 -c source.cpp -o output.wasm
    //    (With -I/path/to/sdk for barf.h include path)
    // 3. Read output.wasm bytes
    // 4. Return bytes
}

pub async fn compile_esp32(source: &str, board: &str) -> Result<Vec<u8>, String> {
    // 1. Create temp sketch directory with source + robot_firmware.h
    // 2. Run: arduino-cli compile --fqbn esp32:esp32:esp32 sketch/
    // 3. Read .bin from build output
    // 4. Return bytes
}
```

**Validate WASM compilation:**
- Copy `sdk/examples/follow_ball.cpp` into the editor
- Click Compile
- Receives .wasm bytes back (check size > 0, first 4 bytes = 0x00 0x61 0x73 0x6d = "\0asm")

**Validate ESP32 compilation:**
- Copy `sdk/robot_firmware_template.ino` into the editor
- Click Compile
- Receives .bin bytes back
- Flash to a real ESP32, verify it boots and responds to `{"c":"ping"}` with `{"c":"pong"}`

---

### Step 4.4 — Implement Rust backend: phone bridge

**What:** `src-tauri/src/phone_bridge.rs`

```rust
pub struct PhoneConnection {
    base_url: String,   // e.g. "http://localhost:8080" (ADB forwarded) or "http://192.168.1.x:8080"
    ws_serial: Option<WebSocket>,
    ws_events: Option<WebSocket>,
}

impl PhoneConnection {
    pub async fn connect(ip: &str) -> Result<Self, String>;
    pub async fn deploy_wasm(&self, wasm_bytes: Vec<u8>) -> Result<String, String>;
    pub async fn flash_esp32(&self, bin_bytes: Vec<u8>) -> Result<String, String>;
    pub async fn serial_write(&self, data: &str) -> Result<(), String>;
    pub async fn serial_read(&self) -> String;  // from WS stream
    pub async fn get_status(&self) -> Result<PhoneStatus, String>;
    pub async fn run_js(&self, script: &str) -> Result<String, String>;
}
```

**Validation:**
- Phone connected via ADB: `adb forward tcp:8080 tcp:8080`
- `deploy_wasm()` sends .wasm to phone, receives `{"loaded":true}`
- `get_status()` returns phone health data
- `serial_write()` sends a test message, `serial_read()` receives ESP32 response

---

### Step 4.5 — Wire UI to backend

**What:** Connect the React frontend to the Rust backend via Tauri `invoke()`.

**Example (VisionEditor.tsx):**
```tsx
import { invoke } from '@tauri-apps/api/core';

async function compileAndDeploy() {
    try {
        // Compile
        const wasmBytes: number[] = await invoke('compile_wasm', { source: editorValue });
        
        // Deploy to phone
        const result: string = await invoke('deploy_wasm', { ip: phoneIp, wasmBytes });
        
        // Start execution
        await invoke('phone_api', { ip: phoneIp, method: 'POST', path: '/api/wasm/start' });
        
        setStatus('Running');
    } catch (e) {
        setError(e as string);
    }
}
```

**Validation:**
- Full flow works: edit C++ → click Compile → .wasm deploy to phone → WASM starts running
- ESP32 flow works: edit Arduino code → click Compile → .bin sent to phone → phone flashes ESP32
- Serial monitor shows bidirectional traffic
- Camera feed placeholder is visible

---

### Step 4.6 — Commit the desktop app

```bash
git add -A
git commit -m "feat: Tauri desktop companion app — WASM/ESP32 editor, compile service, phone bridge"
```

**Validation:**
- `cargo build` succeeds
- `npm run tauri build` produces a distributable binary
- Full dev workflow: edit → compile → deploy → run, all from the desktop app

---

## PHASE 5 — Integration & Hardening

### Step 5.1 — End-to-end competition mode test

**What:** Verify the full airgapped flow works.

**Test procedure:**
1. Flash ESP32 with `robot_firmware_template.ino` + user's motor implementation
2. Deploy a WASM vision script (e.g., `follow_ball.cpp`) to phone via desktop app
3. Disconnect laptop completely
4. Power on phone + ESP32 (USB cable between them)
5. Phone boots, auto-starts BARF app
6. WASM module loads automatically
7. Camera opens, YOLO starts detecting
8. WASM `on_frame()` fires, processes detections, calls `move()`/`rotate()`
9. Serial JSON `{"m":[...]}` reaches ESP32
10. ESP32 drives motors
11. ESP32 sends `{"s":[...]}` back
12. WASM can call `serial_read()` to get sensor data

**Validation:**
- Robot moves in response to camera input
- No laptop, no WiFi, no internet
- Button on phone screen to emergency stop (override WASM → send `{"m":[0,0,0,0]}`)
- All of this works for 2+ hours continuously (no memory leak, no crash)

---

### Step 5.2 — Error handling pass

**What:** Replace catch-log-continue with structured error reporting.

**Pattern:**
```java
// Before:
try { something(); } catch (Exception e) { Log.e(TAG, "failed: " + e.getMessage()); }

// After:
try { something(); } catch (Exception e) {
    statusService.setError(ErrorCode.SERIAL_DISCONNECTED, e.getMessage());
}
```

**Add:** `StatusService.java` — centralized error/status reporting that:
- Accumulates errors per component (camera, serial, WASM, model)
- Exposes via `GET /api/status` as structured JSON
- Broadcasts errors via WebSocket events
- Shows on phone screen as overlay if critical

**Validation:**
- Unplug ESP32 USB → phone screen shows "Serial disconnected" within 2 seconds
- WASM compile error → desktop app shows error with line number
- Camera permission denied → phone screen shows "Camera permission required"
- Model fails to load → phone screen shows "Model not found" + instructions

---

### Step 5.3 — Add safety features

**What:** Hardware and software safety.

**Implement:**
1. **WASM watchdog timer** — if `on_frame()` takes > 500ms, kill the module and stop motors
2. **Serial heartbeat** — if no pong from ESP32 in 3 seconds, stop motors and alert
3. **Physical E-stop button** — big red button on phone screen that sends `{"m":[0,0,0,0]}` and kills WASM
4. **Battery monitor** — if phone battery < 5%, stop motors and alert
5. **Auto-stop on app minimize** — if app goes to background, stop motors

**Validation:**
- Infinite loop in WASM → watchdog kills it, motors stop
- Unplug ESP32 → serial heartbeat fails, motors stop, alert shown
- Press E-stop button → motors stop instantly
- Minimize app → motors stop

---

### Step 5.4 — APK build pipeline

**What:** Automate the APK build process.

**Add to root `build.gradle` or create `Makefile`:**

```bash
# Build everything
make apk           # ./gradlew :android:assembleRelease
make desktop       # cd desktop && npm run tauri build
make all           # both

# Dev helpers
make install       # adb install android/build/outputs/apk/release/barf-release.apk
make logcat        # adb logcat | grep BARF
make deploy-wasm   # curl -X POST http://localhost:8080/api/wasm --data-binary @sdk/examples/follow_ball.wasm
```

**Validation:**
- `make apk` produces signed APK
- `make install` installs and app launches
- `make desktop` produces distributable binary

---

### Step 5.5 — Final commit and documentation

**What:** Finalize everything.

**Update `README.md`:**
- New architecture diagram
- Quickstart: "Download APK, install, plug ESP32 into phone, open desktop app"
- Build from source instructions
- Links to SDK reference headers and examples

**Commit:**
```bash
git add -A
git commit -m "feat: complete BARF v2 — USB-serial, WAMR, Tauri desktop app, safety features"
```

**Tag:**
```bash
git tag v2.0.0-beta
```

**Validation:**
- README is accurate and complete
- Someone new can follow the quickstart and get a robot moving
- All commits are clean (no "wip" or "fix typo" commits — squash if needed)

---

## Summary: Phases at a glance

| Phase | What | Commits | Validation gate |
|-------|------|---------|-----------------|
| 0 | Cleanup: .gitignore, cruft deletion, model file organization | 1 | `git status` clean |
| 1 | Restructure: directories, package rename, Gradle paths | 1-2 | `./gradlew :android:assembleDebug` succeeds |
| 2 | Java refactor: split god object, USB-serial, WAMR, GraalJS, slim server | 1-2 per step | Each extraction compiles and runs |
| 3 | Native cleanup: context struct, WASM JNI, frame pipeline | 1 | .so builds, WASM runs in pipeline |
| 4 | Desktop app: Tauri scaffold, React port, compile service, phone bridge | 2-3 | Full edit→compile→deploy→run flow |
| 5 | Integration: E2E test, error handling, safety, build pipeline, docs | 2 | Robot moves from vision input, airgapped |

**Total: ~10-15 commits across 5 phases.**
