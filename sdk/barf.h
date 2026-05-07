// barf.h — Reference header for WASM vision scripts
//
// Include this header in your C++ vision script to get the BARF host API.
// The functions declared here are provided by the WAMR runtime on the phone.
// You don't link against anything — the host binds these at WASM instantiation.
//
// Compile with: clang --target=wasm32 -O3 -c myscript.cpp -o myscript.wasm

#pragma once
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// ── Robot control ──────────────────────────────────────

// Move the robot. direction: "forward", "backward", "left", "right"
// speed: 0.0 (stopped) to 1.0 (full speed)
// Returns 0 on success, -1 if serial link is down.
int move(const char* direction, double speed);

// Rotate the robot in place. direction: "left", "right"
int rotate(const char* direction, double speed);

// Stop all motors immediately.
void stop(void);

// Wait for N milliseconds. Returns early if stop() is called.
void sleep_ms(int ms);

// ── Vision ─────────────────────────────────────────────

// A single detection from YOLO or AprilTag
typedef struct {
    int    label;       // COCO class index (0=person, 1=bicycle, ...)
    float  x, y;        // center of bounding box (pixels)
    float  w, h;        // width and height of bounding box
    float  score;       // confidence 0.0 to 1.0
} yolo_detection_t;

typedef struct {
    int count;
    yolo_detection_t* detections;  // array of `count` detections
} yolo_result_t;

// Get current frame detections. Returns count of detected objects.
// The detections pointer is valid until the next call.
// Returns 0 if no detections or camera not ready.
int get_detections(yolo_result_t* out);

// ── Logging ────────────────────────────────────────────

// Log a message (shows in desktop serial monitor or Android logcat)
void log_info(const char* msg);
void log_warn(const char* msg);
void log_error(const char* msg);

// ── Serial passthrough ─────────────────────────────────

// Send raw bytes over USB-serial to ESP32 (advanced use)
void serial_write(const uint8_t* data, int length);

// Check how many bytes are available to read from ESP32
int serial_available(void);

// Read bytes from ESP32. Returns number of bytes actually read.
int serial_read(uint8_t* buffer, int max_length);

// ── Program lifecycle ──────────────────────────────────

// Called once when the WASM module is loaded (optional — implement if needed)
void setup(void);

// Called on every camera frame (~15-30 FPS). `detections` has current YOLO results.
// This is where your main logic goes. Motor commands sent here take effect immediately.
void on_frame(yolo_result_t detections);

#ifdef __cplusplus
}
#endif
