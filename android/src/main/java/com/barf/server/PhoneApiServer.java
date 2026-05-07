package com.barf.server;

import android.content.Context;
import android.util.Log;

import com.barf.SimpleWebSocketServer;
import com.barf.VideoStreamServer;
import com.barf.runtime.JsRuntime;
import com.barf.runtime.WasmRuntime;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import fi.iki.elonen.NanoHTTPD;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Slim HTTP/WebSocket API server for the BARF desktop companion app.
 * Replaces the old SimpleHttpServer (which served static files and MJPEG streams).
 *
 * Endpoints:
 *   POST /api/wasm       — deploy .wasm binary (stub)
 *   POST /api/js/run     — execute JS snippet
 *   POST /api/js/stop    — stop JS execution
 *   POST /api/serial     — write to serial (delegates to UsbSerialManager)
 *   GET  /api/status     — health, FPS, serial state
 *   WS   /api/events     — detection JSON push, robot state updates
 */
public class PhoneApiServer extends NanoHTTPD {
    private static final String TAG = "PhoneApiServer";

    private final Context context;
    private SimpleWebSocketServer webSocketServer;
    private VideoStreamServer videoStreamServer;
    private JsRuntime jsRuntime;
    private WasmRuntime wasmRuntime;
    private boolean isOnline = false;

    // Callback for robot/serial actions
    private ServerCallback callback;

    public interface ServerCallback {
        void onMove(String direction, float speed);
        void onRotate(String direction, float speed);
        void onStop();
        int getCameraFacing();
    }

    public PhoneApiServer(Context context, int port) {
        super(port);
        this.context = context;
        this.videoStreamServer = new VideoStreamServer();
        Log.i(TAG, "Phone API server created on port " + port);
    }

    public void setCallback(ServerCallback callback) {
        this.callback = callback;
    }

    public void setJsRuntime(JsRuntime jsRuntime) {
        this.jsRuntime = jsRuntime;
    }

    public void setWasmRuntime(WasmRuntime wasmRuntime) {
        this.wasmRuntime = wasmRuntime;
    }

    public VideoStreamServer getVideoStreamServer() {
        return videoStreamServer;
    }

    public SimpleWebSocketServer getWebSocketServer() {
        return webSocketServer;
    }

    public void start() {
        try {
            start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
            isOnline = true;
            Log.i(TAG, "HTTP server started on port " + getListeningPort());

            webSocketServer = new SimpleWebSocketServer(8081);
            webSocketServer.start();
            Log.i(TAG, "WebSocket server started on port 8081");

            videoStreamServer.start();
            Log.i(TAG, "Video stream server started");

        } catch (IOException e) {
            Log.e(TAG, "Failed to start server: " + e.getMessage());
            isOnline = false;
        }
    }

    public void stop() {
        isOnline = false;
        stop();

        if (webSocketServer != null) {
            webSocketServer.shutdown();
        }

        if (videoStreamServer != null) {
            videoStreamServer.stop();
        }

        Log.i(TAG, "Servers stopped");
    }

    public boolean isOnline() {
        return isOnline;
    }

    /**
     * Push detection JSON to JS/WASM runtimes and broadcast to WebSocket clients.
     */
    public void pushDetections(String detectionsJson) {
        if (detectionsJson == null) detectionsJson = "[]";

        if (jsRuntime != null) {
            jsRuntime.pushDetections(detectionsJson);
        }

        // Forward to WASM runtime if loaded
        if (wasmRuntime != null && wasmRuntime.isLoaded()) {
            wasmRuntime.onFrame(detectionsJson);
        }

        if (webSocketServer != null) {
            try {
                JsonObject msg = new JsonObject();
                msg.addProperty("type", "detections");
                msg.addProperty("timestamp", System.currentTimeMillis());
                msg.addProperty("detections", detectionsJson);
                webSocketServer.broadcast(msg.toString());
            } catch (Exception e) {
                Log.w(TAG, "Failed to broadcast detections: " + e.getMessage());
            }
        }
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();

        Log.d(TAG, method + " " + uri);

        try {
            if (uri.startsWith("/api/")) {
                return handleApi(session, uri, method);
            }
            return createJsonResponse(Response.Status.NOT_FOUND, errorJson("Not found: " + uri));
        } catch (Exception e) {
            Log.e(TAG, "Error handling request: " + e.getMessage(), e);
            return createJsonResponse(Response.Status.INTERNAL_ERROR, errorJson(e.getMessage()));
        }
    }

