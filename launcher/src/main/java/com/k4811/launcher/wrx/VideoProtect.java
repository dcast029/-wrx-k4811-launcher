package com.k4811.launcher.wrx;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Guarda y protege el último video de la cámara trasera con sus datos GPS y mapa.
 */
public final class VideoProtect {

    public interface OnDone {
        void onDone(boolean success, String message);
    }

    private VideoProtect() {
    }

    public static void protectLatestVideo(Context c, OnDone callback) {
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                File root = new File("/storage/udiskh/HDDVR/rearcam");
                if (!root.exists() || !root.isDirectory()) {
                    root = new File("/mnt/media_rw/udiskh/HDDVR/rearcam");
                }
                if (!root.exists() || !root.isDirectory()) {
                    main.post(() -> callback.onDone(false, "No encontré el pendrive DVR (udiskh)."));
                    return;
                }

                File newest = findNewestVideo(root, 0);
                if (newest == null) {
                    main.post(() -> callback.onDone(false, "Aún no hay videos grabados en el pendrive."));
                    return;
                }

                File destDir = new File("/storage/emulated/0/K4811/Protegidas");
                if (!destDir.exists()) destDir.mkdirs();

                String timeStr = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                String ext = newest.getName().endsWith(".mp4") ? ".mp4" : ".ts";
                File destFile = new File(destDir, "PROTEGIDO_" + timeStr + ext);

                copyFile(newest, destFile);

                // Escribir archivo de metadatos GPS y mapa
                File infoFile = new File(destDir, "PROTEGIDO_" + timeStr + ".txt");
                try (FileWriter fw = new FileWriter(infoFile)) {
                    fw.write("SUBARU WRX - VIDEO PROTEGIDO\n");
                    fw.write("Fecha de guardado: " + new Date().toString() + "\n");
                    fw.write("Archivo original: " + newest.getName() + " (" + (newest.length() / (1024 * 1024)) + " MB)\n");
                    if (CarState.hasPosition) {
                        fw.write(String.format(Locale.US, "GPS: %.6f, %.6f\n", CarState.lat, CarState.lon));
                        fw.write("Mapa: https://maps.google.com/?q=" + CarState.lat + "," + CarState.lon + "\n");
                    }
                    if (!Float.isNaN(CarState.speedKmh)) {
                        fw.write(String.format(Locale.US, "Velocidad: %.0f km/h\n", CarState.speedKmh));
                    }
                    if (!Double.isNaN(CarState.altitudeMeters)) {
                        fw.write(String.format(Locale.US, "Altitud: %.0f msnm\n", CarState.altitudeMeters));
                    }
                }

                main.post(() -> callback.onDone(true, "¡Video protegido guardado!\nEn K4811/Protegidas (" + newest.getName() + ")"));
            } catch (Exception e) {
                main.post(() -> callback.onDone(false, "Error al proteger video: " + e.getMessage()));
            }
        }, "K4811-VideoProtect").start();
    }

    private static File findNewestVideo(File dir, int depth) {
        if (dir == null || depth > 4 || !dir.exists()) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;
        File best = null;
        for (File f : files) {
            if (f.isDirectory()) {
                File subBest = findNewestVideo(f, depth + 1);
                if (subBest != null && (best == null || subBest.lastModified() > best.lastModified())) {
                    best = subBest;
                }
            } else if (f.isFile() && f.length() > 500000) { // Mayor a 500 KB para ignorar temporales corruptos
                String name = f.getName().toLowerCase(Locale.US);
                if ((name.endsWith(".ts") || name.endsWith(".mp4")) && !name.contains(".temp")) {
                    if (best == null || f.lastModified() > best.lastModified()) {
                        best = f;
                    }
                }
            }
        }
        return best;
    }

    private static void copyFile(File src, File dst) throws Exception {
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        }
    }
}

