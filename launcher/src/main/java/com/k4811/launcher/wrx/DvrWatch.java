package com.k4811.launcher.wrx;

import android.os.Environment;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Seguridad de las grabaciones:
 * - Guarda el recorrido GPS en K4811/gps/AAAAMMDD.csv (tiempo, lat, lon, km/h) para que la app
 *   Grabaciones muestre dónde se grabó cada video (útil ante un accidente). Se borran solos a los 60 días.
 * - Revisa el pendrive de la cámara trasera (Streaming HD2): si se está llenando o dejó de grabar.
 */
final class DvrWatch {
    private DvrWatch() {
    }

    private static final File GPS_DIR = new File(Environment.getExternalStorageDirectory(), "K4811/gps");
    private static final String[] DVR_ROOTS = {"/storage/udiskh", "/mnt/media_rw/udiskh"};
    private static long lastGpsWrite;
    private static boolean cleaned;

    /** Cada 5 s andando, cada 60 s detenido. */
    static void logGps(double lat, double lon, float speedKmh) {
        long now = System.currentTimeMillis();
        long every = speedKmh >= 3 ? 5000 : 60000;
        if (now - lastGpsWrite < every) return;
        lastGpsWrite = now;
        try {
            if (!GPS_DIR.isDirectory() && !GPS_DIR.mkdirs()) return;
            String day = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date(now));
            try (FileWriter w = new FileWriter(new File(GPS_DIR, day + ".csv"), true)) {
                w.write(String.format(Locale.US, "%d,%.6f,%.6f,%.0f\n", now, lat, lon, speedKmh));
            }
            if (!cleaned) {
                cleaned = true;
                File[] old = GPS_DIR.listFiles();
                long limit = now - 60L * 24 * 3600 * 1000;
                if (old != null) for (File f : old) if (f.lastModified() < limit) f.delete();
            }
        } catch (Exception ignored) {
        }
    }

    static File dvrRoot() {
        for (String r : DVR_ROOTS) {
            File f = new File(r);
            if (f.isDirectory() && f.canRead()) return f;
        }
        return null;
    }

    /** Hora del video más nuevo de la trasera (ms) o 0. Sigue las carpetas por fecha (la "mayor"). */
    static long lastRearRecording() {
        File root = dvrRoot();
        return root == null ? 0 : newest(new File(root, "HDDVR/rearcam"), 0);
    }

    private static long newest(File dir, int depth) {
        if (depth > 5) return 0;
        File[] list = dir.listFiles();
        if (list == null) return 0;
        long best = 0;
        File lastDir = null;
        for (File f : list) {
            if (f.getName().startsWith(".")) continue;
            if (f.isDirectory()) {
                if (lastDir == null || f.getName().compareTo(lastDir.getName()) > 0) lastDir = f;
            } else if (f.length() > 0 && f.lastModified() > best) {
                best = f.lastModified();
            }
        }
        if (lastDir != null) best = Math.max(best, newest(lastDir, depth + 1));
        return best;
    }

    /** Espacio libre del pendrive (bytes) o -1 si no está. */
    static long freeBytes() {
        File root = dvrRoot();
        return root == null ? -1 : root.getFreeSpace();
    }

    static long totalBytes() {
        File root = dvrRoot();
        return root == null ? -1 : root.getTotalSpace();
    }
}
