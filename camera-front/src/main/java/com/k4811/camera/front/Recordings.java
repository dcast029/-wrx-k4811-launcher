package com.k4811.camera.front;

import android.os.Environment;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Busca las grabaciones del auto y las ordena por fecha:
 * - Trasera: Streaming HD2 graba en el pendrive, en HDDVR/rearcam/AAAAMM/DD/HH/*.ts.
 * - Bloqueadas por golpe: HDDVR/sensorlock.
 * - Dashcam: la app de la dashcam (Traffic Safety Guard) guarda en /sdcard/uvccameramjpeg/VIDEO
 *   los videos que se bajan desde su memoria propia.
 * - Protegidas: copias que el usuario guardó en /sdcard/K4811/Protegidas (no se borran solas).
 * La ubicación sale del registro GPS que deja el launcher en /sdcard/K4811/gps/AAAAMMDD.csv.
 */
final class Recordings {
    private Recordings() {
    }

    static final String REAR = "Trasera";
    static final String LOCKED = "Golpe";
    static final String DASHCAM = "Dashcam";
    static final String FRONT = "Frontal";
    static final String PROTECTED = "Protegida";

    /** Trasera (Streaming HD2): pendrive "udiskh". */
    static final String[] DVR_ROOTS = {"/storage/udiskh", "/mnt/media_rw/udiskh"};
    /** Dashcam (Traffic Safety Guard): su memoria SD aparece como disco "udisk". */
    static final String[] DASHCAM_ROOTS = {"/storage/udisk", "/mnt/media_rw/udisk"};
    static final File DASHCAM_DIR = new File(Environment.getExternalStorageDirectory(), "uvccameramjpeg/VIDEO");
    static final File PROTECTED_DIR = new File(Environment.getExternalStorageDirectory(), "K4811/Protegidas");
    static final File GPS_DIR = new File(Environment.getExternalStorageDirectory(), "K4811/gps");

    static final class Video {
        File file;
        String kind;
        long startMs;
        long size;
        // Ubicación (NaN = sin dato)
        double lat = Double.NaN, lon = Double.NaN;
        float speed = Float.NaN;
    }

    /** Raíz del pendrive de Streaming HD2 (con carpeta HDDVR), o null si no está conectado. */
    static File dvrRoot() {
        for (String r : DVR_ROOTS) {
            File f = new File(r);
            if (new File(f, "HDDVR").isDirectory()) return f;
        }
        for (String r : DVR_ROOTS) {
            File f = new File(r);
            if (f.isDirectory() && f.canRead()) return f;
        }
        return null;
    }

    static List<Video> scanAll() {
        List<Video> out = new ArrayList<>();
        File root = dvrRoot();
        if (root != null) {
            File hd = new File(root, "HDDVR");
            scan(new File(hd, "rearcam"), REAR, out, 0);
            scan(new File(hd, "frontcam"), FRONT, out, 0);
            scan(new File(hd, "sensorlock"), LOCKED, out, 0);
        }
        scan(DASHCAM_DIR, DASHCAM, out, 0);
        // La dashcam es el disco "udisk" (su propia SD). Normalmente sus videos solo se leen con su app
        // (por VSFILE), pero si alguna vez aparecen como archivos, se muestran aquí.
        for (String r : DASHCAM_ROOTS) {
            File d = new File(r);
            if (!d.isDirectory()) continue;
            File[] top = d.listFiles();
            if (top == null) continue;
            for (File f : top) {
                if (f.getName().equals("Android") || f.getName().startsWith(".")) continue;
                if (f.isDirectory()) scan(f, DASHCAM, out, 1);
            }
            break;
        }
        scan(PROTECTED_DIR, PROTECTED, out, 0);
        Collections.sort(out, (a, b) -> Long.compare(b.startMs, a.startMs));
        return out;
    }

    private static final Pattern DATE = Pattern.compile(
        "(20\\d{2})[-_]?(\\d{2})[-_]?(\\d{2})[-_ T]?(\\d{2})[-_:]?(\\d{2})[-_:]?(\\d{2})");

    private static void scan(File dir, String kind, List<Video> out, int depth) {
        if (depth > 6) return;
        File[] list = dir.listFiles();
        if (list == null) return;
        for (File f : list) {
            String n = f.getName();
            if (n.startsWith(".")) continue;
            if (f.isDirectory()) {
                scan(f, kind, out, depth + 1);
                continue;
            }
            String l = n.toLowerCase(Locale.US);
            if (!(l.endsWith(".ts") || l.endsWith(".mp4") || l.endsWith(".avi") || l.endsWith(".mov"))) continue;
            long size = f.length();
            if (size < 64 * 1024) continue; // archivos vacíos o cortados
            Video v = new Video();
            v.file = f;
            v.kind = kind;
            v.size = size;
            v.startMs = timeFromName(n, f);
            if (PROTECTED.equals(kind) && n.contains("dashcam")) v.kind = PROTECTED;
            out.add(v);
        }
    }

