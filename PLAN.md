# PLAN.md — BARF Restructure & Rebuild

## Audit findings (2026-06-03)

| Problem | Detail |
|---------|--------|
| 16KB ELF alignment | App fails on Android 15+ devices with 16KB page sizes. Prebuilt libraries (ncnn, OpenCV, Mesa Turnip) and ML Kit dependencies are not aligned. |
| QR pairing flow | Current flow opens main app first, then allows pairing. Should be: QR pairing screen → main app. |
| WireGuard integration | Pairing should use WireGuard VPN for secure connection without requiring same local network. |
| App stability | Multiple bugs and rough edges in the app flow need smoothing. |

---

## PHASE 6 — Fix 16KB ELF Alignment Compatibility

### Step 6.1 — Update CameraX dependencies to 1.4.2+

**What:** CameraX 1.4.2+ includes 16KB-aligned `libimage_processing_util_jni.so`. This fixes the ML Kit compatibility issue.

**File:** `android/build.gradle`

**Changes:**
```groovy
// Replace CameraX 1.3.0 with 1.4.2+
implementation 'androidx.camera:camera-core:1.4.2'
implementation 'androidx.camera:camera-camera2:1.4.2'
implementation 'androidx.camera:camera-lifecycle:1.4.2'
implementation 'androidx.camera:camera-view:1.4.2'

// Force resolution to ensure all CameraX dependencies use 1.4.2+
configurations.configureEach {
    resolutionStrategy {
        force "androidx.camera:camera-core:1.4.2",
              "androidx.camera:camera-camera2:1.4.2",
              "androidx.camera:camera-lifecycle:1.4.2",
              "androidx.camera:camera-view:1.4.2"
    }
}
```

**Validation:**
- Build succeeds
- `libimage_processing_util_jni.so` shows 16KB alignment

---

### Step 6.2 — Update ML Kit barcode scanning

**What:** Update to latest ML Kit version which may have 16KB-aligned libraries.

**File:** `android/build.gradle`

**Changes:**
```groovy
// Update ML Kit barcode scanning
implementation 'com.google.mlkit:barcode-scanning:17.3.0'
```

**Validation:**
- Build succeeds
- `libbarhopper_v3.so` shows improved alignment (if available)

---

### Step 6.3 — Add 16KB linker flags to CMakeLists.txt

**What:** Add linker flags to ensure `libyolo11ncnn.so` is built with 16KB alignment.

**File:** `android/src/main/jni/CMakeLists.txt`

**Changes:** Add after the `target_link_libraries` line:
```cmake
# 16KB ELF alignment for Android 15+ compatibility
if(ANDROID_ABI STREQUAL "arm64-v8a" OR ANDROID_ABI STREQUAL "x86_64")
    target_link_options(yolo11ncnn PRIVATE
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384"
    )
endif()
```

**Validation:**
- Build succeeds
- `libyolo11ncnn.so` shows 16KB alignment using `llvm-objdump -p`

---

### Step 6.4 — Update Android Gradle Plugin and NDK

**What:** Ensure AGP 8.5.1+ and NDK r28+ are used for automatic 16KB support.

**File:** `build.gradle` (root)

**Changes:**
```groovy
// Update AGP version
plugins {
    id 'com.android.application' version '8.7.3' apply false
    // Ensure AGP 8.5.1+ for 16KB support
}
```

**File:** `android/build.gradle`

**Changes:**
```groovy
// NDK r28+ handles 16KB alignment by default
ndkVersion "29.0.14206865"  // Already using r29, which is good
```

**Validation:**
- Build succeeds with updated AGP/NDK
- All arm64-v8a .so files show 16KB alignment

---

### Step 6.5 — Handle prebuilt libraries (ncnn, OpenCV, Mesa Turnip)

**What:** The prebuilt libraries may not be 16KB aligned. Options:
1. Rebuild from source with 16KB flags (recommended)
2. Use post-build patching script
3. Exclude incompatible ABIs if not needed

