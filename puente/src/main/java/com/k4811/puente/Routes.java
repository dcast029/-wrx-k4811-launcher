package com.k4811.puente;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.provider.Settings;
import android.util.DisplayMetrics;

import com.k4811.common.system.SystemProps;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Rutas HTTP del Puente PC (v1.2.0):
 * Lectura completa de diagnóstico + envío e instalación de APKs por WiFi,
 * desinstalación asistida, ajuste de Settings.System y reparación recursiva de USB DVR.
 * Todas las rutas (menos "/") exigen la clave ?k=123456.
 */
final class Routes implements MiniHttp.Handler {

    private final Context c;
    private final String key;
    private final Log log;

    interface Log {
        void line(String s);
    }

    Routes(Context c, String key, Log log) {
        this.c = c.getApplicationContext();
        this.key = key;
        this.log = log;
    }

    private static final String HELP =
        "Puente PC v1.2.0 · radio K4811 (Lectura + Envío de APKs por WiFi)\n"
            + "Todas las rutas necesitan ?k=CLAVE (la que sale en la pantalla de la app).\n\n"
            + "/info                               resumen: modelo, Android, memoria, red, temperaturas, adb\n"
            + "/getprop                            propiedades del sistema\n"
            + "/packages                           apps instaladas (paquete, versión, sistema, ruta)\n"
            + "/package?p=PAQ                      detalle: actividades, servicios, receptores, permisos\n"
            + "/apk?p=PAQ                          descarga el APK de esa app (para estudiarla)\n"
            + "/settings?ns=system|global|secure   tabla de ajustes\n"
            + "/ls?path=/system/app                lista una carpeta\n"
            + "/get?path=/ruta/archivo             descarga un archivo legible\n"
            + "/proc                               memoria, CPU y procesos visibles\n"
            + "/screenshot                         captura de pantalla PNG\n"
            + "/upload?name=app.apk&install=1      (POST binario) sube un APK/archivo y abre el instalador\n"
            + "/install?name=app.apk               abre el instalador en la radio para un APK subido o en USB\n"
            + "/uninstall?p=PAQ                    abre el diálogo para desinstalar un paquete\n"
            + "/put_setting?name=K&value=V         escribe un valor en Settings.System\n"
            + "/repair_dvr                         repara archivos corruptos 1969 en el pendrive DVR\n";

    @Override
    public MiniHttp.Response handle(MiniHttp.Request r) throws Exception {
        if (r.path.equals("/") || r.path.equals("/help")) return MiniHttp.Response.text(HELP);
        if (!key.equals(r.p("k", ""))) return MiniHttp.Response.error(403, "Falta la clave ?k=");
        log.line(r.remote + "  " + r.method + " " + r.path + (r.q.size() > 1 ? " " + r.q.keySet() : ""));
        switch (r.path) {
            case "/info":
                return MiniHttp.Response.text(info());
            case "/getprop":
                return MiniHttp.Response.text(getprop());
            case "/packages":
                return MiniHttp.Response.text(packages());
            case "/package":
                return MiniHttp.Response.text(pkg(r.p("p", "")));
            case "/apk":
                return apk(r.p("p", ""));
            case "/settings":
                return MiniHttp.Response.text(settings(r.p("ns", "system")));
            case "/ls":
                return MiniHttp.Response.text(ls(r.p("path", "/")));
            case "/get":
                return get(r.p("path", ""));
            case "/proc":
                return MiniHttp.Response.text(proc());
            case "/screenshot":
                return screenshot();
            case "/upload":
                return upload(r);
            case "/install":
                return install(r.p("path", ""), r.p("name", ""));
            case "/uninstall":
                return uninstall(r.p("p", ""));
            case "/put_setting":
                return putSetting(r.p("name", ""), r.p("value", ""), r.p("broadcast", ""));
            case "/repair_dvr":
                return MiniHttp.Response.text(repairDvrUsb());
            default:
                return MiniHttp.Response.error(404, "No existe " + r.path + "\n\n" + HELP);
        }
    }

    // ------------------------------------------------------------------ envío e instalación WiFi

