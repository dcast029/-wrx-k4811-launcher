package com.k4811.launcher.wrx;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Calculador de combustible y rendimiento (km/L) para el Subaru WRX.
 * Guarda cada carga en /sdcard/K4811/bencina.json para que sobreviva a reinstalaciones.
 */
public final class FuelTracker {
    private static final String TAG = "FuelTracker";
    private static final Locale ES = new Locale("es", "CL");
    private static final SimpleDateFormat FMT_DATE = new SimpleDateFormat("dd/MM/yyyy", ES);
    private static final SimpleDateFormat FMT_TIME = new SimpleDateFormat("HH:mm", ES);

    public static class FillUp {
        public long id;
        public String dateStr;
        public String timeStr;
        public double odo;
        public float liters;
        public int cost; // CLP
        public int octane; // 95, 97
        public float kmL; // NaN si no se calculó
        public boolean fullTank;
        public double distanceKm; // km recorridos desde la carga anterior

        public JSONObject toJson() {
            try {
                JSONObject o = new JSONObject();
                o.put("id", id);
                o.put("date", dateStr);
                o.put("time", timeStr);
                o.put("odo", odo);
                o.put("liters", liters);
                o.put("cost", cost);
                o.put("octane", octane);
                o.put("kmL", Float.isNaN(kmL) ? -1 : kmL);
                o.put("fullTank", fullTank);
                o.put("dist", distanceKm);
                return o;
            } catch (Exception e) {
                return null;
            }
        }

        public static FillUp fromJson(JSONObject o) {
            try {
                FillUp f = new FillUp();
                f.id = o.optLong("id", System.currentTimeMillis());
                f.dateStr = o.optString("date", "");
                f.timeStr = o.optString("time", "");
                f.odo = o.optDouble("odo", 0);
                f.liters = (float) o.optDouble("liters", 0);
                f.cost = o.optInt("cost", 0);
                f.octane = o.optInt("octane", 97);
                double k = o.optDouble("kmL", -1);
                f.kmL = k <= 0 ? Float.NaN : (float) k;
                f.fullTank = o.optBoolean("fullTank", true);
                f.distanceKm = o.optDouble("dist", 0);
                return f;
            } catch (Exception e) {
                return null;
            }
        }
    }

    private FuelTracker() {
    }

