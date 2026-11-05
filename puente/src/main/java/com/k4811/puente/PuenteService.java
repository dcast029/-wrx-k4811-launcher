package com.k4811.puente;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Mantiene el servidor de lectura encendido mientras la app lo pida. */
public class PuenteService extends Service {

    public static final int PORT = 8765;
    private static final String TAG = "Puente";
    private static final List<String> LOG = new ArrayList<>();
    static volatile boolean running;
    static volatile String error;

    private MiniHttp http;
    private WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock wakeLock;

    public static void start(Context c) {
        c.startService(new Intent(c, PuenteService.class));
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, PuenteService.class));
    }

    /** Clave de 6 dígitos, se crea una vez y se muestra en la pantalla de la app. */
    static String key(Context c) {
        SharedPreferences p = c.getSharedPreferences("puente", MODE_PRIVATE);
        String k = p.getString("key", null);
        if (k == null) {
            k = String.format(Locale.US, "%06d", new SecureRandom().nextInt(1000000));
            p.edit().putString("key", k).apply();
        }
        return k;
    }

    static void newKey(Context c) {
        c.getSharedPreferences("puente", MODE_PRIVATE).edit().remove("key").apply();
    }

    static synchronized List<String> log() {
        return new ArrayList<>(LOG);
    }

    static synchronized void addLog(String s) {
        LOG.add(new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + "  " + s);
        if (LOG.size() > 200) LOG.remove(0);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            http = new MiniHttp(PORT, new Routes(this, key(this), PuenteService::addLog));
            http.start();
            running = true;
            error = null;
            addLog("servidor encendido en " + ipAddress(this) + ":" + PORT);
        } catch (Exception e) {
            running = false;
            error = e.toString();
            Log.w(TAG, "no pude abrir el puerto: " + e);
        }
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL, "puente");
            wifiLock.acquire();
            wakeLock = ((PowerManager) getSystemService(POWER_SERVICE))
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "puente:server");
            wakeLock.acquire();
        } catch (Exception ignored) {
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (http != null) http.stop();
        running = false;
        addLog("servidor apagado");
        try {
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** IPv4 de la red WiFi (o de cualquier interfaz que no sea la local). */
    static String ipAddress(Context c) {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return "sin red";
    }
}
