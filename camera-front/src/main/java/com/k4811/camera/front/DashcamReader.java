package com.k4811.camera.front;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.huiying.cameramjpeg.UvcCamera;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Protocolo de comunicación con la dashcam USB (Traffic Safety Guard)
 * a través de su canal HWC y la librería libuvccamera-lib.so.
 */
public final class DashcamReader {
    private static final String TAG = "DashcamReader";
    public static final String[] HWC_CANDIDATES = {
        "/mnt/media_rw/udisk/Android/data/com.fvision.camera/HWC",
        "/storage/udisk/Android/data/com.fvision.camera/HWC",
        "/mnt/media_rw/udisk/Android/data/com.fvision.camera/hwc",
        "/storage/udisk/Android/data/com.fvision.camera/hwc"
    };

    public static class FileItem {
        public final String name;
        public final long timeMs;
        public final boolean locked;
        public int sizeBytes;

        public FileItem(String name, long timeMs, boolean locked) {
            this.name = name;
            this.timeMs = timeMs;
            this.locked = locked;
        }

        public String formattedTime() {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(timeMs));
        }
    }

    public static class ListResult {
        public final List<FileItem> items;
        public final String debugInfo;

        public ListResult(List<FileItem> items, String debugInfo) {
            this.items = items;
            this.debugInfo = debugInfo;
        }
    }

    public interface DownloadListener {
        void onProgress(int percent, long currentBytes, long totalBytes);
        void onComplete(File file);
        void onError(String message);
    }

    private DashcamReader() {}

    public static String findHwc() {
        for (String p : HWC_CANDIDATES) {
            File f = new File(p);
            if (f.exists()) return p;
        }
        for (String root : new String[]{"/mnt/media_rw/udisk", "/storage/udisk"}) {
            File hwc = new File(root, "Android/data/com.fvision.camera/HWC");
            if (hwc.exists()) return hwc.getAbsolutePath();
        }
        return null;
    }

    public static boolean isDashcamAvailable() {
        if (findHwc() != null) return true;
        for (String root : new String[]{"/storage/udisk", "/mnt/media_rw/udisk"}) {
            if (new File(root).exists()) return true;
        }
        return false;
    }

    /**
     * Abre el canal de comandos intentando las rutas candidatas.
     */
    public static int openChannel(UvcCamera cam, StringBuilder log) {
        String existing = findHwc();
        List<String> list = new ArrayList<>();
        if (existing != null) list.add(existing);
        for (String p : HWC_CANDIDATES) {
            if (!list.contains(p)) list.add(p);
        }
        for (String p : list) {
            int fd = cam.openCmd(p);
            if (log != null) log.append(p).append(" -> fd=").append(fd).append(" (").append(cam.cmd_fd_error).append(")\n");
            if (fd > 0) return fd;
        }
        return -1;
    }

    /**
     * Consulta y decodifica la lista de videos almacenados en la dashcam.
     */
    public static ListResult listFiles() {
        UvcCamera cam = UvcCamera.getInstance();
        StringBuilder debug = new StringBuilder();

        int fd = openChannel(cam, debug);
        if (fd <= 0) {
            Log.e(TAG, "No se pudo abrir HWC:\n" + debug);
            return new ListResult(Collections.emptyList(), "No se pudo conectar al canal HWC:\n" + debug);
        }

        List<FileItem> items = new ArrayList<>();
        try {
            // Comando 33: abrir lista de archivos
            byte[] cmd33Buf = new byte[4];
            int r33 = cam.sendCmd(-1, 33, -1, -1, 1, cmd33Buf);
            debug.append("Cmd 33 (openFiles): ").append(r33).append("\n");

            // Breve espera para que el firmware de la dashcam prepare el búfer
            try { Thread.sleep(120); } catch (InterruptedException ignored) {}

            byte[] buf = new byte[6000]; // hasta 1000 archivos
            int read = cam.getFile(buf);
            debug.append("getFile(6000) leyó: ").append(read).append(" bytes\n");

            // Si getFile leyó 0 o negativo, probar comando alternativo 42 (GetPicUtil / lista completa)
            if (read <= 0) {
                byte[] altBuf = new byte[3000];
                int r42 = cam.sendCmd(-1, 42, -1, -1, altBuf.length, altBuf);
                debug.append("Cmd 42 alternativo: ").append(r42).append("\n");
                if (r42 > 0) {
                    buf = altBuf;
                    read = r42;
                } else {
                    int rRetry = cam.getFile(buf);
                    debug.append("Reintento getFile: ").append(rRetry).append(" bytes\n");
                    if (rRetry > 0) read = rRetry;
                }
            }

            if (read > 0) {
                int count = read / 6;
                for (int i = 0; i < count; i++) {
                    int offset = i * 6;
                    int typeWord = (buf[offset] & 0xFF) | ((buf[offset + 1] & 0xFF) << 8);
                    int dateWord = (buf[offset + 2] & 0xFF) | ((buf[offset + 3] & 0xFF) << 8);
                    int timeWord = (buf[offset + 4] & 0xFF) | ((buf[offset + 5] & 0xFF) << 8);

                    if (dateWord == 0 && timeWord == 0) continue;

                    int year = ((dateWord >> 9) & 0x7F) + 1980;
                    int month = (dateWord >> 5) & 0x0F;
                    int day = dateWord & 0x1F;

                    int hour = (timeWord >> 11) & 0x1F;
                    int minute = (timeWord >> 5) & 0x3F;
                    int second = (timeWord & 0x1F) * 2;

                    boolean locked = (typeWord & 0x1000) != 0;
                    String prefix;
                    if (locked) {
                        prefix = (typeWord & 0x2000) != 0 ? "LOB" : "LOK";
                    } else if ((typeWord & 0x8000) != 0) {
                        prefix = "MOV";
                    } else if ((typeWord & 0x2000) != 0) {
                        prefix = "MOB";
                    } else {
                        prefix = "OTHER";
                    }

                    int num = typeWord & 0x0FFF;
                    String ext = (typeWord & 0x4000) != 0 ? ".jpg" : ".AVI";
                    String name = String.format(Locale.US, "%s%05d%s", prefix, num, ext);

                    Calendar cal = Calendar.getInstance();
                    cal.set(year, Math.max(0, month - 1), day, hour, minute, second);
                    cal.set(Calendar.MILLISECOND, 0);

                    items.add(new FileItem(name, cal.getTimeInMillis(), locked));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error listando archivos de dashcam", e);
            debug.append("Excepción: ").append(e.getMessage()).append("\n");
        } finally {
            cam.close();
        }

        // Ordenar del más nuevo al más antiguo
        Collections.sort(items, (a, b) -> Long.compare(b.timeMs, a.timeMs));
        return new ListResult(items, debug.toString());
    }

    /**
     * Descarga un video de la dashcam a la carpeta /sdcard/uvccameramjpeg/VIDEO/.
     */
    public static void download(FileItem item, DownloadListener listener) {
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            File dir = Recordings.DASHCAM_DIR;
            if (!dir.exists()) dir.mkdirs();

            // Prefijo con fecha AAAAMMDD_HHMMSS para compatibilidad automática con el GPS del launcher
            String datePrefix = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(item.timeMs));
            File dest = new File(dir, datePrefix + "_" + item.name);
            if (dest.exists() && dest.length() > 500000) {
                main.post(() -> listener.onComplete(dest));
                return;
            }

            File temp = new File(dir, dest.getName() + ".part");
            UvcCamera cam = UvcCamera.getInstance();
            int fd = openChannel(cam, null);
            if (fd <= 0) {
                main.post(() -> listener.onError("No se pudo conectar a la dashcam (fd=" + fd + ")"));
                return;
            }

            try {
                // Comando 39: abrir archivo para lectura
                byte[] nameBytes = item.name.getBytes(Charset.forName("US-ASCII"));
                byte[] cmd39Buf = new byte[Math.max(32, nameBytes.length)];
                System.arraycopy(nameBytes, 0, cmd39Buf, 0, nameBytes.length);

                int r39 = cam.sendCmd(-1, 39, -1, -1, nameBytes.length, cmd39Buf);
                Log.d(TAG, "Comando 39 respuesta: " + r39);
                if (r39 < 0) {
                    main.post(() -> listener.onError("La dashcam no pudo abrir " + item.name));
                    return;
                }

                // El tamaño del archivo viene en los primeros 4 bytes big-endian
                int fileSize = ((cmd39Buf[0] & 0xFF) << 24)
                    | ((cmd39Buf[1] & 0xFF) << 16)
                    | ((cmd39Buf[2] & 0xFF) << 8)
                    | (cmd39Buf[3] & 0xFF);

                Log.d(TAG, "Tamaño de " + item.name + ": " + fileSize + " bytes");
                if (fileSize <= 0) {
                    main.post(() -> listener.onError("Tamaño de archivo inválido: " + fileSize));
                    return;
                }
                item.sizeBytes = fileSize;

                // Comando 40: leer en bloques de 8192 bytes
                try (FileOutputStream fos = new FileOutputStream(temp)) {
                    byte[] block = new byte[8192];
                    long downloaded = 0;
                    int lastPct = -1;

                    while (downloaded < fileSize) {
                        int toRead = (int) Math.min(8192, fileSize - downloaded);
                        int r40 = cam.sendCmd(-1, 40, -1, -1, toRead, block);
                        if (r40 < 0) {
                            // Reintento
                            r40 = cam.sendCmd(-1, 40, -1, -1, toRead, block);
                            if (r40 < 0) {
                                throw new Exception("Error leyendo bloque en " + downloaded + " bytes");
                            }
                        }
                        fos.write(block, 0, toRead);
                        downloaded += toRead;

                        int pct = (int) (downloaded * 100L / fileSize);
                        if (pct != lastPct) {
                            lastPct = pct;
                            final long cur = downloaded;
                            final int p = pct;
                            main.post(() -> listener.onProgress(p, cur, fileSize));
                        }
                    }
                }

                // Comando 36: cerrar lectura
                byte[] c36 = new byte[1];
                cam.sendCmd(-1, 36, -1, -1, 1, c36);

                if (temp.renameTo(dest)) {
                    dest.setLastModified(item.timeMs);
                    main.post(() -> listener.onComplete(dest));
                } else {
                    main.post(() -> listener.onError("Error al renombrar archivo temporal"));
                }
            } catch (Exception e) {
                Log.e(TAG, "Error durante la descarga", e);
                temp.delete();
                main.post(() -> listener.onError("Descarga interrumpida: " + e.getMessage()));
            } finally {
                cam.close();
            }
        }, "DashcamDownloadThread").start();
    }
}
