package com.k4811.launcher.wrx;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/**
 * Modo Pista WRX: cronómetro automático 0 a 100 km/h y 0 a 60 km/h por GPS.
 * Se arma solo al detener el auto (0 km/h durante 1.5 s). Al acelerar arranca el reloj;
 * al tocar 60 y 100 km/h registra el tiempo exacto y guarda el récord personal.
 */
public final class SportTimer {

    public interface OnResult {
        void onResult(float timeSec, boolean isBestRecord);
    }

    private static boolean running;
    private static long stopStartMs;
    private static long launchStartMs;
    private static float t60;
    private static float t100;

    public static void init(Context c) {
        SharedPreferences p = WrxPrefs.get(c);
        CarState.best0to100Sec = p.getFloat(WrxPrefs.BEST_0_100, Float.NaN);
        CarState.best0to60Sec = p.getFloat(WrxPrefs.BEST_0_60, Float.NaN);
    }

    public static void onSpeed(Context c, float speedKmh, long nowMs, OnResult callback) {
        if (!WrxPrefs.on(c, WrxPrefs.SPORT_MODE, true)) {
            CarState.sportArmed = false;
            running = false;
            return;
        }

        if (Float.isNaN(speedKmh)) return;

        // 1. Armado automático al detenerse
        if (speedKmh < 1.5f) {
            if (stopStartMs == 0) stopStartMs = nowMs;
            if (nowMs - stopStartMs >= 1500) {
                CarState.sportArmed = true;
            }
            if (running && nowMs - launchStartMs > 2000) {
                running = false;
            }
            return;
        }

        // 2. Detección de largada
        stopStartMs = 0;
        if (CarState.sportArmed && speedKmh >= 2.5f) {
            CarState.sportArmed = false;
            running = true;
            launchStartMs = nowMs;
            t60 = 0f;
            t100 = 0f;
            return;
        }

        // 3. Medición en carrera
        if (running) {
            long elapsed = nowMs - launchStartMs;
            if (speedKmh >= 60f && t60 == 0f) {
                t60 = elapsed / 1000f;
                CarState.last0to60Sec = t60;
                float best60 = CarState.best0to60Sec;
                if (Float.isNaN(best60) || t60 < best60) {
                    CarState.best0to60Sec = t60;
                    WrxPrefs.get(c).edit().putFloat(WrxPrefs.BEST_0_60, t60).apply();
                }
            }
            if (speedKmh >= 100f && t100 == 0f) {
                t100 = elapsed / 1000f;
                CarState.last0to100Sec = t100;
                running = false;
                float best100 = CarState.best0to100Sec;
                boolean newRecord = Float.isNaN(best100) || t100 < best100;
                if (newRecord) {
                    CarState.best0to100Sec = t100;
                    WrxPrefs.get(c).edit().putFloat(WrxPrefs.BEST_0_100, t100).apply();
                }
                if (callback != null) {
                    callback.onResult(t100, newRecord);
                }
            } else if (speedKmh < 15f && elapsed > 6000) {
                // Abortado (frenó antes de llegar a 100)
                running = false;
            } else if (elapsed > 35000) {
                // Tiempo límite
                running = false;
            }
        }
    }

    public static void resetRecords(Context c) {
        CarState.best0to100Sec = Float.NaN;
        CarState.best0to60Sec = Float.NaN;
        CarState.last0to100Sec = Float.NaN;
        CarState.last0to60Sec = Float.NaN;
        WrxPrefs.get(c).edit()
            .remove(WrxPrefs.BEST_0_100)
            .remove(WrxPrefs.BEST_0_60)
            .apply();
    }

    public static void resetBest(Context c) {
        resetRecords(c);
    }
}