**Option 1 (Recommended): Rebuild prebuilt libraries**

For ncnn and OpenCV, rebuild with:
```cmake
# In their respective build systems
-Wl,-z,max-page-size=16384
-Wl,-z,common-page-size=16384
```

**Option 2: Post-build patching**

Create `tools/patch_elf_16kb.py` to patch ELF headers:
```python
#!/usr/bin/env python3
"""Patch ELF files for 16KB page alignment."""
import struct
import os
import sys

def patch_elf(filepath):
    with open(filepath, 'r+b') as f:
        # Read ELF header
        ident = f.read(16)
        if ident[:4] != b'\x7fELF':
            return False
        
        is_64 = ident[4] == 2
        is_little = ident[5] == 1
        
        # Parse program headers and fix alignment
        # ... (implementation details)
        
    return True

if __name__ == '__main__':
    for root, dirs, files in os.walk(sys.argv[1]):
        for f in files:
            if f.endswith('.so'):
                filepath = os.path.join(root, f)
                if patch_elf(filepath):
                    print(f"Patched: {filepath}")
```

**Option 3: Exclude problematic ABIs**

If only arm64-v8a is needed for target devices:
```groovy
android {
    defaultConfig {
        ndk {
            abiFilters 'arm64-v8a'
        }
    }
}
```

**Validation:**
- All .so files in APK show 16KB alignment
- App runs on Android 15+ devices with 16KB page sizes

---

### Step 6.6 — Verify alignment with check_elf_alignment.sh

**What:** Use Android's official script to verify all libraries are aligned.

**Command:**
```bash
# Download the script from Android
curl -O https://developer.android.com/ndk/guides/page-alignment-check/check_elf_alignment.sh
chmod +x check_elf_alignment.sh

# Run on built APK
./check_elf_alignment.sh android/build/outputs/apk/debug/barf-debug.apk
```

**Expected output:**
```
=== ELF alignment ===
lib/arm64-v8a/libyolo11ncnn.so: ALIGNED (2**14)
lib/arm64-v8a/libncnn.so: ALIGNED (2**14)
lib/arm64-v8a/libimage_processing_util_jni.so: ALIGNED (2**14)
lib/arm64-v8a/libvulkan_freedreno.so: ALIGNED (2**14)
...
ELF Verification Successful
```

**Validation:**
- All arm64-v8a libraries show ALIGNED
- No UNALIGNED libraries in output

---

## PHASE 7 — QR Code Pairing with WireGuard

### Step 7.1 — Create PairingActivity as first screen

**What:** Modify app flow so QR pairing is the first screen shown when app opens.

**File:** `android/src/main/AndroidManifest.xml`

**Changes:**
```xml
<activity
    android:name=".pairing.PairingActivity"
    android:exported="true"
    android:launchMode="singleTask"
    android:screenOrientation="landscape">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
</activity>

<activity
    android:name=".MainActivity"
    android:exported="false"
    android:screenOrientation="landscape"
    android:launchMode="singleTask" />
```

**File:** `android/src/main/java/com/barf/pairing/PairingActivity.java`

**New flow:**
1. App opens → PairingActivity (QR scanner)
2. User scans QR code from desktop app
3. PairingActivity establishes WireGuard connection
4. On successful pairing → launch MainActivity
5. If already paired → skip to MainActivity (with option to re-pair)

**Validation:**
- App opens directly to QR scanner
- Scanning QR code triggers WireGuard connection
- Successful pairing transitions to main app

---

### Step 7.2 — Integrate WireGuard library

**What:** Add WireGuard Android library for VPN-based pairing.

**File:** `android/build.gradle`

**Changes:**
```groovy
// WireGuard Android library
implementation 'com.wireguard.android:tunnel:1.0.20230712'
```

**File:** `android/src/main/java/com/barf/pairing/WireGuardManager.java`