    /** Hora de inicio: del nombre si trae fecha (AAAAMMDDHHMMSS); si no, la fecha del archivo. */
    private static long timeFromName(String name, File f) {
        Matcher m = DATE.matcher(name);
        if (m.find()) {
            try {
                Calendar c = Calendar.getInstance();
                c.set(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)) - 1, Integer.parseInt(m.group(3)),
                    Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6)));
                c.set(Calendar.MILLISECOND, 0);
                long t = c.getTimeInMillis();
                if (Math.abs(t - f.lastModified()) < 3L * 24 * 3600 * 1000 || f.lastModified() < 1500000000000L) return t;
            } catch (Exception ignored) {
            }
        }
        return f.lastModified();
    }

    // ------------------------------------------------------------------ ubicación GPS

    private static final Map<String, List<double[]>> gpsCache = new HashMap<>();

    /** Registro GPS del día (tiempo, lat, lon, km/h), leído una vez. */
    private static synchronized List<double[]> gpsDay(long timeMs) {
        String day = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date(timeMs));
        List<double[]> pts = gpsCache.get(day);
        if (pts != null) return pts;
        pts = new ArrayList<>();
        File f = new File(GPS_DIR, day + ".csv");
        if (f.isFile()) {
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String[] p = line.split(",");
                    if (p.length < 3) continue;
                    try {
                        pts.add(new double[]{Double.parseDouble(p[0]), Double.parseDouble(p[1]),
                            Double.parseDouble(p[2]), p.length > 3 ? Double.parseDouble(p[3]) : Double.NaN});
                    } catch (NumberFormatException ignored) {
                    }
                }
            } catch (Exception ignored) {
            }
        }
        gpsCache.put(day, pts);
        return pts;
    }

    static synchronized void clearGpsCache() {
        gpsCache.clear();
    }

    /** Busca el punto GPS más cercano (máx. 5 minutos de diferencia) al inicio del video. */
    static void locate(Video v) {
        if (!Double.isNaN(v.lat)) return;
        List<double[]> pts = gpsDay(v.startMs);
        double[] best = null;
        long bestDiff = 5 * 60 * 1000;
        for (double[] p : pts) {
            long diff = Math.abs((long) p[0] - v.startMs);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = p;
            }
        }
        if (best != null) {
            v.lat = best[1];
            v.lon = best[2];
            v.speed = (float) best[3];
        }
    }

    // ------------------------------------------------------------------ espacio y estado

    static final class DiskInfo {
        boolean present;
        long free, total;
        /** Último video de la trasera (ms) o 0. */
        long lastRearMs;
        String path;
    }

    static DiskInfo disk() {
        DiskInfo d = new DiskInfo();
        File root = dvrRoot();
        if (root == null) return d;
        d.present = true;
        d.path = root.getPath();
        d.free = root.getFreeSpace();
        d.total = root.getTotalSpace();
        d.lastRearMs = newest(new File(root, "HDDVR/rearcam"), 0);
        return d;
    }

    private static long newest(File dir, int depth) {
        if (depth > 5) return 0;
        File[] list = dir.listFiles();
        if (list == null) return 0;
        long best = 0;
        // Las carpetas tienen nombres por fecha: basta mirar la "mayor" en cada nivel.
        File lastDir = null;
        for (File f : list) {
            if (f.getName().startsWith(".")) continue;
            if (f.isDirectory()) {
                if (lastDir == null || f.getName().compareTo(lastDir.getName()) > 0) lastDir = f;
            } else if (f.lastModified() > best && f.length() > 0) {
                best = f.lastModified();
            }
        }
        if (lastDir != null) best = Math.max(best, newest(lastDir, depth + 1));
        return best;
    }

    /** Borra las grabaciones normales (no bloqueadas ni protegidas) más antiguas que {@code days}. */
    static int deleteOlderThan(int days) {
        File root = dvrRoot();
        if (root == null) return 0;
        long limit = System.currentTimeMillis() - days * 24L * 3600 * 1000;
        int n = 0;
        for (String sub : new String[]{"HDDVR/rearcam", "HDDVR/frontcam"}) n += deleteOld(new File(root, sub), limit, 0);
        return n;
    }

    private static int deleteOld(File dir, long limit, int depth) {
        if (depth > 6) return 0;
        File[] list = dir.listFiles();
        if (list == null) return 0;
        int n = 0;
        for (File f : list) {
            if (f.getName().startsWith(".")) continue;
            if (f.isDirectory()) {
                n += deleteOld(f, limit, depth + 1);
                String[] rest = f.list();
                if (rest != null && rest.length == 0) f.delete();
            } else if (f.lastModified() < limit && f.delete()) {
                n++;
            }
        }
        return n;
    }

    /** Copia el video a K4811/Protegidas (memoria interna) con fecha, tipo y ubicación. */
    static File protect(Video v) throws Exception {
        if (!PROTECTED_DIR.isDirectory() && !PROTECTED_DIR.mkdirs()) throw new Exception("No pude crear " + PROTECTED_DIR);
        String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date(v.startMs));
        String name = v.file.getName();
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')) : ".ts";
        File dest = new File(PROTECTED_DIR, stamp + "_" + v.kind.toLowerCase(Locale.US) + ext);
        try (InputStream in = new FileInputStream(v.file); OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[256 * 1024];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
        }
        dest.setLastModified(v.startMs);
        try (OutputStream out = new FileOutputStream(new File(PROTECTED_DIR, stamp + "_info.txt"))) {
            String info = "Video: " + v.file.getPath() + "\nInicio: " + new Date(v.startMs) + "\nTipo: " + v.kind
                + (Double.isNaN(v.lat) ? "\nUbicación: sin dato GPS\n"
                : String.format(Locale.US, "\nUbicación: %.6f, %.6f\nMapa: https://maps.google.com/?q=%.6f,%.6f\n",
                v.lat, v.lon, v.lat, v.lon));
            out.write(info.getBytes("UTF-8"));
        }
        return dest;
    }
}