    public static File uploadDir() {
        File dir = new File(Environment.getExternalStorageDirectory(), "K4811/apks");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private MiniHttp.Response upload(MiniHttp.Request r) throws Exception {
        if (r.body == null || r.body.length == 0) {
            return MiniHttp.Response.error(400, "Cuerpo vacío: envía el archivo por POST (--data-binary)");
        }
        String name = r.p("name", "archivo.apk").replaceAll("[^a-zA-Z0-9._-]", "_");
        String customPath = r.p("path", "");
        File dest = !customPath.isEmpty() ? new File(customPath) : new File(uploadDir(), name);
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        try (FileOutputStream out = new FileOutputStream(dest)) {
            out.write(r.body);
            out.flush();
        }
        // También deja copia en Download para que sea fácil de ver desde cualquier explorador
        try {
            File dlDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!dlDir.exists()) dlDir.mkdirs();
            File dlCopy = new File(dlDir, name);
            if (!dlCopy.getAbsolutePath().equals(dest.getAbsolutePath())) {
                try (FileOutputStream out2 = new FileOutputStream(dlCopy)) {
                    out2.write(r.body);
                }
            }
        } catch (Exception ignored) {
        }

        boolean doInstall = "1".equals(r.p("install", name.endsWith(".apk") ? "1" : "0"));
        if (doInstall && name.toLowerCase(Locale.US).endsWith(".apk")) {
            promptInstallApk(c, dest);
        }
        return MiniHttp.Response.text("OK: guardado en " + dest.getAbsolutePath()
            + " (" + r.body.length + " bytes)"
            + (doInstall ? " -> instalador abierto en la pantalla de la radio\n" : "\n"));
    }