**New class:**
```java
package com.barf.pairing;

import com.wireguard.android.backend.GoBackend;
import com.wireguard.config.Config;
import com.wireguard.config.InetEndpoint;
import com.wireguard.config.InetNetwork;
import com.wireguard.config.Interface;
import com.wireguard.config.Peer;

public class WireGuardManager {
    private Context context;
    private GoBackend backend;
    
    public WireGuardManager(Context context) {
        this.context = context;
        this.backend = new GoBackend(context);
    }
    
    public void connect(String serverIp, String publicKey, String clientIp) {
        // Build WireGuard config
        Interface.Builder interfaceBuilder = new Interface.Builder();
        interfaceBuilder.parsePrivateKey(generatePrivateKey());
        interfaceBuilder.setAddress(InetNetwork.parse(clientIp + "/32"));
        interfaceBuilder.setDnsServer(InetNetwork.parse("1.1.1.1"));
        
        Peer.Builder peerBuilder = new Peer.Builder();
        peerBuilder.parsePublicKey(publicKey);
        peerBuilder.setEndpoint(InetEndpoint.parse(serverIp + ":51820"));
        peerBuilder.setAllowedIpRange(InetNetwork.parse("0.0.0.0/0"));
        
        Config config = new Config(interfaceBuilder.build(), peerBuilder.build());
        
        // Start tunnel
        backend.setTunnelConfig(config);
    }
    
    public void disconnect() {
        backend.setTunnelConfig(null);
    }
    
    private String generatePrivateKey() {
        // Generate WireGuard private key
        return com.wireguard.crypto.Key.generateKeyPair().toBase64();
    }
}
```

**Validation:**
- WireGuard library compiles
- Can establish VPN connection
- Phone and desktop can communicate over WireGuard tunnel

---

### Step 7.3 — Update QR code format for WireGuard

**What:** Modify QR code format to include WireGuard configuration.

**Desktop side changes:**

**File:** `desktop/src-tauri/src/pairing_server.rs`

**Changes:**
```rust
#[tauri::command]
async fn start_pairing() -> Result<PairingInfo, String> {
    let key = generate_key(); // Existing key generation
    
    // Generate WireGuard keys
    let wg_private_key = generate_wg_private_key();
    let wg_public_key = derive_public_key(&wg_private_key);
    
    // Generate client IP
    let client_ip = "10.0.0.2"; // WireGuard tunnel IP
    
    Ok(PairingInfo {
        desktop_ip: get_local_ip(),
        port: 9876,
        key: key.clone(),
        wg_public_key: wg_public_key,
        wg_server_ip: "YOUR_PUBLIC_IP", // Or use STUN/TURN for NAT traversal
        wg_client_ip: client_ip,
        wg_port: 51820,
    })
}
```

**QR code format:**
```
barf://pair?ip=DESKTOP_IP&key=PAIR_KEY&port=9876&wg_ip=WG_PUBLIC_IP&wg_key=WG_PUBLIC_KEY&wg_client=CLIENT_IP&wg_port=51820
```

**Phone side parsing:**

**File:** `android/src/main/java/com/barf/pairing/PairingActivity.java`

**Changes:**
```java
private void parseQrCode(String qrData) {
    Uri uri = Uri.parse(qrData);
    String desktopIp = uri.getQueryParameter("ip");
    String pairKey = uri.getQueryParameter("key");
    int port = Integer.parseInt(uri.getQueryParameter("port"));
    
    // WireGuard parameters
    String wgIp = uri.getQueryParameter("wg_ip");
    String wgKey = uri.getQueryParameter("wg_key");
    String wgClient = uri.getQueryParameter("wg_client");
    int wgPort = Integer.parseInt(uri.getQueryParameter("wg_port"));
    
    // Establish WireGuard connection first
    wireGuardManager.connect(wgIp, wgKey, wgClient);
    
    // Then send pairing request over WireGuard tunnel
    sendPairingRequest(desktopIp, port, pairKey);
}
```

**Validation:**
- QR code contains WireGuard configuration
- Phone can parse WireGuard parameters from QR code
- WireGuard connection established before HTTP pairing

---

### Step 7.4 — Implement NAT traversal for WireGuard