    private Response handleApi(IHTTPSession session, String uri, Method method) {
        switch (uri) {
            case "/api/status":
                return handleStatus();
            case "/api/js/run":
                if (method == Method.POST) return handleJsRun(session);
                break;
            case "/api/js/stop":
                if (method == Method.POST) return handleJsStop();
                break;
            case "/api/serial":
                if (method == Method.POST) return handleSerial(session);
                break;
        }
        return createJsonResponse(Response.Status.NOT_FOUND, errorJson("API endpoint not found: " + uri));
    }

    private Response handleStatus() {
        JsonObject status = new JsonObject();
        status.addProperty("server", "BARF Phone API");
        status.addProperty("status", "online");
        status.addProperty("timestamp", System.currentTimeMillis());
        status.addProperty("httpPort", getListeningPort());
        status.addProperty("wsPort", 8081);
        status.addProperty("jsRunning", jsRuntime != null && jsRuntime.isRunning());
        if (webSocketServer != null) {
            status.addProperty("wsClients", webSocketServer.getClientCount());
        }
        if (callback != null) {
            status.addProperty("cameraFacing", callback.getCameraFacing());
        }
        return createJsonResponse(Response.Status.OK, status.toString());
    }

    private Response handleJsRun(IHTTPSession session) {
        try {
            String body = getBody(session);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String script = json.has("script") ? json.get("script").getAsString() : "";

            if (script.isEmpty()) {
                return createJsonResponse(Response.Status.BAD_REQUEST, errorJson("No script provided"));
            }

            if (jsRuntime == null) {
                return createJsonResponse(Response.Status.INTERNAL_ERROR, errorJson("JS runtime not initialized"));
            }

            if (jsRuntime.isRunning()) {
                return createJsonResponse(Response.Status.BAD_REQUEST, errorJson("Script already running"));
            }

            jsRuntime.execute(script);

            JsonObject resp = new JsonObject();
            resp.addProperty("success", true);
            resp.addProperty("message", "Script started");
            return createJsonResponse(Response.Status.OK, resp.toString());

        } catch (Exception e) {
            return createJsonResponse(Response.Status.BAD_REQUEST, errorJson(e.getMessage()));
        }
    }

    private Response handleJsStop() {
        if (jsRuntime != null && jsRuntime.isRunning()) {
            jsRuntime.stop();
        }
        JsonObject resp = new JsonObject();
        resp.addProperty("success", true);
        resp.addProperty("message", "Script stopped");
        return createJsonResponse(Response.Status.OK, resp.toString());
    }

    private Response handleSerial(IHTTPSession session) {
        // Serial write endpoint — in Phase 2.4 the UsbSerialManager handles this.
        // For now, echo the request.
        try {
            String body = getBody(session);
            JsonObject resp = new JsonObject();
            resp.addProperty("success", true);
            resp.addProperty("echo", body);
            return createJsonResponse(Response.Status.OK, resp.toString());
        } catch (Exception e) {
            return createJsonResponse(Response.Status.BAD_REQUEST, errorJson(e.getMessage()));
        }
    }

    private String getBody(IHTTPSession session) throws IOException {
        Map<String, String> files = new HashMap<>();
        try {
            session.parseBody(files);
        } catch (ResponseException e) {
            throw new IOException("Failed to parse body", e);
        }
        String body = files.get("postData");
        return body != null ? body : "";
    }

    private String errorJson(String message) {
        JsonObject err = new JsonObject();
        err.addProperty("success", false);
        err.addProperty("error", message);
        err.addProperty("timestamp", System.currentTimeMillis());
        return err.toString();
    }

    private Response createJsonResponse(Response.Status status, String json) {
        Response resp = newFixedLengthResponse(status, "application/json", json);
        resp.addHeader("Access-Control-Allow-Origin", "*");
        resp.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        resp.addHeader("Access-Control-Allow-Headers", "Content-Type");
        return resp;
    }
}
