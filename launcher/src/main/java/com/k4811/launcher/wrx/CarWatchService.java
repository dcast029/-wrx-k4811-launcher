package com.k4811.launcher.wrx;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.k4811.common.Apps;
import com.k4811.common.home.GpsClient;
import com.k4811.common.system.ExternalApps;
import com.k4811.common.system.NwdSettings;
import com.k4811.common.ui.Ui;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.List;
import java.util.Locale;

/**
 * Vigila el auto mientras la radio está encendida, esté en la app que esté:
 * GPS (velocidad, viaje, odómetro, altitud), batería por CAN, faros, freno de mano y reversa (si están
 * calibrados), temperatura de la radio. Llena {@link CarState} y muestra avisos por encima de
 * cualquier app (freno de mano puesto andando, luces apagadas de noche, batería, calor,
 * mantenciones, modo pista 0-100). Lo inicia LauncherApp.
 */
public class CarWatchService extends Service {

    private static final String TAG = "K4811CarWatch";
    private static final String ACTION_QUERY_VOLTAGE = "com.nwd.action.ACTION_QUERY_CAN_BATTERY_VOLTAGE";
    private static final String ACTION_VOLTAGE = "com.nwd.action.ACTION_CAN_BATTERY_VOLTAGE";
    private static final String ACTION_HAND_BREAK = "com.nwd.action.ACTION_HAND_BREAK_STATE_CHANGE";
    private static final String ACTION_BACKCAR_SETUP = "com.android.action_update_backcar_setup";
    private static final String ACTION_CAN_DOOR_STATE_CHANGE_NWD = "com.nwd.ACTION_CAN_DOOR_STATE_CHANGE";
    private static final String ACTION_CAN_DOOR_STATE_CHANGE = "com.nwd.action.ACTION_CAN_DOOR_STATE_CHANGE";
    private static final String ACTION_CAN_DOOR_WINDOW = "com.nwd.ACTION_CAN_DOOR_WINDOW";
    private static final String EXTRA_VOLTAGE = "extra_voltage_value";
    private static final String EXTRA_CAN_DOOR_STATE = "extra_can_door_state";
    private static final String EXTRA_CAR_DOOR = "extra_car_door";
    private static final String KEY_FAN_SWITCH = "mcu_smart_fan_switch";
    public static final String PKG_FRONT_CAM = "com.k4811.camera.front";

    // Prioridades de los avisos (mayor = más importante).
    private static final int P_DVR = 3;
    private static final int P_DOOR = 6, P_HANDBRAKE = 5, P_LIGHTS = 4, P_CHARGE = 3, P_HOT = 2, P_MAINT = 1;

    private final Handler main = new Handler(Looper.getMainLooper());
    private GpsClient gps;
    private WindowManager wm;
    private float scale = 1f;
    private LinearLayout banner;
    private Glyph bannerIcon;
    private TextView bannerTitle, bannerText;
    private boolean bannerAttached;
    private int bannerPriority;
    private Runnable bannerTap;
    private ToneGenerator tone;

    private Signal handbrakeSig, reverseSig;
    private long lastFixAt;
    private double lastLat, lastLon;
    private boolean lastMoving;

    private long lightsSince, lightsCooldownUntil;
    private long lowVoltSince, chargeSince, voltCooldownUntil;
    private long hotSince, hotCooldownUntil;
    private boolean prevReverse;
    private long frontCamOpenedAt;
    private long lastBeepAt;
    private float voltsSmooth = Float.NaN;

    // Burbuja flotante de cámara frontal
    private FrameLayout camBubble;
    private WindowManager.LayoutParams bubbleLp;
    private boolean bubbleAttached;

    public static void start(Context c) {
        try {
            c.startService(new Intent(c, CarWatchService.class));
        } catch (Exception e) {
            Log.w(TAG, "startService: " + e.getMessage());
        }
    }

    /** Volver a leer la calibración del freno de mano / reversa (después del explorador). */
    public static void reloadSignals(Context c) {
        start(c);
    }