**What:** Handle cases where desktop is behind NAT. Options:
1. Use public IP if available
2. Use STUN/TURN server
3. Use UPnP port forwarding
4. Manual port forwarding instructions

**File:** `desktop/src-tauri/src/pairing_server.rs`

**Changes:**
```rust
async fn get_public_ip() -> Result<String, String> {
    // Try STUN server to get public IP
    let response = reqwest::get("https://api.ipify.org?format=json")
        .await
        .map_err(|e| e.to_string())?;
    
    let json: serde_json::Value = response.json()
        .await
        .map_err(|e| e.to_string())?;
    
    Ok(json["ip"].as_str().unwrap_or("").to_string())
}

#[tauri::command]
async fn start_pairing() -> Result<PairingInfo, String> {
    let local_ip = get_local_ip();
    let public_ip = get_public_ip().await.unwrap_or_default();
    
    // Use public IP if different from local IP (behind NAT)
    let server_ip = if public_ip != local_ip && !public_ip.is_empty() {
        public_ip
    } else {
        local_ip
    };
    
    // ... rest of pairing logic
}
```

**Validation:**
- Works with public IP
- Works with local network
- Handles NAT traversal scenarios

---

### Step 7.5 — Add connection persistence

**What:** Remember paired desktop connection for future sessions.

**File:** `android/src/main/java/com/barf/pairing/PairingManager.java`

**New class:**
```java
package com.barf.pairing;

import android.content.Context;
import android.content.SharedPreferences;

public class PairingManager {
    private static final String PREFS_NAME = "barf_pairing";
    private static final String KEY_DESKTOP_IP = "desktop_ip";
    private static final String KEY_WG_CONFIG = "wg_config";
    private static final String KEY_PAIRED = "is_paired";
    
    private SharedPreferences prefs;
    
    public PairingManager(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
    
    public void savePairing(String desktopIp, String wgConfig) {
        prefs.edit()
            .putString(KEY_DESKTOP_IP, desktopIp)
            .putString(KEY_WG_CONFIG, wgConfig)
            .putBoolean(KEY_PAIRED, true)
            .apply();
    }
    
    public boolean isPaired() {
        return prefs.getBoolean(KEY_PAIRED, false);
    }
    
    public String getDesktopIp() {
        return prefs.getString(KEY_DESKTOP_IP, null);
    }
    
    public void clearPairing() {
        prefs.edit().clear().apply();
    }
}
```

**Update PairingActivity:**
```java
@Override
protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    
    PairingManager pairingManager = new PairingManager(this);
    
    if (pairingManager.isPaired()) {
        // Already paired, try to connect automatically
        String desktopIp = pairingManager.getDesktopIp();
        connectToDesktop(desktopIp);
    } else {
        // Not paired, show QR scanner
        showQrScanner();
    }
}
```

**Validation:**
- After first pairing, app remembers desktop
- On subsequent launches, auto-connects to paired desktop
- Option to re-pair or clear pairing

---

### Step 7.6 — Update MainActivity for paired state

**What:** Modify MainActivity to work with WireGuard-paired desktop.

**File:** `android/src/main/java/com/barf/MainActivity.java`

**Changes:**
```java
@Override
public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    
    // Get pairing info from intent
    String desktopIp = getIntent().getStringExtra("desktop_ip");
    String wgConfig = getIntent().getStringExtra("wg_config");
    
    // Initialize services with paired desktop
    if (desktopIp != null) {
        apiServer.setDesktopIp(desktopIp);
        wireGuardManager.connect(wgConfig);
    }
    
    // ... rest of onCreate
}
```

**Update PhoneApiServer:**
```java
public void setDesktopIp(String ip) {
    this.desktopIp = ip;
    // Update WebSocket connection to desktop
    if (wsClient != null) {
        wsClient.connect("ws://" + ip + ":8081");
    }
}
```

**Validation:**
- MainActivity receives pairing info
- Connects to desktop over WireGuard
- WebSocket communication works over WireGuard tunnel

