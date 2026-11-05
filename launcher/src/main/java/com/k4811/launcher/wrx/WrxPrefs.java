package com.k4811.launcher.wrx;

import android.content.Context;
import android.content.SharedPreferences;

/** Opciones de la pantalla WRX (solo las usa el launcher, por eso van en su SharedPreferences). */
public final class WrxPrefs {
    private WrxPrefs() {
    }

    public static final String FILE = "wrx";

    /** 0 = automático (faros), 1 = siempre día, 2 = siempre noche. */
    public static final String THEME = "theme_mode";
    public static final String ALERT_DOORS = "alert_doors";
    public static final String ALERT_HANDBRAKE = "alert_handbrake";
    public static final String ALERT_LIGHTS = "alert_lights";
    public static final String ALERT_BATTERY = "alert_battery";
    public static final String ALERT_HOT = "alert_hot";
    public static final String ALERT_MAINTENANCE = "alert_maintenance";
    public static final String ALERT_DVR = "alert_dvr";
    public static final String SPEED_VOLUME = "speed_volume";
    public static final String AUTO_FRONT_CAM = "auto_front_cam";
    public static final String SPORT_MODE = "sport_mode";
    public static final String CAM_BUBBLE = "cam_bubble";
    public static final String BEST_0_100 = "best_0_100";
    public static final String BEST_0_60 = "best_0_60";
    public static final String FUEL_LAST_ODO = "fuel_last_odo";
    public static final String FUEL_LAST_LITERS = "fuel_last_liters";
    public static final String FUEL_LAST_KM_L = "fuel_last_km_l";
    public static final String DOCK = "dock";
    public static final String CAR_IMAGE_MODE = "car_image_mode";

    public static final int CAR_MODE_FAMILY_DYNAMIC = 0; // Familia WRX Dinámica (tranquilo detenido / rápido en marcha)
    public static final int CAR_MODE_ORIGINAL_3D = 1;     // Subaru WRX 3D Original
    public static final int CAR_MODE_FAMILY_STOPPED = 2;  // Familia tranquila fija
    public static final int CAR_MODE_FAMILY_MOVING = 3;   // Familia rápida fija
    public static final int CAR_MODE_CUSTOM_SD = 4;       // /sdcard/K4811/wrx_auto.png

    public static int getCarImageMode(Context c) {
        return get(c).getInt(CAR_IMAGE_MODE, CAR_MODE_FAMILY_DYNAMIC);
    }

    public static void setCarImageMode(Context c, int mode) {
        get(c).edit().putInt(CAR_IMAGE_MODE, mode).apply();
    }

    public static SharedPreferences get(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static boolean on(Context c, String key, boolean def) {
        return get(c).getBoolean(key, def);
    }

    public static void set(Context c, String key, boolean v) {
        get(c).edit().putBoolean(key, v).apply();
    }

    public static int themeMode(Context c) {
        return get(c).getInt(THEME, 0);
    }

    public static void setThemeMode(Context c, int mode) {
        get(c).edit().putInt(THEME, mode).apply();
    }

    public static boolean isSportMode(Context c) {
        return on(c, SPORT_MODE, true);
    }

    public static void setSportMode(Context c, boolean v) {
        set(c, SPORT_MODE, v);
    }

    public static float getBest0to100(Context c) {
        return get(c).getFloat(BEST_0_100, Float.NaN);
    }

    public static float getBest0to60(Context c) {
        return get(c).getFloat(BEST_0_60, Float.NaN);
    }
}