    private static File getStorageFile() {
        File dir = new File(Environment.getExternalStorageDirectory(), "K4811");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "bencina.json");
    }

    public static synchronized List<FillUp> loadHistory(Context c) {
        List<FillUp> list = new ArrayList<>();
        File file = getStorageFile();
        String json = null;

        if (file.exists() && file.length() > 0) {
            try (FileInputStream in = new FileInputStream(file)) {
                byte[] b = new byte[(int) file.length()];
                in.read(b);
                json = new String(b, "UTF-8");
            } catch (Exception e) {
                Log.w(TAG, "Error leyendo bencina.json de archivo: " + e.getMessage());
            }
        }

        if (json == null || json.trim().isEmpty()) {
            json = WrxPrefs.get(c).getString("fuel_history_backup", "[]");
        }

        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                FillUp f = FillUp.fromJson(arr.getJSONObject(i));
                if (f != null) list.add(f);
            }
        } catch (Exception e) {
            Log.w(TAG, "Error parseando historial de bencina: " + e.getMessage());
        }

        // Ordenar del más nuevo al más antiguo
        Collections.sort(list, (a, b) -> Long.compare(b.id, a.id));
        return list;
    }

    public static synchronized void saveHistory(Context c, List<FillUp> list) {
        try {
            JSONArray arr = new JSONArray();
            for (FillUp f : list) {
                JSONObject o = f.toJson();
                if (o != null) arr.put(o);
            }
            String raw = arr.toString();

            // 1. Guardar en /sdcard/K4811/bencina.json
            File file = getStorageFile();
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(raw.getBytes("UTF-8"));
            }

            // 2. Backup en SharedPreferences
            WrxPrefs.get(c).edit().putString("fuel_history_backup", raw).apply();
        } catch (Exception e) {
            Log.e(TAG, "Error guardando historial de bencina", e);
        }
    }

    /**
     * Registra una nueva carga de combustible completa y calcula el rendimiento real.
     */
    public static FillUp addFillUp(Context c, double currentOdo, float liters, int cost, int octane, boolean fullTank) {
        if (liters <= 0) return null;
        List<FillUp> history = loadHistory(c);

        FillUp entry = new FillUp();
        entry.id = System.currentTimeMillis();
        Date now = new Date(entry.id);
        entry.dateStr = FMT_DATE.format(now);
        entry.timeStr = FMT_TIME.format(now);
        entry.odo = currentOdo;
        entry.liters = liters;
        entry.cost = cost;
        entry.octane = octane;
        entry.fullTank = fullTank;
        entry.kmL = Float.NaN;
        entry.distanceKm = 0;

        // Calcular rendimiento con respecto a la carga previa
        if (!history.isEmpty()) {
            FillUp prev = history.get(0); // el más reciente
            if (prev.odo > 0 && currentOdo > prev.odo) {
                double km = currentOdo - prev.odo;
                entry.distanceKm = km;
                if (fullTank && liters > 0) {
                    entry.kmL = (float) (km / liters);
                }
            }
        }

        history.add(0, entry);
        saveHistory(c, history);

        // Actualizar SharedPreferences para widgets rápidos
        SharedPreferences.Editor ed = WrxPrefs.get(c).edit();
        ed.putLong(WrxPrefs.FUEL_LAST_ODO, Double.doubleToLongBits(currentOdo));
        ed.putFloat(WrxPrefs.FUEL_LAST_LITERS, liters);
        if (!Float.isNaN(entry.kmL)) {
            ed.putFloat(WrxPrefs.FUEL_LAST_KM_L, entry.kmL);
        }
        ed.apply();

        return entry;
    }

    public static synchronized void deleteFillUp(Context c, long id) {
        List<FillUp> history = loadHistory(c);
        boolean removed = false;
        for (int i = 0; i < history.size(); i++) {
            if (history.get(i).id == id) {
                history.remove(i);
                removed = true;
                break;
            }
        }
        if (removed) {
            saveHistory(c, history);
            if (!history.isEmpty()) {
                FillUp last = history.get(0);
                SharedPreferences.Editor ed = WrxPrefs.get(c).edit();
                ed.putLong(WrxPrefs.FUEL_LAST_ODO, Double.doubleToLongBits(last.odo));
                ed.putFloat(WrxPrefs.FUEL_LAST_LITERS, last.liters);
                if (!Float.isNaN(last.kmL)) ed.putFloat(WrxPrefs.FUEL_LAST_KM_L, last.kmL);
                ed.apply();
            }
        }
    }

    public static float getAverageKmL(Context c) {
        List<FillUp> history = loadHistory(c);
        float sumKmL = 0;
        int count = 0;
        for (FillUp f : history) {
            if (!Float.isNaN(f.kmL) && f.kmL > 0) {
                sumKmL += f.kmL;
                count++;
            }
        }
        return count == 0 ? Float.NaN : (sumKmL / count);
    }

    public static float getLastKmPerLiter(Context c) {
        List<FillUp> history = loadHistory(c);
        for (FillUp f : history) {
            if (!Float.isNaN(f.kmL) && f.kmL > 0) return f.kmL;
        }
        return WrxPrefs.get(c).getFloat(WrxPrefs.FUEL_LAST_KM_L, Float.NaN);
    }

    public static FillUp getLastFillUp(Context c) {
        List<FillUp> history = loadHistory(c);
        return history.isEmpty() ? null : history.get(0);
    }

    public static String onRefuel(Context c, float liters) {
        double odo = CarState.odometerKm;
        if (Double.isNaN(odo) || odo <= 0) odo = CarState.tripKm;
        FillUp f = addFillUp(c, odo, liters, 0, 97, true);
        if (f == null) return "No se pudo registrar la carga.";
        if (Float.isNaN(f.kmL)) {
            return String.format(ES, "Carga de %.1f L registrada a %.0f km.\nEn tu próxima carga calcularemos el consumo exacto.",
                f.liters, f.odo);
        }
        return String.format(ES, "¡Rendimiento: %.1f km/L!\nRecorriste %.0f km con %.1f L.", f.kmL, f.distanceKm, f.liters);
    }

    public static String getSummary(Context c) {
        FillUp last = getLastFillUp(c);
        if (last == null) return "Sin registros de combustible todavía.";
        if (Float.isNaN(last.kmL)) {
            return String.format(ES, "Última carga: %.1f L en %.0f km\n(esperando siguiente llenado para calcular km/L)",
                last.liters, last.odo);
        }
        return String.format(ES, "Último rendimiento: %.1f km/L\n(%.1f L en %.0f km · %s)",
            last.kmL, last.liters, last.odo, last.dateStr);
    }
}