---

## PHASE 8 — Smooth App Flows and Fix Bugs

### Step 8.1 — Add loading states and error handling

**What:** Add proper loading indicators and error messages throughout the app.

**File:** `android/src/main/java/com/barf/ui/LoadingManager.java`

**New class:**
```java
package com.barf.ui;

import android.app.ProgressDialog;
import android.content.Context;

public class LoadingManager {
    private Context context;
    private ProgressDialog progressDialog;
    
    public LoadingManager(Context context) {
        this.context = context;
    }
    
    public void showLoading(String message) {
        progressDialog = new ProgressDialog(context);
        progressDialog.setMessage(message);
        progressDialog.setCancelable(false);
        progressDialog.show();
    }
    
    public void hideLoading() {
        if (progressDialog != null && progressDialog.isShowing()) {
            progressDialog.dismiss();
        }
    }
    
    public void showError(String message) {
        hideLoading();
        new AlertDialog.Builder(context)
            .setTitle("Error")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show();
    }
}
```

**Update PairingActivity:**
```java
private void connectToDesktop(String desktopIp) {
    loadingManager.showLoading("Connecting to desktop...");
    
    wireGuardManager.connect(desktopIp, new WireGuardManager.ConnectionCallback() {
        @Override
        public void onConnected() {
            loadingManager.hideLoading();
            launchMainActivity();
        }
        
        @Override
        public void onError(String error) {
            loadingManager.showError("Connection failed: " + error);
        }
    });
}
```

**Validation:**
- Loading spinner shown during connection
- Error messages displayed on failure
- Smooth transitions between states

---

### Step 8.2 — Add retry mechanisms

**What:** Add automatic retry for failed operations.

**File:** `android/src/main/java/com/barf/utils/RetryHelper.java`

**New class:**
```java
package com.barf.utils;

public class RetryHelper {
    public interface RetryCallback {
        void onSuccess();
        void onFailure(String error);
    }
    
    public static void retry(Runnable action, RetryCallback callback, int maxRetries, long delayMs) {
        new Thread(() -> {
            for (int i = 0; i < maxRetries; i++) {
                try {
                    action.run();
                    callback.onSuccess();
                    return;
                } catch (Exception e) {
                    if (i == maxRetries - 1) {
                        callback.onFailure(e.getMessage());
                    } else {
                        try {
                            Thread.sleep(delayMs);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
            }
        }).start();
    }
}
```

**Usage:**
```java
RetryHelper.retry(
    () -> wireGuardManager.connect(config),
    new RetryHelper.RetryCallback() {
        @Override public void onSuccess() { /* handle success */ }
        @Override public void onFailure(String error) { /* handle failure */ }
    },
    3, // max retries
    1000 // delay between retries
);
```

**Validation:**
- Failed operations retry automatically
- User sees retry attempts
- Eventually fails gracefully after max retries

---

### Step 8.3 — Add connection monitoring

**What:** Monitor WireGuard and HTTP connections, reconnect if needed.

**File:** `android/src/main/java/com/barf/network/ConnectionMonitor.java`

**New class:**
```java
package com.barf.network;

import android.os.Handler;
import android.os.Looper;

public class ConnectionMonitor {
    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable checkRunnable;
    private ConnectionCallback callback;
    private long checkIntervalMs = 5000;
    
    public interface ConnectionCallback {
        void onConnected();
        void onDisconnected();
    }
    
    public void startMonitoring(ConnectionCallback callback) {
        this.callback = callback;
        checkRunnable = new Runnable() {
            @Override
            public void run() {
                checkConnection();
                handler.postDelayed(this, checkIntervalMs);
            }
        };
        handler.post(checkRunnable);
    }
    
    private void checkConnection() {
        // Ping desktop over WireGuard
        new Thread(() -> {
            boolean connected = pingDesktop();
            handler.post(() -> {
                if (connected) {
                    callback.onConnected();
                } else {
                    callback.onDisconnected();
                }
            });
        }).start();
    }
    
    private boolean pingDesktop() {
        try {
            // HTTP ping to desktop
            URL url = new URL("http://" + desktopIp + ":9876/api/ping");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(2000);
            int response = conn.getResponseCode();
            conn.disconnect();
            return response == 200;
        } catch (Exception e) {
            return false;
        }
    }
    
    public void stopMonitoring() {
        handler.removeCallbacks(checkRunnable);
    }
}
```