    private MiniHttp.Response install(String path, String name) {
        File f = null;
        if (!path.isEmpty()) {
            f = new File(path);
        } else if (!name.isEmpty()) {
            File[] candidates = {
                new File(uploadDir(), name),
                new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name),
                new File("/storage/udisk/1_INSTALAR_AHORA", name),
                new File("/storage/udisk", name),
            };
            for (File cand : candidates) {
                if (cand.isFile()) {
                    f = cand;
                    break;
                }
            }
        }
        if (f == null || !f.isFile()) {
            return MiniHttp.Response.error(404, "No se encontró el APK para instalar (" + path + " " + name + ")");
        }
        promptInstallApk(c, f);
        return MiniHttp.Response.text("OK: instalador abierto en la radio para " + f.getAbsolutePath() + "\n");
    }

    public static void promptInstallApk(Context context, File apkFile) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(Uri.fromFile(apkFile), "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception ignored) {
        }
    }

    private MiniHttp.Response uninstall(String pkg) {
        if (pkg == null || pkg.trim().isEmpty()) {
            return MiniHttp.Response.error(400, "Falta ?p=nombre.del.paquete");
        }
        try {
            Intent intent = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg.trim()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(intent);
            return MiniHttp.Response.text("OK: diálogo de desinstalación abierto en la radio para " + pkg + "\n");
        } catch (Exception e) {
            return MiniHttp.Response.error(500, "No se pudo abrir desinstalador: " + e);
        }
    }

    private MiniHttp.Response putSetting(String name, String value, String broadcast) {
        if (name == null || name.trim().isEmpty()) {
            return MiniHttp.Response.error(400, "Falta ?name=clave&value=valor");
        }
        try {
            Settings.System.putString(c.getContentResolver(), name.trim(), value);
            if (broadcast != null && !broadcast.trim().isEmpty()) {
                c.sendBroadcast(new Intent(broadcast.trim()));
            }
            return MiniHttp.Response.text("OK: Settings.System[" + name + "] = " + value + "\n");
        } catch (Exception e) {
            return MiniHttp.Response.error(500, "Error escribiendo Settings.System: " + e);
        }
    }

    public static String repairDvrUsb() {
        // Primero limpiamos cualquier carpeta HDDVR creada por error en el USB virtual de la Dashcam (donde existe VSFILE)
        for (String dashRoot : new String[]{"/storage/udisk", "/mnt/media_rw/udisk"}) {
            File dr = new File(dashRoot);
            if (new File(dr, "VSFILE").exists()) {
                deleteDirRecursive(new File(dr, "HDDVR"), 0);
            }
        }

        String[] roots = {
            "/storage/udiskh/HDDVR",
            "/mnt/media_rw/udiskh/HDDVR",
            "/storage/udisk/HDDVR",
            "/mnt/media_rw/udisk/HDDVR",
        };
        int fixedFiles = 0;
        int createdDirs = 0;
        int rescuedDirs = 0;
        String foundRoot = null;
        Date now = new Date();
        String currentMonth = new SimpleDateFormat("yyyyMM", Locale.US).format(now);
        String currentDay = new SimpleDateFormat("dd", Locale.US).format(now);
        String currentHour = new SimpleDateFormat("HH", Locale.US).format(now);

        for (String r : roots) {
            File rootDir = new File(r);
            File parent = rootDir.getParentFile();
            if (parent == null || !parent.exists() || !parent.canRead()) continue;
            // No tocar el USB virtual de la Dashcam (VSFILE)
            if (new File(parent, "VSFILE").exists()) continue;

            foundRoot = r;

            File vodka = new File(parent, "NWDVodka_K4811");
            if (!vodka.isDirectory() && vodka.length() == 0 && vodka.delete()) fixedFiles++;

            if (!rootDir.isDirectory() && rootDir.delete()) fixedFiles++;
            if (!rootDir.exists() && rootDir.mkdirs()) createdDirs++;

            // En Streaming HD2 (RecordManager), .formatflag y .nomedia son directorios creados con mkdirs()
            File flag = new File(rootDir, ".formatflag");
            if (flag.isFile()) flag.delete();
            if (!flag.exists() && flag.mkdirs()) createdDirs++;

            File nomedia = new File(rootDir, ".nomedia");
            if (nomedia.isFile()) nomedia.delete();
            if (!nomedia.exists() && nomedia.mkdirs()) createdDirs++;

            String[] subFolders = {"frontcam", "rearcam", "sensorlock", "pic"};
            for (String sub : subFolders) {
                File subDir = new File(rootDir, sub);
                if (!subDir.isDirectory() && subDir.delete()) fixedFiles++;
                if (!subDir.exists() && subDir.mkdirs()) createdDirs++;

                int[] counts = cleanCorruptRecursive(subDir, 0);
                fixedFiles += counts[0];
                createdDirs += counts[1];

                File monthDir = new File(subDir, currentMonth);
                if (!monthDir.isDirectory()) {
                    monthDir.delete();
                    monthDir.mkdirs();
                }

                // Si la entrada FAT32 para 202611 dentro de rearcam está corrupta a nivel de tabla FAT
                // (stat() falla: isDirectory=false, isFile=false, canRead=false) y vfat no permite borrarla ni crear 202611:
                // renombramos rearcam -> .rearcam_bak_*, creamos un rearcam nuevo y rescatamos las carpetas sanas (ej. 202610).
                if (!monthDir.isDirectory() || !monthDir.canRead()) {
                    File backupDir = new File(rootDir, "." + sub + "_corrupt_" + (System.currentTimeMillis() % 100000));
                    if (subDir.renameTo(backupDir)) {
                        fixedFiles++;
                        if (subDir.mkdirs()) createdDirs++;
                        rescuedDirs += rescueHealthyDirs(backupDir, subDir, 0);
                        monthDir = new File(subDir, currentMonth);
                        if (monthDir.mkdirs()) createdDirs++;
                    }
                }

                File dayDir = new File(monthDir, currentDay);
                if (!dayDir.isDirectory()) {
                    dayDir.delete();
                    if (dayDir.mkdirs()) createdDirs++;
                }

                File hourDir = new File(dayDir, currentHour);
                if (!hourDir.isDirectory()) {
                    hourDir.delete();
                    if (hourDir.mkdirs()) createdDirs++;
                }
            }
            // Basta con reparar por la primera ruta montada con permisos completos (/storage/udiskh/HDDVR)
            if (new File(rootDir, "rearcam/" + currentMonth + "/" + currentDay).isDirectory()) {
                break;
            }
        }
        if (foundRoot == null) return "No se detectó pendrive DVR en /storage/udiskh.\n";
        return "OK: Pendrive DVR (" + foundRoot + ") reparado. Entradas 1969 corregidas="
            + fixedFiles + ", carpetas creadas=" + createdDirs + ", carpetas históricas rescatadas=" + rescuedDirs + "\n";
    }

    private static int rescueHealthyDirs(File oldDir, File newDir, int depth) {
        if (oldDir == null || !oldDir.isDirectory() || !oldDir.canRead() || depth > 4) return 0;
        int rescued = 0;
        File[] children = oldDir.listFiles();
        if (children == null) return 0;
        for (File child : children) {
            if (!child.canRead() || child.lastModified() < 315532800000L) continue;
            String name = child.getName();
            File target = new File(newDir, name);
            if (child.isDirectory()) {
                if (!target.exists()) target.mkdirs();
                rescued += rescueHealthyDirs(child, target, depth + 1);
            } else if (child.isFile() && child.length() > 0) {
                if (child.renameTo(target)) rescued++;
            }
        }
        return rescued;
    }

    private static void deleteDirRecursive(File f, int depth) {
        if (f == null || !f.exists() || depth > 5) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteDirRecursive(c, depth + 1);
            }
        }
        f.delete();
    }

    private static int[] cleanCorruptRecursive(File dir, int depth) {
        int fixed = 0;
        int created = 0;
        if (dir == null || !dir.isDirectory() || depth > 5) return new int[]{0, 0};
        File[] children = dir.listFiles();
        if (children == null) return new int[]{0, 0};
        for (File child : children) {
            String name = child.getName();
            if (".formatflag".equals(name) || ".nomedia".equals(name)) continue;
            boolean numericDirName = name.matches("\\d{2}|\\d{4}|\\d{6}");
            boolean corruptFatEntry = !child.canRead() || (!child.isDirectory() && !child.isFile());
            boolean zeroOr1969 = child.length() == 0 || child.lastModified() < 315532800000L;

            if (corruptFatEntry || (child.isFile() && (numericDirName || zeroOr1969))) {
                if (child.delete()) {
                    fixed++;
                    if (numericDirName && child.mkdirs()) created++;
                }
            } else if (child.isDirectory()) {
                int[] sub = cleanCorruptRecursive(child, depth + 1);
                fixed += sub[0];
                created += sub[1];
                // Si dentro de p.ej. 202610 o 202610/26 quedaron entradas FAT corruptas imborrables,
                // rescatamos las subcarpetas sanas a un directorio limpio.
                if (hasUnreadableCorruptChildren(child)) {
                    File parent = child.getParentFile();
                    File bak = new File(parent, "." + name + "_bak_" + (System.currentTimeMillis() % 100000));
                    if (child.renameTo(bak)) {
                        fixed++;
                        File clean = new File(parent, name);
                        if (clean.mkdirs()) created++;
                        rescueHealthyDirs(bak, clean, depth + 1);
                    }
                }
            }
        }
        return new int[]{fixed, created};
    }

    private static boolean hasUnreadableCorruptChildren(File dir) {
        File[] list = dir.listFiles();
        if (list == null) return false;
        for (File f : list) {
            if (!f.canRead() || (!f.isDirectory() && !f.isFile())) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ info

    private String info() {
        StringBuilder b = new StringBuilder();
        b.append("fecha: ").append(new Date()).append('\n');
        b.append("modelo: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(" (").append(Build.DEVICE).append(", ").append(Build.BOARD).append(")\n");
        b.append("android: ").append(Build.VERSION.RELEASE).append(" API ").append(Build.VERSION.SDK_INT).append('\n');
        b.append("build: ").append(Build.DISPLAY).append('\n');
        b.append("fingerprint: ").append(Build.FINGERPRINT).append('\n');
        DisplayMetrics m = c.getResources().getDisplayMetrics();
        b.append("pantalla: ").append(m.widthPixels).append('x').append(m.heightPixels)
            .append(" dpi ").append(m.densityDpi).append('\n');
        b.append("ip: ").append(PuenteService.ipAddress(c)).append('\n');
        b.append("uptime: ").append(android.os.SystemClock.elapsedRealtime() / 60000).append(" min\n");
        b.append('\n').append(head(new File("/proc/meminfo"), 6));
        b.append("\ncarga cpu: ").append(head(new File("/proc/loadavg"), 1));
        StatFs st = new StatFs(Environment.getDataDirectory().getPath());
        b.append("data libre: ").append(st.getAvailableBytes() / 1048576).append(" MB de ")
            .append(st.getTotalBytes() / 1048576).append(" MB\n");
        b.append("\n-- propiedades clave --\n");
        for (String p : new String[]{
            "ro.product.model", "ro.product.board", "ro.board.platform", "ro.build.version.release",
            "ro.build.display.id", "persist.nwd.launcher.default", "ro.secure", "ro.debuggable",
            "ro.adb.secure", "init.svc.adbd", "service.adb.tcp.port", "persist.adb.tcp.port",
            "sys.usb.config", "persist.sys.usb.config", "sys.usb.state", "ro.boot.selinux",
        }) {
            b.append(p).append(" = ").append(SystemProps.get(p, "")).append('\n');
        }
        b.append("adb_enabled (global) = ").append(Settings.Global.getString(c.getContentResolver(), "adb_enabled")).append('\n');
        b.append("puerto 5555 abierto = ").append(portOpen(5555)).append('\n');
        b.append("\n-- temperaturas --\n");
        for (int i = 0; i < 12; i++) {
            File t = new File("/sys/class/thermal/thermal_zone" + i + "/temp");
            if (!t.canRead()) continue;
            b.append("zona ").append(i).append(" (")
                .append(head(new File("/sys/class/thermal/thermal_zone" + i + "/type"), 1).trim())
                .append("): ").append(head(t, 1).trim()).append('\n');
        }
        return b.toString();
    }

    private static String head(File f, int lines) {
        StringBuilder b = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String l;
            int n = 0;
            while ((l = r.readLine()) != null && n++ < lines) b.append(l).append('\n');
        } catch (Exception e) {
            b.append("(no se puede leer ").append(f).append(")\n");
        }
        return b.toString();
    }

    private String getprop() {
        StringBuilder b = new StringBuilder();
        for (String f : new String[]{"/system/build.prop", "/vendor/build.prop", "/product/build.prop", "/odm/etc/build.prop"}) {
            File file = new File(f);
            if (!file.canRead()) continue;
            b.append("#### ").append(f).append('\n').append(head(file, 5000)).append('\n');
        }
        return b.length() == 0 ? "(no se pudo leer build.prop)" : b.toString();
    }

    private String proc() {
        StringBuilder b = new StringBuilder();
        b.append(head(new File("/proc/meminfo"), 40)).append('\n');
        b.append(head(new File("/proc/cpuinfo"), 60)).append('\n');
        b.append("loadavg: ").append(head(new File("/proc/loadavg"), 1)).append('\n');
        android.app.ActivityManager am = (android.app.ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        b.append("RAM disponible: ").append(mi.availMem / 1048576).append(" MB de ").append(mi.totalMem / 1048576)
            .append(" MB, poca memoria=").append(mi.lowMemory).append("\n\nSERVICIOS EN MARCHA (visibles)\n");
        try {
            for (android.app.ActivityManager.RunningServiceInfo s : am.getRunningServices(300)) {
                b.append("  ").append(s.service.flattenToShortString()).append("  pid ").append(s.pid).append('\n');
            }
        } catch (Exception e) {
            b.append("  (no disponible: ").append(e).append(")\n");
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ apps

    private String packages() {
        PackageManager pm = c.getPackageManager();
        List<PackageInfo> list = pm.getInstalledPackages(0);
        StringBuilder b = new StringBuilder("# paquete\tversión\tcódigo\tsistema\tactiva\tnombre\truta\n");
        java.util.Collections.sort(list, (x, y) -> x.packageName.compareTo(y.packageName));
        for (PackageInfo p : list) {
            ApplicationInfo a = p.applicationInfo;
            boolean sys = (a.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            b.append(p.packageName).append('\t').append(p.versionName).append('\t').append(p.versionCode)
                .append('\t').append(sys ? "sistema" : "usuario").append('\t').append(a.enabled ? "sí" : "NO")
                .append('\t').append(a.loadLabel(pm)).append('\t').append(a.sourceDir).append('\n');
        }
        return b.toString();
    }

    private String pkg(String p) throws Exception {
        PackageManager pm = c.getPackageManager();
        PackageInfo i = pm.getPackageInfo(p, PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES
            | PackageManager.GET_RECEIVERS | PackageManager.GET_PROVIDERS | PackageManager.GET_PERMISSIONS);
        StringBuilder b = new StringBuilder();
        b.append(p).append("  v").append(i.versionName).append(" (").append(i.versionCode).append(")\n");
        b.append("uid: ").append(i.applicationInfo.uid).append("  sharedUser: ").append(i.sharedUserId).append('\n');
        b.append("apk: ").append(i.applicationInfo.sourceDir).append('\n');
        b.append("targetSdk: ").append(i.applicationInfo.targetSdkVersion).append('\n');
        Intent launch = pm.getLaunchIntentForPackage(p);
        b.append("abre con: ").append(launch == null ? "-" : launch.getComponent()).append('\n');
        if (i.activities != null) {
            b.append("\nACTIVIDADES\n");
            for (ActivityInfo a : i.activities) b.append("  ").append(a.name).append(a.exported ? "  (exportada)" : "").append('\n');
        }
        if (i.services != null) {
            b.append("\nSERVICIOS\n");
            for (ServiceInfo s : i.services) b.append("  ").append(s.name).append(s.exported ? "  (exportado)" : "").append('\n');
        }
        if (i.receivers != null) {
            b.append("\nRECEPTORES\n");
            for (ActivityInfo a : i.receivers) b.append("  ").append(a.name).append('\n');
        }
        if (i.providers != null) {
            b.append("\nPROVEEDORES\n");
            for (android.content.pm.ProviderInfo pr : i.providers) b.append("  ").append(pr.name).append("  ").append(pr.authority).append('\n');
        }
        if (i.requestedPermissions != null) {
            b.append("\nPERMISOS\n");
            for (String s : i.requestedPermissions) b.append("  ").append(s).append('\n');
        }
        return b.toString();
    }

    private MiniHttp.Response apk(String p) throws Exception {
        ApplicationInfo a = c.getPackageManager().getApplicationInfo(p, 0);
        File f = new File(a.sourceDir);
        if (!f.canRead()) return MiniHttp.Response.error(403, "No se puede leer " + f);
        MiniHttp.Response r = new MiniHttp.Response();
        r.type = "application/vnd.android.package-archive";
        r.file = f;
        r.fileName = p + ".apk";
        return r;
    }

    // ------------------------------------------------------------------ ajustes

    private String settings(String ns) {
        Uri uri = "global".equals(ns) ? Settings.Global.CONTENT_URI
            : "secure".equals(ns) ? Settings.Secure.CONTENT_URI : Settings.System.CONTENT_URI;
        StringBuilder b = new StringBuilder();
        try (Cursor cur = c.getContentResolver().query(uri, new String[]{"name", "value"}, null, null, "name")) {
            if (cur == null) return "(sin acceso)";
            while (cur.moveToNext()) b.append(cur.getString(0)).append(" = ").append(cur.getString(1)).append('\n');
        } catch (Exception e) {
            b.append("error: ").append(e);
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ archivos

    private String ls(String path) {
        File d = new File(path);
        File[] list = d.listFiles();
        if (list == null) return "(no se puede listar " + path + ")\n";
        Arrays.sort(list);
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        StringBuilder b = new StringBuilder();
        for (File x : list) {
            b.append(x.isDirectory() ? "d " : "- ").append(x.canRead() ? "r " : "  ")
                .append(String.format(Locale.US, "%10d ", x.length())).append(f.format(new Date(x.lastModified())))
                .append("  ").append(x.getName()).append('\n');
        }
        return b.toString();
    }

    private MiniHttp.Response get(String path) {
        File f = new File(path);
        if (!f.isFile() || !f.canRead()) return MiniHttp.Response.error(404, "No se puede leer " + path);
        // Los archivos de /sys y /proc reportan length=4096 pero devuelven menos bytes; los leemos en memoria
        if (path.startsWith("/sys/") || path.startsWith("/proc/")) {
            try (FileInputStream in = new FileInputStream(f)) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                MiniHttp.Response r = new MiniHttp.Response();
                r.type = "text/plain; charset=utf-8";
                r.bytes = bos.toByteArray();
                return r;
            } catch (Exception e) {
                return MiniHttp.Response.error(500, "Error leyendo " + path + ": " + e);
            }
        }
        MiniHttp.Response r = new MiniHttp.Response();
        r.type = "application/octet-stream";
        r.file = f;
        r.fileName = f.getName();
        return r;
    }

    // ------------------------------------------------------------------ pantalla

    private MiniHttp.Response screenshot() {
        byte[] png = ScreenCapture.png();
        if (png == null) {
            return MiniHttp.Response.error(409, "Captura no permitida todavía: en la radio abre Puente PC y toca "
                + "«Permitir captura de pantalla».");
        }
        MiniHttp.Response r = new MiniHttp.Response();
        r.type = "image/png";
        r.bytes = png;
        return r;
    }

    static boolean portOpen(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 400);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
