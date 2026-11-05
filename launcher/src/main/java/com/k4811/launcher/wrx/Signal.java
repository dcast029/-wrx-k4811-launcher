package com.k4811.launcher.wrx;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.provider.Settings;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Una señal del auto leída de Settings.System (donde la MCU de la NWD deja sus estados): una clave,
 * un bit (máscara) y si está "activa" con el bit encendido o apagado. No sabemos de antemano en qué
 * clave/bit va el freno de mano o la reversa en este auto: se calibra en el Explorador CAN
 * (poner/quitar el freno y ver qué cambia).
 */
public final class Signal {

    public static final String HANDBRAKE = "handbrake";
    public static final String REVERSE = "reverse";

    public final String key;
    public final int mask;
    public final boolean activeWhenSet;

    public Signal(String key, int mask, boolean activeWhenSet) {
        this.key = key;
        this.mask = mask;
        this.activeWhenSet = activeWhenSet;
    }

    /** true = señal activa (freno puesto, reversa puesta); null si no se puede leer. */
    public Boolean read(Context c) {
        String raw;
        try {
            raw = Settings.System.getString(c.getContentResolver(), key);
        } catch (Exception e) {
            return null;
        }
        if (raw == null) return null;
        long v;
        try {
            v = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
        boolean set = mask == 0 ? v != 0 : (v & mask) != 0;
        return set == activeWhenSet;
    }

    public String describe() {
        return key + (mask == 0 ? "" : " bit 0x" + Integer.toHexString(mask)) + (activeWhenSet ? "" : " (invertido)");
    }

    // ------------------------------------------------------------------ guardado

    public static Signal load(Context c, String name) {
        SharedPreferences p = WrxPrefs.get(c);
        String key = p.getString("sig_" + name + "_key", null);
        if (key == null) {
            if (HANDBRAKE.equals(name)) return new Signal("mcu_hand_break", 0, true);
            if (REVERSE.equals(name)) return new Signal("mcu_backcar_state", 0, true);
            return null;
        }
        return new Signal(key, p.getInt("sig_" + name + "_mask", 0), p.getBoolean("sig_" + name + "_set", true));
    }

    public static void save(Context c, String name, Signal s) {
        SharedPreferences.Editor e = WrxPrefs.get(c).edit();
        if (s == null) {
            e.remove("sig_" + name + "_key").remove("sig_" + name + "_mask").remove("sig_" + name + "_set");
        } else {
            e.putString("sig_" + name + "_key", s.key).putInt("sig_" + name + "_mask", s.mask)
                .putBoolean("sig_" + name + "_set", s.activeWhenSet);
        }
        e.apply();
    }

    // ------------------------------------------------------------------ foto de Settings.System

    /** Todas las claves de Settings.System con su valor (para el explorador y la calibración). */
    public static Map<String, String> snapshot(Context c) {
        Map<String, String> out = new LinkedHashMap<>();
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(Settings.System.CONTENT_URI,
                new String[]{"name", "value"}, null, null, "name");
            if (cur != null) {
                while (cur.moveToNext()) out.put(cur.getString(0), cur.getString(1));
            }
        } catch (Exception ignored) {
        } finally {
            if (cur != null) cur.close();
        }
        return out;
    }

    /** Valor numérico o null. */
    public static Long number(String raw) {
        if (raw == null) return null;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