**Validation:**
- Connection status updates in real-time
- Automatic reconnection on disconnect
- User notified of connection issues

---

### Step 8.4 — Add proper error codes and messages

**What:** Standardize error handling across the app.

**File:** `android/src/main/java/com/barf/utils/ErrorCode.java`

**New class:**
```java
package com.barf.utils;

public enum ErrorCode {
    CAMERA_PERMISSION_DENIED(100, "Camera permission is required for QR scanning"),
    CAMERA_OPEN_FAILED(101, "Failed to open camera"),
    
    QR_PARSE_ERROR(200, "Invalid QR code format"),
    QR_INVALID_PARAMETERS(201, "Missing required QR parameters"),
    
    WIREGUARD_INIT_FAILED(300, "Failed to initialize WireGuard"),
    WIREGUARD_CONNECT_FAILED(301, "Failed to connect to WireGuard server"),
    WIREGUARD_AUTH_FAILED(302, "WireGuard authentication failed"),
    
    PAIRING_REQUEST_FAILED(400, "Failed to send pairing request"),
    PAIRING_TIMEOUT(401, "Pairing request timed out"),
    PAIRING_KEY_MISMATCH(402, "Pairing key does not match"),
    
    SERVER_CONNECTION_FAILED(500, "Failed to connect to phone API server"),
    WEBSOCKET_CONNECTION_FAILED(501, "WebSocket connection failed"),
    
    MODEL_LOAD_FAILED(600, "Failed to load YOLO model"),
    DETECTION_FAILED(601, "Object detection failed");
    
    private final int code;
    private final String message;
    
    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
    
    public int getCode() { return code; }
    public String getMessage() { return message; }
}
```

**File:** `android/src/main/java/com/barf/utils/AppException.java`

**New class:**
```java
package com.barf.utils;

public class AppException extends Exception {
    private final ErrorCode errorCode;
    
    public AppException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }
    
    public AppException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }
    
    public ErrorCode getErrorCode() { return errorCode; }
}
```

**Validation:**
- Consistent error handling throughout app
- Meaningful error messages for users
- Error codes for debugging

---

### Step 8.5 — Add user feedback for operations

**What:** Show toast messages and notifications for important operations.

**File:** `android/src/main/java/com/barf/ui/FeedbackManager.java`

**New class:**
```java
package com.barf.ui;

import android.content.Context;
import android.widget.Toast;

public class FeedbackManager {
    private Context context;
    
    public FeedbackManager(Context context) {
        this.context = context;
    }
    
    public void showSuccess(String message) {
        Toast.makeText(context, "✓ " + message, Toast.LENGTH_SHORT).show();
    }
    
    public void showError(String message) {
        Toast.makeText(context, "✗ " + message, Toast.LENGTH_LONG).show();
    }
    
    public void showInfo(String message) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
    }
}
```

**Usage:**
```java
// After successful pairing
feedbackManager.showSuccess("Connected to desktop at " + desktopIp);

// After failed connection
feedbackManager.showError("Connection failed. Please try again.");

// During operation
feedbackManager.showInfo("Scanning QR code...");
```

**Validation:**
- User sees feedback for all operations
- Success/error states clearly communicated
- Non-intrusive notifications

---

### Step 8.6 — Add offline mode

**What:** Allow app to work without desktop connection for local operations.

**File:** `android/src/main/java/com/barf/runtime/OfflineMode.java`

