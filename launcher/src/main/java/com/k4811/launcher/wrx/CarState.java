package com.k4811.launcher.wrx;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

/**
 * Lo último que se sabe del auto, en un solo lugar (mismo proceso). Lo llena
 * {@link CarWatchService}; la pantalla de inicio solo lo dibuja.
 */
public final class CarState {
    private CarState() {
    }

    public interface Listener {
        void onCarState();
    }

    /** km/h por GPS; NaN = sin señal. */
    public static float speedKmh = Float.NaN;
    public static boolean gpsAvailable = true;
    /** Altitud en metros sobre el nivel del mar; NaN = sin señal. */
    public static double altitudeMeters = Double.NaN;
    /** Rumbo o dirección en grados (0° a 359°); NaN = sin rumbo. */
    public static float bearing = Float.NaN;
    /** Última posición conocida (para saber si ya es de noche); por defecto, Santiago. */
    public static double lat = -33.45, lon = -70.66;
    public static boolean hasPosition;

    /** Batería por CAN; NaN = el auto no respondió. */
    public static float volts = Float.NaN;
    public static boolean headlights;
    /** null = señal sin calibrar. */
    public static Boolean handbrake;
    public static Boolean reverse;

    /** Puertas y maletero por CAN Bus (Subaru Raise). */
    public static boolean doorFrontLeft;   // Conductor (Piloto)
    public static boolean doorFrontRight;  // Copiloto (Acompañante)
    public static boolean doorBackLeft;    // Trasera Izquierda
    public static boolean doorBackRight;   // Trasera Derecha
    public static boolean doorTrunk;       // Maletero / Maleta
    public static boolean doorHood;        // Capó delantero

    public static boolean anyDoorOpen() {
        return doorFrontLeft || doorFrontRight || doorBackLeft || doorBackRight || doorTrunk || doorHood;
    }

    public static int openDoorsCount() {
        int c = 0;
        if (doorFrontLeft) c++;
        if (doorFrontRight) c++;
        if (doorBackLeft) c++;
        if (doorBackRight) c++;
        if (doorTrunk) c++;
        if (doorHood) c++;
        return c;
    }

    public static String openDoorsDescription() {
        List<String> list = new ArrayList<>();
        if (doorFrontLeft) list.add("Conductor");
        if (doorFrontRight) list.add("Copiloto");
        if (doorBackLeft) list.add("Tras. Izq.");
        if (doorBackRight) list.add("Tras. Der.");
        if (doorTrunk) list.add("Maletero");
        if (doorHood) list.add("Capó");
        if (list.isEmpty()) return "";
        if (list.size() == 1) return "Puerta " + list.get(0);
        if (list.size() == 2) return list.get(0) + " y " + list.get(1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(i == list.size() - 1 ? " y " : ", ");
            sb.append(list.get(i));
        }
        return sb.toString();
    }
    /** Temperatura del procesador de la radio; NaN = no se puede leer. */
    public static float radioTempC = Float.NaN;
    public static boolean fanOn;

    public static double tripKm;
    public static long tripMovingMs;
    public static long tripStart;
    public static float tripMaxKmh;
    /** Odómetro estimado (el que ingresaste + lo recorrido por GPS); NaN = no ingresado. */
    public static double odometerKm = Double.NaN;

    /** Modo Pista WRX: telemetría de aceleración. */
    public static float best0to100Sec = Float.NaN;
    public static float best0to60Sec = Float.NaN;
    public static float last0to100Sec = Float.NaN;
    public static float last0to60Sec = Float.NaN;
    public static boolean sportArmed = false;

    private static final List<Listener> listeners = new ArrayList<>();

    public static synchronized void add(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    public static synchronized void remove(Listener l) {
        listeners.remove(l);
    }

    /** Llamar en el hilo principal. */
    public static void changed() {
        Listener[] copy;
        synchronized (CarState.class) {
            copy = listeners.toArray(new Listener[0]);
        }
        for (Listener l : copy) l.onCarState();
    }

    public static boolean moving() {
        return !Float.isNaN(speedKmh) && speedKmh >= 3f;
    }

    public static float tripAvgKmh() {
        return tripMovingMs < 60000 ? Float.NaN : (float) (tripKm / (tripMovingMs / 3600000.0));
    }

    // ------------------------------------------------------------------ ¿ya oscureció?

    /** Elevación del sol en grados (fórmula de la NOAA, suficiente para saber si es de noche). */
    public static double sunElevation(double latDeg, double lonDeg, long timeMs) {
        Calendar u = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        u.setTimeInMillis(timeMs);
        int n = u.get(Calendar.DAY_OF_YEAR);
        double hour = u.get(Calendar.HOUR_OF_DAY) + u.get(Calendar.MINUTE) / 60.0 + u.get(Calendar.SECOND) / 3600.0;
        double g = 2 * Math.PI / 365.0 * (n - 1 + (hour - 12) / 24.0);
        double eqtime = 229.18 * (0.000075 + 0.001868 * Math.cos(g) - 0.032077 * Math.sin(g)
            - 0.014615 * Math.cos(2 * g) - 0.040849 * Math.sin(2 * g));
        double decl = 0.006918 - 0.399912 * Math.cos(g) + 0.070257 * Math.sin(g) - 0.006758 * Math.cos(2 * g)
            + 0.000907 * Math.sin(2 * g) - 0.002697 * Math.cos(3 * g) + 0.00148 * Math.sin(3 * g);
        double tst = hour * 60 + eqtime + 4 * lonDeg;
        double ha = Math.toRadians(tst / 4 - 180);
        double la = Math.toRadians(latDeg);
        double cosZ = Math.sin(la) * Math.sin(decl) + Math.cos(la) * Math.cos(decl) * Math.cos(ha);
        return 90 - Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cosZ))));
    }

    /** Oscuro = el sol más de 3° bajo el horizonte (después del atardecer). */
    public static boolean isDark(long timeMs) {
        return sunElevation(lat, lon, timeMs) < -3;
    }
}
