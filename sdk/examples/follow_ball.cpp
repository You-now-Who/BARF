// follow_ball.cpp — Example BARF vision script
//
// Behavior: If a sports ball is detected, drive toward it.
//           If lost, rotate in place to search.
//
// Compile: clang --target=wasm32 -O3 -c follow_ball.cpp -o follow_ball.wasm
// Deploy: Upload via BARF desktop app → "Compile & Deploy"

#include "barf.h"

// COCO class indices for ball-like objects
#define CLASS_SPORTS_BALL 32
#define CLASS_FRISBEE     29
#define CLASS_KITE        33

static float g_last_ball_x = -1;
static int   g_lost_frames = 0;
static bool  g_initialized = false;

// Image dimensions — adjust to match your camera
static const float IMG_W = 640.0f;
static const float IMG_H = 480.0f;
static const float IMG_CX = IMG_W / 2.0f;
static const float IMG_CY = IMG_H / 2.0f;

// Dead zone: if ball is within this many pixels of center, drive straight
static const float DEAD_ZONE_X = 60.0f;

void setup(void) {
    log_info("Ball follower started. Looking for sports balls, frisbees, kites...");
}

static bool is_ball(const yolo_detection_t* d) {
    return d->label == CLASS_SPORTS_BALL
        || d->label == CLASS_FRISBEE
        || d->label == CLASS_KITE;
}

void on_frame(yolo_result_t detections) {
    // ── Find the best ball candidate ──────────────────
    const yolo_detection_t* best = NULL;
    float best_score = 0.0f;
    float best_size  = 0.0f;

    for (int i = 0; i < detections.count; i++) {
        const yolo_detection_t* d = &detections.detections[i];
        if (is_ball(d) && d->score > best_score) {
            best_score = d->score;
            best = d;
            best_size = d->w * d->h;
        }
    }

    // ── No ball visible — search by rotating ──────────
    if (best == NULL || best_score < 0.3f) {
        g_lost_frames++;

        if (g_lost_frames > 20) {
            // Start searching
            const char* dir = (g_lost_frames / 40) % 2 == 0 ? "left" : "right";
            log_info("Searching...");
            rotate(dir, 0.3);
        }
        return;
    }

    g_lost_frames = 0;
    g_last_ball_x = best->x;

    float dx = best->x - IMG_CX;  // positive = ball is right of center
    float dy = best->y - IMG_CY;  // positive = ball is below center

    // ── Decide movement ────────────────────────────────
    float base_speed = best_size / (IMG_W * IMG_H);  // faster when closer
    if (base_speed > 0.8f) base_speed = 0.8f;
    if (base_speed < 0.3f) base_speed = 0.3f;

    if (dx > DEAD_ZONE_X) {
        // Ball is right — strafe right while going forward
        move("right", base_speed * 0.5f);
        sleep_ms(50);
        move("forward", base_speed);
    } else if (dx < -DEAD_ZONE_X) {
        // Ball is left — strafe left while going forward
        move("left", base_speed * 0.5f);
        sleep_ms(50);
        move("forward", base_speed);
    } else {
        // Ball is centered — drive straight
        move("forward", base_speed);
    }

    // Log occasionally
    static int frame_count = 0;
    if (++frame_count % 30 == 0) {
        char buf[128];
        snprintf(buf, sizeof(buf),
            "Ball at (%.0f,%.0f) size=%.0f score=%.2f speed=%.2f",
            best->x, best->y, best_size, best_score, base_speed);
        log_info(buf);
    }
}