**New class:**
```java
package com.barf.runtime;

public class OfflineMode {
    private boolean isOffline = false;
    private String lastConnectedDesktop;
    
    public void enableOfflineMode(String desktopIp) {
        isOffline = true;
        lastConnectedDesktop = desktopIp;
        // Store local WASM module if available
    }
    
    public boolean isOffline() {
        return isOffline;
    }
    
    public String getLastConnectedDesktop() {
        return lastConnectedDesktop;
    }
    
    public void disableOfflineMode() {
        isOffline = false;
    }
}
```

**Update MainActivity:**
```java
@Override
public void onResume() {
    super.onResume();
    
    if (!wireGuardManager.isConnected()) {
        // Offline mode - use local WASM if available
        if (offlineMode.isOffline()) {
            loadLocalWasmModule();
        } else {
            showOfflineWarning();
        }
    }
}
```

**Validation:**
- App works without desktop connection
- Local WASM execution available
- Clear indication of offline status

---

## PHASE 9 — Testing and Validation

### Step 9.1 — Create test cases for pairing flow

**What:** Write unit tests for QR parsing, WireGuard connection, and pairing logic.

**File:** `android/src/test/java/com/barf/pairing/PairingActivityTest.java`

**Test cases:**
```java
@Test
public void testQrCodeParsing() {
    String qrData = "barf://pair?ip=192.168.1.100&key=abc123&port=9876&wg_ip=10.0.0.1&wg_key=xyz789&wg_client=10.0.0.2&wg_port=51820";
    
    PairingInfo info = PairingActivity.parseQrCode(qrData);
    
    assertEquals("192.168.1.100", info.getDesktopIp());
    assertEquals("abc123", info.getPairKey());
    assertEquals(9876, info.getPort());
    assertEquals("10.0.0.1", info.getWgServerIp());
    assertEquals("xyz789", info.getWgPublicKey());
    assertEquals("10.0.0.2", info.getWgClientIp());
    assertEquals(51820, info.getWgPort());
}

@Test
public void testWireGuardConnection() {
    WireGuardManager manager = new WireGuardManager(context);
    Config config = manager.buildConfig("10.0.0.1", "publicKey", "10.0.0.2");
    
    assertNotNull(config);
    // Verify config contains correct endpoints
}
```

**Validation:**
- All tests pass
- Edge cases handled (invalid QR, missing parameters)
- Error cases covered

---

### Step 9.2 — Create integration tests

**What:** Test end-to-end pairing flow with mock desktop.

**File:** `android/src/androidTest/java/com/barf/pairing/PairingIntegrationTest.java`

**Test setup:**
```java
@RunWith(AndroidJUnit4.class)
public class PairingIntegrationTest {
    private MockWebServer mockDesktop;
    
    @Before
    public void setup() {
        mockDesktop = new MockWebServer();
        mockDesktop.start(9876);
    }
    
    @Test
    public void testPairingFlow() {
        // Mock pairing endpoint
        mockDesktop.enqueue(new MockResponse()
            .setBody("{\"success\": true}")
            .setHeader("Content-Type", "application/json"));
        
        // Test pairing
        PairingActivity activity = launchActivity(PairingActivity.class);
        
        // Simulate QR scan
        String qrData = "barf://pair?ip=" + mockDesktop.getHostName() + "&key=test123&port=9876";
        activity.onQrCodeScanned(qrData);
        
        // Verify pairing request was made
        RecordedRequest request = mockDesktop.takeRequest();
        assertEquals("/api/phone-here", request.getPath());
    }
    
    @After
    public void teardown() {
        mockDesktop.shutdown();
    }
}
```

**Validation:**
- Integration tests pass
- Mock desktop responds correctly
- Error scenarios tested

---

### Step 9.3 — Create UI tests

**What:** Test UI interactions and transitions.

**File:** `android/src/androidTest/java/com/barf/ui/UiTests.java`

