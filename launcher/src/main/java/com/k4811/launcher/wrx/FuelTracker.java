package com.k4811.launcher.wrx;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/**
 * Calculador de combustible y rendimiento (km/L) para el Subaru WRX.
 * Guarda el odómetro en cada llenado y calcula el rendimiento real entre cargas.
 */
public final class FuelTracker {
    private static final Locale ES = new Locale("es", "CL");

    private FuelTracker() {
    }

    public static String recordFillUp(Context c, double currentOdo, float liters) {
        if (liters <= 0) return "Ingresa una cantidad válida de litros.";
        SharedPreferences p = WrxPrefs.get(c);
        double lastOdo = Double.longBitsToDouble(p.getLong(WrxPrefs.FUEL_LAST_ODO, Double.doubleToLongBits(Double.NaN)));

        p.edit()
            .putLong(WrxPrefs.FUEL_LAST_ODO, Double.doubleToLongBits(currentOdo))
            .putFloat(WrxPrefs.FUEL_LAST_LITERS, liters)
            .apply();

        if (Double.isNaN(lastOdo) || currentOdo <= lastOdo) {
            return String.format(ES, "Carga de %.1f L registrada en %.0f km. En tu próxima carga calcularemos el consumo real.",
                liters, currentOdo);
        }

        double km = currentOdo - lastOdo;
        float kmL = (float) (km / liters);
        p.edit().putFloat(WrxPrefs.FUEL_LAST_KM_L, kmL).apply();

        return String.format(ES, "¡Rendimiento: %.1f km/L!\nRecorriste %.0f km con %.1f L.", kmL, km, liters);
    }

    public static String onRefuel(Context c, float liters) {
        double odo = CarState.odometerKm;
        if (Double.isNaN(odo)) {
            odo = CarState.tripKm;
        }
        return recordFillUp(c, odo, liters);
    }

    public static float getLastKmPerLiter(Context c) {
        return WrxPrefs.get(c).getFloat(WrxPrefs.FUEL_LAST_KM_L, Float.NaN);
    }

    public static String getSummary(Context c) {
        SharedPreferences p = WrxPrefs.get(c);
        float kmL = p.getFloat(WrxPrefs.FUEL_LAST_KM_L, Float.NaN);
        double lastOdo = Double.longBitsToDouble(p.getLong(WrxPrefs.FUEL_LAST_ODO, Double.doubleToLongBits(Double.NaN)));
        float liters = p.getFloat(WrxPrefs.FUEL_LAST_LITERS, Float.NaN);

        if (Float.isNaN(kmL)) {
            if (!Double.isNaN(lastOdo)) {
                return String.format(ES, "Última carga: %.1f L en %.0f km (esperando siguiente llenado)", liters, lastOdo);
            }
            return "Sin registros de combustible todavía.";
        }
        return String.format(ES, "Último rendimiento: %.1f km/L (%.1f L en %.0f km)", kmL, liters, lastOdo);
    }
}
