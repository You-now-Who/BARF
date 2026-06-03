package com.barf.pairing;

import android.content.Context;
import android.content.SharedPreferences;

public class PairingManager {
    private static final String PREFS_NAME = "barf_pairing";
    private static final String KEY_DESKTOP_IP = "desktop_ip";
    private static final String KEY_WG_SERVER_IP = "wg_server_ip";
    private static final String KEY_WG_PUBLIC_KEY = "wg_public_key";
    private static final String KEY_WG_CLIENT_IP = "wg_client_ip";
    private static final String KEY_WG_PORT = "wg_port";
    private static final String KEY_PAIRED = "is_paired";
    
    private SharedPreferences prefs;
    
    public PairingManager(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
    
    public void savePairing(String desktopIp, String wgServerIp, String wgPublicKey, String wgClientIp, int wgPort) {
        prefs.edit()
            .putString(KEY_DESKTOP_IP, desktopIp)
            .putString(KEY_WG_SERVER_IP, wgServerIp)
            .putString(KEY_WG_PUBLIC_KEY, wgPublicKey)
            .putString(KEY_WG_CLIENT_IP, wgClientIp)
            .putInt(KEY_WG_PORT, wgPort)
            .putBoolean(KEY_PAIRED, true)
            .apply();
    }
    
    public boolean isPaired() {
        return prefs.getBoolean(KEY_PAIRED, false);
    }
    
    public String getDesktopIp() {
        return prefs.getString(KEY_DESKTOP_IP, null);
    }
    
    public String getWgServerIp() {
        return prefs.getString(KEY_WG_SERVER_IP, null);
    }
    
    public String getWgPublicKey() {
        return prefs.getString(KEY_WG_PUBLIC_KEY, null);
    }
    
    public String getWgClientIp() {
        return prefs.getString(KEY_WG_CLIENT_IP, null);
    }
    
    public int getWgPort() {
        return prefs.getInt(KEY_WG_PORT, 51820);
    }
    
    public void clearPairing() {
        prefs.edit().clear().apply();
    }
}
