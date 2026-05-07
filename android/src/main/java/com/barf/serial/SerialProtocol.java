package com.barf.serial;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Serial protocol helpers for JSON-newline-delimited communication with ESP32.
 * Wire format: {"m":[255,0,-128,0]} for motor commands, {"s":[142,138,0,0]} for sensor data.
 */
public class SerialProtocol {
    private static final Gson gson = new Gson();

    private SerialProtocol() {}

    /**
     * Build motor command JSON: {"m":[fl, fr, bl, br]}
     */
    public static String motorCommand(int[] speeds) {
        JsonObject msg = new JsonObject();
        JsonArray arr = new JsonArray();
        for (int s : speeds) arr.add(s);
        msg.add("m", arr);
        return msg.toString() + "\n";
    }

    /**
     * Build heartbeat ping: {"c":"ping"}
     */
    public static String ping() {
        return "{\"c\":\"ping\"}\n";
    }

    /**
     * Parse a sensor data message: {"s":[v1,v2,v3,v4]}
     */
    public static SensorData parseSensorData(String line) {
        try {
            JsonObject obj = JsonParser.parseString(line).getAsJsonObject();
            if (obj.has("s")) {
                JsonArray arr = obj.getAsJsonArray("s");
                int[] values = gson.fromJson(arr, int[].class);
                return new SensorData(values);
            }
            if (obj.has("c") && "pong".equals(obj.get("c").getAsString())) {
                return new SensorData("pong");
            }
        } catch (Exception ignored) {}
        return null;
    }

    public static class SensorData {
        public final int[] values;
        public final String command;
        public final boolean isPong;

        public SensorData(int[] values) {
            this.values = values;
            this.command = null;
            this.isPong = false;
        }

        public SensorData(String command) {
            this.values = null;
            this.command = command;
            this.isPong = "pong".equals(command);
        }
    }
}