    private final BroadcastReceiver canReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String act = intent.getAction();
            if (ACTION_VOLTAGE.equals(act)) {
                int raw = intent.getIntExtra(EXTRA_VOLTAGE, 0);
                float v = Math.abs(raw) / 10f;
                if (v <= 0f) return;
                v = Math.max(8f, Math.min(18f, v));
                voltsSmooth = Float.isNaN(voltsSmooth) ? v : voltsSmooth * 0.6f + v * 0.4f;
                CarState.volts = v;
                CarState.changed();
            } else if (ACTION_HAND_BREAK.equals(act)) {
                boolean hb = intent.getBooleanExtra("extra_hand_break_state", false);
                boolean changed = !eq(hb, CarState.handbrake);
                CarState.handbrake = hb;
                if (changed) {
                    CarState.changed();
                    if (hb && !Float.isNaN(CarState.speedKmh) && CarState.speedKmh >= 5) {
                        showBanner(P_HANDBRAKE, Glyph.Kind.HANDBRAKE, WrxTheme.RED,
                            "¡Quita el freno de mano!", "Vas a " + Math.round(CarState.speedKmh) + " km/h con el freno puesto", 0, null);
                        beep();
                    } else if (!hb && bannerPriority == P_HANDBRAKE) {
                        hideBanner();
                    }
                }
            } else if (ACTION_BACKCAR_SETUP.equals(act)) {
                checkReverseState();
            } else if (ACTION_CAN_DOOR_STATE_CHANGE.equals(act) || ACTION_CAN_DOOR_STATE_CHANGE_NWD.equals(act)
                    || ACTION_CAN_DOOR_WINDOW.equals(act)) {
                handleDoorStateChange(intent);
            }
        }
    };

    private void handleDoorStateChange(Intent intent) {
        try {
            byte state = 0;
            if (intent.hasExtra(EXTRA_CAN_DOOR_STATE)) {
                try {
                    state = intent.getByteExtra(EXTRA_CAN_DOOR_STATE, (byte) 0);
                } catch (Exception e) {
                    state = (byte) intent.getIntExtra(EXTRA_CAN_DOOR_STATE, 0);
                }
                if (state == 0) {
                    state = (byte) intent.getIntExtra(EXTRA_CAN_DOOR_STATE, 0);
                }
            }
            boolean fl = (state & 0x40) != 0; // Front Left (Piloto)
            boolean fr = (state & 0x80) != 0; // Front Right (Copiloto)
            boolean rl = (state & 0x10) != 0; // Rear Left
            boolean rr = (state & 0x20) != 0; // Rear Right
            boolean trunk = (state & 0x08) != 0; // Trunk
            boolean hood = (state & 0x04) != 0; // Hood

            boolean[] bools = intent.getBooleanArrayExtra(EXTRA_CAR_DOOR);
            if (bools != null) {
                if (bools.length > 0) fl |= bools[0];
                if (bools.length > 1) fr |= bools[1];
                if (bools.length > 2) rl |= bools[2];
                if (bools.length > 3) rr |= bools[3];
                if (bools.length > 4) trunk |= bools[4];
                if (bools.length > 5) hood |= bools[5];
            }

            boolean changed = (fl != CarState.doorFrontLeft)
                || (fr != CarState.doorFrontRight)
                || (rl != CarState.doorBackLeft)
                || (rr != CarState.doorBackRight)
                || (trunk != CarState.doorTrunk)
                || (hood != CarState.doorHood);

            if (changed) {
                CarState.doorFrontLeft = fl;
                CarState.doorFrontRight = fr;
                CarState.doorBackLeft = rl;
                CarState.doorBackRight = rr;
                CarState.doorTrunk = trunk;
                CarState.doorHood = hood;
                CarState.changed();
                Log.d(TAG, "CAN Door State: FL=" + fl + " FR=" + fr + " RL=" + rl + " RR=" + rr + " Trunk=" + trunk + " Hood=" + hood);

                if (CarState.anyDoorOpen()) {
                    if (WrxPrefs.on(this, WrxPrefs.ALERT_DOORS, true)
                            && !Float.isNaN(CarState.speedKmh) && CarState.speedKmh >= 5) {
                        showBanner(P_DOOR, Glyph.Kind.DOOR, WrxTheme.RED,
                            "¡Puerta abierta!", CarState.openDoorsDescription() + " a " + Math.round(CarState.speedKmh) + " km/h", 0, null);
                        beep();
                    }
                } else if (bannerPriority == P_DOOR) {
                    hideBanner();
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "handleDoorStateChange: " + t.getMessage());
        }
    }

    private final ContentObserver canObserver = new ContentObserver(main) {
        @Override
        public void onChange(boolean selfChange) {
            checkHandbrakeState();
            checkReverseState();
        }
    };

    private void checkHandbrakeState() {
        try {
            int val = Settings.System.getInt(getContentResolver(), "mcu_hand_break", -1);
            if (val >= 0) {
                boolean hb = val != 0;
                boolean changed = !eq(hb, CarState.handbrake);
                CarState.handbrake = hb;
                if (changed) {
                    CarState.changed();
                    if (hb && !Float.isNaN(CarState.speedKmh) && CarState.speedKmh >= 5) {
                        showBanner(P_HANDBRAKE, Glyph.Kind.HANDBRAKE, WrxTheme.RED,
                            "¡Quita el freno de mano!", "Vas a " + Math.round(CarState.speedKmh) + " km/h con el freno puesto", 0, null);
                        beep();
                    } else if (!hb && bannerPriority == P_HANDBRAKE) {
                        hideBanner();
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void checkReverseState() {
        try {
            int val = Settings.System.getInt(getContentResolver(), "mcu_backcar_state", -1);
            if (val >= 0) {
                boolean rev = val == 1;
                boolean changed = !eq(rev, CarState.reverse);
                if (prevReverse && !rev) onReverseEnded();
                prevReverse = rev;
                CarState.reverse = rev;
                if (changed) CarState.changed();
            }
        } catch (Exception ignored) {
        }
    }

    private final ContentObserver headlightObserver = new ContentObserver(main) {
        @Override
        public void onChange(boolean selfChange) {
            boolean was = CarState.headlights;
            CarState.headlights = NwdSettings.headlightsOn(CarWatchService.this);
            if (was != CarState.headlights) {
                lightsCooldownUntil = 0;
                if (CarState.headlights && bannerPriority == P_LIGHTS) hideBanner();
                CarState.changed();
            }
        }
    };

    private final ContentObserver fanObserver = new ContentObserver(main) {
        @Override
        public void onChange(boolean selfChange) {
            CarState.fanOn = NwdSettings.getInt(CarWatchService.this, KEY_FAN_SWITCH, 0) != 0;
            CarState.changed();
        }
    };

    private final Runnable voltageTick = new Runnable() {
        @Override
        public void run() {
            try {
                sendBroadcast(new Intent(ACTION_QUERY_VOLTAGE));
            } catch (Exception ignored) {
            }
            main.postDelayed(this, 10000);
        }
    };

    /** Cada segundo: señales calibradas, temperatura (cada 5 s) y reglas de avisos. */
    private int ticks;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            ticks++;
            readSignals();
            if (ticks % 5 == 1) readTemperature();
            evaluateAlerts();
            main.postDelayed(this, 1000);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        Point size = new Point();
        wm.getDefaultDisplay().getRealSize(size);
        scale = Math.min(Math.max(size.x, size.y) / (float) Ui.DESIGN_WIDTH,
            Math.min(size.x, size.y) / (float) Ui.DESIGN_HEIGHT);
        WrxTheme.loadFonts(this);

        loadTripAndOdometer();
        SharedPreferences p = WrxPrefs.get(this);
        CarState.lat = Double.longBitsToDouble(p.getLong("last_lat", Double.doubleToLongBits(CarState.lat)));
        CarState.lon = Double.longBitsToDouble(p.getLong("last_lon", Double.doubleToLongBits(CarState.lon)));

        speedVolume = new SpeedVolume(this);
        gps = new GpsClient(this);
        gps.setListener(this::onGps);
        gps.start();
        CarState.gpsAvailable = gps.available();

        IntentFilter canFilter = new IntentFilter();
        canFilter.addAction(ACTION_VOLTAGE);
        canFilter.addAction(ACTION_HAND_BREAK);
        canFilter.addAction(ACTION_BACKCAR_SETUP);
        canFilter.addAction(ACTION_CAN_DOOR_STATE_CHANGE);
        canFilter.addAction(ACTION_CAN_DOOR_STATE_CHANGE_NWD);
        canFilter.addAction(ACTION_CAN_DOOR_WINDOW);
        registerReceiver(canReceiver, canFilter);

        getContentResolver().registerContentObserver(
            Settings.System.getUriFor(NwdSettings.KEY_NO_SOURCE_DEVICE), false, headlightObserver);
        getContentResolver().registerContentObserver(
            Settings.System.getUriFor(KEY_FAN_SWITCH), false, fanObserver);
        getContentResolver().registerContentObserver(
            Settings.System.getUriFor("mcu_hand_break"), false, canObserver);
        getContentResolver().registerContentObserver(
            Settings.System.getUriFor("mcu_backcar_state"), false, canObserver);
        headlightObserver.onChange(false);
        fanObserver.onChange(false);
        checkHandbrakeState();
        checkReverseState();
        loadSignals();
        ExternalApps.repairHd2DvrDisk(this);
        SportTimer.init(this);
        main.post(voltageTick);
        main.post(tick);
        // Recordatorio de mantención al encender (una vez por encendido).
        main.postDelayed(this::maintenanceReminder, 25000);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        loadSignals();
        if (intent != null && intent.getBooleanExtra("test", false)) {
            showBanner(P_HANDBRAKE, Glyph.Kind.HANDBRAKE, WrxTheme.RED,
                "¡Quita el freno de mano!", "Así se ve un aviso (prueba)", 6000, null);
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (gps != null) gps.stop();
        try {
            unregisterReceiver(canReceiver);
        } catch (Exception ignored) {
        }
        getContentResolver().unregisterContentObserver(headlightObserver);
        getContentResolver().unregisterContentObserver(fanObserver);
        getContentResolver().unregisterContentObserver(canObserver);
        saveTrip(true);
        if (speedVolume != null) speedVolume.release();
        removeCamBubble();
        hideBanner();
        if (tone != null) tone.release();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ GPS, viaje y odómetro

    private void onGps(GpsClient.Fix fix) {
        CarState.gpsAvailable = gps.available();
        long now = SystemClock.elapsedRealtime();
        if (fix == null || Float.isNaN(fix.speedKmh)) {
            CarState.speedKmh = Float.NaN;
            lastMoving = false;
            CarState.changed();
            return;
        }
        float speed = fix.moving() ? fix.speedKmh : 0f;
        CarState.speedKmh = speed;
        CarState.altitudeMeters = fix.altitude;
        DvrWatch.logGps(fix.latitude, fix.longitude, speed);
        speedVolume.onSpeed(speed);
        CarState.lat = fix.latitude;
        CarState.lon = fix.longitude;
        CarState.hasPosition = true;

        // Medición de aceleración 0-100 / 0-60
        SportTimer.onSpeed(this, speed, now, (timeSec, isBest) -> {
            showBanner(P_HANDBRAKE, Glyph.Kind.TRIP, isBest ? WrxTheme.OK : WrxTheme.WARN,
                isBest ? "¡NUEVO RÉCORD 0-100 WRX!" : "Aceleración 0 a 100 km/h",
                String.format(Locale.US, "Tiempo: %.2f s (0-60: %.2f s)", timeSec, CarState.last0to60Sec),
                10000, null);
            beep();
        });

        // Actualizar burbuja flotante de cámara
        updateCamBubble(speed);

        long dt = now - lastFixAt;
        if (lastFixAt != 0 && dt <= 5000 && fix.moving() && lastMoving) {
            double d = distanceKm(lastLat, lastLon, fix.latitude, fix.longitude);
            // Saltos imposibles (> 250 km/h) = error del GPS.
            if (d / (dt / 3600000.0) < 250) {
                CarState.tripKm += d;
                CarState.tripMovingMs += dt;
                odoGpsKm += d;
                if (!Double.isNaN(odoBaseKm)) CarState.odometerKm = odoBaseKm + odoGpsKm;
            }
        }
        if (speed > CarState.tripMaxKmh) CarState.tripMaxKmh = speed;
        lastFixAt = now;
        lastLat = fix.latitude;
        lastLon = fix.longitude;
        lastMoving = fix.moving();
        if (++fixCount % 30 == 0) saveTrip(false);
        CarState.changed();
    }

    private int fixCount;
    private SpeedVolume speedVolume;
    private double odoBaseKm = Double.NaN;
    private double odoGpsKm;

    private static double distanceKm(double la1, double lo1, double la2, double lo2) {
        double r = 6371.0;
        double dLa = Math.toRadians(la2 - la1), dLo = Math.toRadians(lo2 - lo1);
        double a = Math.sin(dLa / 2) * Math.sin(dLa / 2) + Math.cos(Math.toRadians(la1))
            * Math.cos(Math.toRadians(la2)) * Math.sin(dLo / 2) * Math.sin(dLo / 2);
        return 2 * r * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    /** Viaje nuevo si la radio estuvo apagada más de 2 horas; si no, sigue el anterior. */
    private void loadTripAndOdometer() {
        SharedPreferences p = WrxPrefs.get(this);
        long last = p.getLong("trip_last", 0);
        if (System.currentTimeMillis() - last > 2 * 3600000L) {
            CarState.tripKm = 0;
            CarState.tripMovingMs = 0;
            CarState.tripMaxKmh = 0;
            CarState.tripStart = System.currentTimeMillis();
        } else {
            CarState.tripKm = Double.longBitsToDouble(p.getLong("trip_km", 0));
            CarState.tripMovingMs = p.getLong("trip_moving", 0);
            CarState.tripMaxKmh = p.getFloat("trip_max", 0);
            CarState.tripStart = p.getLong("trip_start", System.currentTimeMillis());
        }
        reloadOdometer(this);
        odoBaseKm = odoBase(this);
        odoGpsKm = Double.longBitsToDouble(p.getLong("odo_gps", 0));
    }

    private void saveTrip(boolean force) {
        WrxPrefs.get(this).edit()
            .putLong("trip_last", System.currentTimeMillis())
            .putLong("trip_km", Double.doubleToLongBits(CarState.tripKm))
            .putLong("trip_moving", CarState.tripMovingMs)
            .putFloat("trip_max", CarState.tripMaxKmh)
            .putLong("trip_start", CarState.tripStart)
            .putLong("odo_gps", Double.doubleToLongBits(odoGpsKm))
            .putLong("last_lat", Double.doubleToLongBits(CarState.lat))
            .putLong("last_lon", Double.doubleToLongBits(CarState.lon))
            .apply();
    }

    private static double odoBase(Context c) {
        String s = WrxPrefs.get(c).getString("odo_base", null);
        if (s == null) return Double.NaN;
        try {
            return Double.parseDouble(s);
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    /** El usuario ingresó el kilometraje del tablero: base nueva, GPS desde cero. */
    public static void setOdometer(Context c, double km) {
        WrxPrefs.get(c).edit().putString("odo_base", String.valueOf(km))
            .putLong("odo_gps", Double.doubleToLongBits(0)).apply();
        CarState.odometerKm = km;
        // El servicio relee la base al reiniciarse; lo forzamos.
        c.stopService(new Intent(c, CarWatchService.class));
        start(c);
    }

    public static void reloadOdometer(Context c) {
        double base = odoBase(c);
        double gpsKm = Double.longBitsToDouble(WrxPrefs.get(c).getLong("odo_gps", 0));
        CarState.odometerKm = Double.isNaN(base) ? Double.NaN : base + gpsKm;
    }

    public static void resetTrip(Context c) {
        CarState.tripKm = 0;
        CarState.tripMovingMs = 0;
        CarState.tripMaxKmh = 0;
        CarState.tripStart = System.currentTimeMillis();
        WrxPrefs.get(c).edit().putLong("trip_km", 0).putLong("trip_moving", 0).putFloat("trip_max", 0)
            .putLong("trip_start", CarState.tripStart).apply();
        CarState.changed();
    }

    // ------------------------------------------------------------------ señales y temperatura

    private void loadSignals() {
        handbrakeSig = Signal.load(this, Signal.HANDBRAKE);
        reverseSig = Signal.load(this, Signal.REVERSE);
    }

    private void readSignals() {
        Boolean hb = handbrakeSig == null ? null : handbrakeSig.read(this);
        Boolean rv = reverseSig == null ? null : reverseSig.read(this);
        boolean changed = !eq(hb, CarState.handbrake) || !eq(rv, CarState.reverse);
        if (hb != null) CarState.handbrake = hb;
        if (rv != null) CarState.reverse = rv;
        boolean rev = rv != null && rv;
        if (prevReverse && !rev) onReverseEnded();
        prevReverse = rev;
        if (changed) CarState.changed();
    }

    private static boolean eq(Boolean a, Boolean b) {
        return a == null ? b == null : a.equals(b);
    }

    /** La mayor temperatura de las zonas térmicas que se puedan leer (en °C). */
    private void readTemperature() {
        float best = Float.NaN;
        for (int i = 0; i < 12; i++) {
            File f = new File("/sys/class/thermal/thermal_zone" + i + "/temp");
            if (!f.canRead()) continue;
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                String s = r.readLine();
                if (s == null) continue;
                float v = Float.parseFloat(s.trim());
                if (v > 1000) v /= 1000f;
                if (v > 0 && v < 150 && (Float.isNaN(best) || v > best)) best = v;
            } catch (Exception ignored) {
            }
        }
        if (best != CarState.radioTempC) {
            CarState.radioTempC = best;
            CarState.changed();
        }
    }

    // ------------------------------------------------------------------ cámara frontal automática

    /** Al sacar la reversa, si vas lento, abre la cámara frontal (para el labio al estacionar). */
    private void onReverseEnded() {
        if (!WrxPrefs.on(this, WrxPrefs.AUTO_FRONT_CAM, false)) return;
        float s = CarState.speedKmh;
        if (!Float.isNaN(s) && s > 10) return;
        main.postDelayed(() -> {
            if (CarState.reverse != null && CarState.reverse) return; // volvió a reversa
            openFrontCamera(this);
            frontCamOpenedAt = SystemClock.elapsedRealtime();
        }, 1200);
    }

    public static void openFrontCamera(Context c) {
        ExternalApps.openAux(c);
    }

    public static boolean openCameraById(Context c, int cameraId) {
        if (cameraId == 0) {
            return ExternalApps.openHd2Cam(c);
        }
        ExternalApps.openAux(c);
        return true;
    }

    // ------------------------------------------------------------------ avisos

    private void evaluateAlerts() {
        long now = SystemClock.elapsedRealtime();
        float speed = CarState.speedKmh;
        boolean known = !Float.isNaN(speed);

        // Cámara frontal abierta sola: al pasar 20 km/h, vuelve al inicio.
        if (frontCamOpenedAt != 0 && known && speed > 20) {
            frontCamOpenedAt = 0;
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(home);
            } catch (Exception ignored) {
            }
        }

        // 0. Puerta o maletero abierto en marcha (prioridad máxima sobre cualquier app).
        boolean doorAlert = WrxPrefs.on(this, WrxPrefs.ALERT_DOORS, true)
            && CarState.anyDoorOpen() && known && speed >= 5;
        if (doorAlert) {
            showBanner(P_DOOR, Glyph.Kind.DOOR, WrxTheme.RED,
                "¡Puerta abierta!", CarState.openDoorsDescription() + " a " + Math.round(speed) + " km/h", 0, null);
            if (now - lastBeepAt > 3000) {
                lastBeepAt = now;
                beep();
            }
            return;
        } else if (bannerPriority == P_DOOR) {
            hideBanner();
        }

        // 1. Freno de mano puesto andando.
        boolean hbAlert = WrxPrefs.on(this, WrxPrefs.ALERT_HANDBRAKE, true)
            && CarState.handbrake != null && CarState.handbrake && known && speed >= 6;
        if (hbAlert) {
            showBanner(P_HANDBRAKE, Glyph.Kind.HANDBRAKE, WrxTheme.RED,
                "¡Quita el freno de mano!", "Vas a " + Math.round(speed) + " km/h con el freno puesto", 0, null);
            if (now - lastBeepAt > 3000) {
                lastBeepAt = now;
                beep();
            }
            return;
        } else if (bannerPriority == P_HANDBRAKE) {
            hideBanner();
        }

        // 2. Oscuro y sin luces.
        boolean dark = CarState.isDark(System.currentTimeMillis());
        boolean lightsCond = WrxPrefs.on(this, WrxPrefs.ALERT_LIGHTS, true)
            && dark && !CarState.headlights && known && speed >= 15;
        if (lightsCond) {
            if (lightsSince == 0) lightsSince = now;
            if (now - lightsSince > 8000 && now > lightsCooldownUntil) {
                lightsCooldownUntil = now + 10 * 60000L;
                showBanner(P_LIGHTS, Glyph.Kind.LIGHTS, WrxTheme.WARN,
                    "Enciende las luces", "Ya oscureció y los faros están apagados", 9000, null);
            }
        } else {
            lightsSince = 0;
        }

        // 3. Batería baja (detenido) o el alternador no carga (andando).
        float v = voltsSmooth;
        if (WrxPrefs.on(this, WrxPrefs.ALERT_BATTERY, true) && !Float.isNaN(v)) {
            boolean moving = known && speed > 10;
            if (!moving && v < 11.5f) {
                if (lowVoltSince == 0) lowVoltSince = now;
            } else {
                lowVoltSince = 0;
            }
            if (moving && v < 12.6f) {
                if (chargeSince == 0) chargeSince = now;
            } else {
                chargeSince = 0;
            }
            String volts = String.format(Locale.US, "%.1f V", v);
            if (lowVoltSince != 0 && now - lowVoltSince > 60000 && now > voltCooldownUntil) {
                voltCooldownUntil = now + 30 * 60000L;
                showBanner(P_CHARGE, Glyph.Kind.BATTERY, WrxTheme.WARN, "Batería baja: " + volts,
                    "Con el motor apagado la batería se descarga", 10000, null);
            } else if (chargeSince != 0 && now - chargeSince > 90000 && now > voltCooldownUntil) {
                voltCooldownUntil = now + 30 * 60000L;
                showBanner(P_CHARGE, Glyph.Kind.BATTERY, WrxTheme.WARN, "Revisa la carga: " + volts,
                    "Con el motor andando debería estar sobre 13 V", 12000, null);
            }
        }

        // 4. Grabación de la cámara trasera (Streaming HD2, pendrive): cada 30 s.
        if (WrxPrefs.on(this, WrxPrefs.ALERT_DVR, true) && ticks % 30 == 0) checkDvr(now, known && speed > 10);

        // 5. Radio muy caliente.
        float t = CarState.radioTempC;
        if (WrxPrefs.on(this, WrxPrefs.ALERT_HOT, true) && !Float.isNaN(t) && t >= 75) {
            if (hotSince == 0) hotSince = now;
            if (now - hotSince > 30000 && now > hotCooldownUntil) {
                hotCooldownUntil = now + 15 * 60000L;
                showBanner(P_HOT, Glyph.Kind.FAN, WrxTheme.WARN, "Radio caliente: " + Math.round(t) + " °C",
                    "Toca para abrir el ventilador", 10000, () -> openPackage(Apps.FAN));
            }
        } else {
            hotSince = 0;
        }
    }

    private long dvrMovingSince, dvrCooldownUntil, spaceCooldownUntil;

    /** Avisa si el pendrive se está llenando, o si vas andando y la trasera no graba hace rato. */
    private void checkDvr(long now, boolean moving) {
        long free = DvrWatch.freeBytes();
        long total = DvrWatch.totalBytes();
        if (free < 0) {
            if (moving && now > dvrCooldownUntil && now > 120000) {
                dvrCooldownUntil = now + 20 * 60000L;
                showBanner(P_DVR, Glyph.Kind.CAM_REAR, WrxTheme.WARN, "No está el pendrive de la cámara trasera",
                    "Sin pendrive no se graba la cámara trasera", 10000, this::openRecordings);
            }
            return;
        }
        if ((free < 1024L * 1024 * 1024 || (total > 0 && free < total * 0.08)) && now > spaceCooldownUntil) {
            spaceCooldownUntil = now + 60 * 60000L;
            showBanner(P_DVR, Glyph.Kind.CAM_REAR, WrxTheme.RED, "Pendrive de la cámara casi lleno",
                String.format(Locale.US, "Quedan %.1f GB · toca para liberar espacio o formatear", free / 1073741824.0),
                12000, this::openRecordings);
            return;
        }
        if (moving) {
            if (dvrMovingSince == 0) dvrMovingSince = now;
        } else {
            dvrMovingSince = 0;
        }
        // Andando más de 4 minutos y el último video tiene más de 5 minutos: no está grabando.
        if (dvrMovingSince != 0 && now - dvrMovingSince > 4 * 60000L && now > dvrCooldownUntil) {
            long last = DvrWatch.lastRearRecording();
            if (System.currentTimeMillis() - last > 5 * 60000L) {
                dvrCooldownUntil = now + 15 * 60000L;
                ExternalApps.repairHd2DvrDisk(this);
                showBanner(P_DVR, Glyph.Kind.CAM_REAR, WrxTheme.RED, "La cámara trasera no está grabando",
                    "Revisé el pendrive. Toca para ver las grabaciones", 12000, this::openRecordings);
            }
        }
    }

    private void openRecordings() {
        Intent i = getPackageManager().getLaunchIntentForPackage(PKG_RECORDINGS);
        if (i == null) return;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    public static final String PKG_RECORDINGS = "com.k4811.camera.front";

    private void maintenanceReminder() {
        if (!WrxPrefs.on(this, WrxPrefs.ALERT_MAINTENANCE, true)) return;
        List<Maintenance.Item> list = Maintenance.pending(this);
        if (list.isEmpty()) return;
        Maintenance.Item first = list.get(0);
        if (first.urgency() == 0) return;
        String more = list.size() > 1 && list.get(1).urgency() > 0 ? "  (+ otras pendientes)" : "";
        showBanner(P_MAINT, Glyph.Kind.WRENCH, first.urgency() == 2 ? WrxTheme.RED : WrxTheme.WARN,
            "Mantención: " + first.title, first.status() + more, 12000, () -> {
                Intent i = new Intent(this, MaintenanceActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            });
    }

    private void openPackage(String pkg) {
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) return;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    private void beep() {
        try {
            if (tone == null) tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 90);
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 400);
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ burbuja flotante de cámara frontal

    private void updateCamBubble(float speedKmh) {
        if (!WrxPrefs.on(this, WrxPrefs.CAM_BUBBLE, true)) {
            removeCamBubble();
            return;
        }
        if (camBubble == null) buildCamBubble();
        if (camBubble == null) return;

        // Si va a más de 20 km/h se oculta sola para no distraer en carretera
        if (!Float.isNaN(speedKmh) && speedKmh > 20f) {
            if (camBubble.getVisibility() != View.GONE) camBubble.setVisibility(View.GONE);
        } else {
            if (camBubble.getVisibility() != View.VISIBLE) camBubble.setVisibility(View.VISIBLE);
        }
    }

    private void buildCamBubble() {
        if (camBubble != null || wm == null) return;
        camBubble = new FrameLayout(this);
        int sz = px(56);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        bg.setColor(0xE610141A);
        bg.setStroke(px(2), WrxTheme.WARN); // Dorado STI
        camBubble.setBackground(bg);

        Glyph icon = new Glyph(this);
        icon.set(Glyph.Kind.CAM_FRONT).color(0xFFFFFFFF);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(px(32), px(32), Gravity.CENTER);
        camBubble.addView(icon, ilp);

        bubbleLp = new WindowManager.LayoutParams(
            sz, sz,
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
        bubbleLp.gravity = Gravity.TOP | Gravity.START;
        bubbleLp.x = px(24);
        bubbleLp.y = px(180);

        camBubble.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;
            private boolean isDrag;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = bubbleLp.x;
                        initialY = bubbleLp.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        isDrag = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (event.getRawX() - initialTouchX);
                        int dy = (int) (event.getRawY() - initialTouchY);
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) isDrag = true;
                        bubbleLp.x = initialX + dx;
                        bubbleLp.y = initialY + dy;
                        try {
                            wm.updateViewLayout(camBubble, bubbleLp);
                        } catch (Exception ignored) {
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!isDrag) {
                            ExternalApps.openAux(CarWatchService.this);
                        }
                        return true;
                }
                return false;
            }
        });

        try {
            wm.addView(camBubble, bubbleLp);
            bubbleAttached = true;
        } catch (Exception e) {
            Log.w(TAG, "camBubble refused: " + e.getMessage());
            camBubble = null;
        }
    }

    private void removeCamBubble() {
        if (camBubble != null && bubbleAttached && wm != null) {
            try {
                wm.removeView(camBubble);
            } catch (Exception ignored) {
            }
        }
        bubbleAttached = false;
        camBubble = null;
    }

    // ------------------------------------------------------------------ cartel por encima de todo

    private final Runnable autoHide = this::hideBanner;

    private int px(float v) {
        return Math.round(v * scale);
    }

    private void buildBanner() {
        banner = new LinearLayout(this);
        banner.setOrientation(LinearLayout.HORIZONTAL);
        banner.setGravity(Gravity.CENTER_VERTICAL);
        banner.setPadding(px(14), px(12), px(26), px(12));
        bannerIcon = new Glyph(this);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(px(52), px(52));
        ilp.rightMargin = px(16);
        banner.addView(bannerIcon, ilp);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        bannerTitle = new TextView(this);
        bannerTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(24));
        bannerTitle.setTypeface(WrxTheme.bodyBold());
        bannerTitle.setTextColor(0xFFFFFFFF);
        bannerText = new TextView(this);
        bannerText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(16));
        bannerText.setTypeface(WrxTheme.body());
        bannerText.setTextColor(0xCCFFFFFF);
        texts.addView(bannerTitle);
        texts.addView(bannerText);
        banner.addView(texts);
        banner.setOnClickListener(v -> {
            Runnable r = bannerTap;
            hideBanner();
            if (r != null) r.run();
        });
    }

    /** Muestra (o actualiza) el cartel. {@code ms} 0 = queda hasta que se oculte por código. */
    private void showBanner(int priority, Glyph.Kind icon, int color, String title, String text, long ms, Runnable tap) {
        if (bannerAttached && priority < bannerPriority) return;
        if (banner == null) buildBanner();
        bannerPriority = priority;
        bannerTap = tap;
        bannerTitle.setText(title);
        bannerText.setText(text);
        bannerIcon.set(icon).color(0xFFFFFFFF);
        android.graphics.drawable.GradientDrawable iconBg = new android.graphics.drawable.GradientDrawable();
        iconBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        iconBg.setColor(color);
        bannerIcon.setBackground(iconBg);
        bannerIcon.setPadding(px(12), px(12), px(12), px(12));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xF0101317);
        bg.setCornerRadius(px(40));
        bg.setStroke(Math.max(2, px(2)), color);
        banner.setBackground(bg);
        if (!bannerAttached) {
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.y = px(70);
            try {
                wm.addView(banner, lp);
                bannerAttached = true;
                banner.setAlpha(0f);
                banner.setTranslationY(-px(30));
                banner.animate().alpha(1f).translationY(0).setDuration(260).start();
            } catch (Exception e) {
                Log.w(TAG, "overlay refused: " + e.getMessage());
                return;
            }
        }
        main.removeCallbacks(autoHide);
        if (ms > 0) main.postDelayed(autoHide, ms);
    }

    private void hideBanner() {
        main.removeCallbacks(autoHide);
        bannerPriority = 0;
        bannerTap = null;
        if (!bannerAttached) return;
        bannerAttached = false;
        final View v = banner;
        try {
            wm.removeView(v);
        } catch (Exception ignored) {
        }
    }

    /** Para probar el cartel desde Ajustes WRX. */
    public static void test(Context c) {
        try {
            c.startService(new Intent(c, CarWatchService.class).putExtra("test", true));
        } catch (Exception ignored) {
        }
    }
}