**Test cases:**
```java
@Test
public void testPairingScreenDisplayed() {
    ActivityScenario< PairingActivity> scenario = ActivityScenario.launch(PairingActivity.class);
    
    scenario.onActivity(activity -> {
        // Verify QR scanner is visible
        View qrScanner = activity.findViewById(R.id.qr_scanner);
        assertNotNull(qrScanner);
        assertTrue(qrScanner.isDisplayed());
        
        // Verify pairing instructions are shown
        TextView instructions = activity.findViewById(R.id.instructions);
        assertNotNull(instructions);
        assertTrue(instructions.getText().toString().contains("Scan QR code"));
    });
}

@Test
public void testConnectionStatusUpdates() {
    ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class);
    
    scenario.onActivity(activity -> {
        // Verify connection status indicator
        TextView status = activity.findViewById(R.id.connection_status);
        assertNotNull(status);
        
        // Simulate connection
        activity.onWireGuardConnected();
        assertEquals("Connected", status.getText().toString());
        
        // Simulate disconnection
        activity.onWireGuardDisconnected();
        assertEquals("Disconnected", status.getText().toString());
    });
}
```

**Validation:**
- UI tests pass
- Screen transitions work correctly
- Status updates reflected in UI

---

### Step 9.4 — Performance testing

**What:** Test app performance under various conditions.

**Test scenarios:**
1. Pairing speed (time from QR scan to connected)
2. WireGuard connection stability
3. Memory usage during extended operation
4. Battery impact
5. Network bandwidth usage

**Tools:**
- Android Profiler for memory and CPU
- Battery Historian for battery impact
- Network Profiler for bandwidth

**Validation:**
- Pairing completes in < 5 seconds
- WireGuard connection stable for 2+ hours
- Memory usage stays under 200MB
- Battery drain < 5% per hour in background
- Network usage minimal (heartbeat only)

---

### Step 9.5 — Create test documentation

**What:** Document test procedures and expected results.

**File:** `docs/TESTING.md`

**Content:**
```markdown
# Testing Guide

## Unit Tests
Run unit tests:
```bash
./gradlew :android:testDebugUnitTest
```

## Integration Tests
Run integration tests:
```bash
./gradlew :android:connectedDebugAndroidTest
```

## Manual Testing

### Pairing Flow
1. Install APK on phone
2. Open app → should show QR scanner
3. Open desktop app → click "Pair with Phone"
4. Scan QR code from desktop
5. Verify connection established
6. Verify main app loads

### Offline Mode
1. Pair with desktop
2. Disconnect from network
3. Verify app works in offline mode
4. Verify local WASM execution

### Error Handling
1. Scan invalid QR code → verify error message
2. Disconnect during pairing → verify retry option
3. Lose connection → verify reconnection attempt

## Performance Testing
- Use Android Profiler to monitor memory
- Test for 2+ hours continuous operation
- Monitor battery usage
```

**Validation:**
- Test documentation complete
- All test procedures documented
- Expected results defined

---

## Summary: Phases at a glance

| Phase | What | Priority | Validation Gate |
|-------|------|----------|-----------------|
| 6 | Fix 16KB ELF alignment | High | All .so files show 16KB alignment |
| 7 | QR pairing with WireGuard | High | Pairing works over WireGuard tunnel |
| 8 | Smooth app flows and fix bugs | Medium | App stable, error handling complete |
| 9 | Testing and validation | Medium | All tests pass, performance acceptable |

**Total: ~20-25 changes across 4 phases.**

---

## Implementation Order

1. **Phase 6 (16KB alignment)** — Fix critical compatibility issue first
2. **Phase 7 (QR pairing with WireGuard)** — Implement new pairing flow
3. **Phase 8 (App smoothing)** — Fix bugs and add polish
4. **Phase 9 (Testing)** — Validate everything works

---

## Success Criteria

- [ ] App passes 16KB alignment check on Android 15+ devices
- [ ] QR pairing works with WireGuard tunnel
- [ ] App flow: QR scan → connect → main app
- [ ] Connection persists across app restarts
- [ ] Error messages are clear and actionable
- [ ] Loading states shown for all operations
- [ ] Offline mode works without desktop
- [ ] All unit and integration tests pass
- [ ] Performance meets requirements (pairing < 5s, memory < 200MB)
