package com.k4811.common.system;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import com.k4811.common.Apps;
import com.k4811.common.R;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Head-unit apps that K4811 only launches (they don't become our own screens): AUX, video,
 * 360 camera, Z-Link (CarPlay / Android Auto), Streaming HD2, Traffic Safety Guard and NWD menus.
 */
public final class ExternalApps {
    private ExternalApps() {
    }

    public static final String PKG_AUX = "com.nwd.auxin";
    public static final String PKG_VIDEO = "com.nwd.android.video.ui";
    public static final String PKG_AVM = "com.ivicar.avm";
    public static final String PKG_CARKIT_360 = "com.nwd.carkit360camera";
    public static final String PKG_CAR_SETTING = "com.android.car.setting";
    public static final String PKG_FACTORY = "com.nwd.factory.setting";
    /** Z-Link: wired/wireless CarPlay and Android Auto on NWD units. */
    public static final String PKG_ZLINK = "com.zjinnova.zlink";
    /** Streaming HD2: Grabador DVR Trasero/Frontal NWD (graba en /storage/udiskh/HDDVR). */
    public static final String PKG_HD2_CAM = "com.nwd.carkithd2cam";
    /** Traffic Safety Guard: Dashcam USB con tarjeta microSD (/storage/udisk). */
    public static final String PKG_TRAFFIC_GUARD = "com.fvision.camera";

    private static final String HOME_HORIZONTAL = ".home_horizontalActivity";
    private static final String ZLINK_MAIN = "com.zjinnova.android.zlink.features.main.MainActivity";
    private static final String AVM_MAIN = "com.ivicar.modules.main.view.MainNowadaActivity";
    private static final String AVM_SETTINGS = "com.ivicar.modules.setting.view.SettingActivity";
    private static final String AVM_CALIBRATE = "com.ivicar.modules.calibrate.view.CalibrateActivity";

    /** Opens the 360 without changing the view (extra_view = -1). Dynamic receiver: the AVM process must be alive. */
    private static final String ACTION_360_IN = "com.nwd.action.360.activity.in";
    private static final String EXTRA_VIEW = "extra_view";
    /** If the screen doesn't leave the foreground within this time, the broadcast was not handled. */
    private static final long AVM_FALLBACK_MS = 1200;

    /** Default factory menu password; {@code Settings.System nwd_factory_password} takes precedence. */
    private static final String FACTORY_PASSWORD = "1617";

    public static boolean installed(Context c, String pkg) {
        try {
            c.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    public static boolean hasAux(Context c) {
        return installed(c, PKG_AUX);
    }

    public static boolean hasVideo(Context c) {
        return installed(c, PKG_VIDEO);
    }

    public static boolean hasZlink(Context c) {
        return installed(c, PKG_ZLINK);
    }

    public static boolean hasAvm(Context c) {
        return installed(c, PKG_AVM);
    }

    public static boolean has360(Context c) {
        return hasAvm(c) || installed(c, PKG_CARKIT_360);
    }

    public static void openAux(Context c) {
        repairHd2DvrDisk(c);
        open(c, PKG_AUX, PKG_AUX + HOME_HORIZONTAL);
    }

    /**
     * Repara el pendrive DVR (/storage/udiskh/HDDVR) y abre Streaming HD2 (com.nwd.carkithd2cam).
     */
    public static boolean openHd2Cam(Context c) {
        repairHd2DvrDisk(c);
        try {
            Settings.System.putInt(c.getContentResolver(), "key_auxin_camerastate", 0);
        } catch (Exception ignored) {
        }
        try {
            SystemProps.requestSet(c, "persist.vendor.sensor.back.channel", "0");
        } catch (Exception ignored) {
        }
        Intent launch = c.getPackageManager().getLaunchIntentForPackage(PKG_HD2_CAM);
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                c.startActivity(launch);
                return true;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    /**
     * Abre la app Traffic Safety Guard (com.fvision.camera) de la Dashcam USB.
     */
    public static boolean openTrafficGuard(Context c) {
        Intent launch = c.getPackageManager().getLaunchIntentForPackage(PKG_TRAFFIC_GUARD);
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                c.startActivity(launch);
                return true;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    /**
     * Repara en segundo plano la estructura FAT32 de /storage/udiskh/HDDVR para que Streaming HD2
     * pueda grabar sin bloquearse por entradas corruptas de 0 bytes (ej. rearcam/202611 con fecha 1969),
     * rescatando automáticamente las carpetas sanas (ej. 202610).
     */
    public static void repairHd2DvrDisk(Context c) {
        final Context appCtx = c.getApplicationContext();
        new Thread(() -> {
            try {
                // 1. Limpiar carpeta accidental en /storage/udisk si es el disco virtual de Traffic Safety Guard (VSFILE)
                File udisk = new File("/storage/udisk");
                if (new File(udisk, "VSFILE").exists() && new File(udisk, "HDDVR").exists()) {
                    deleteEmptyOrCorruptRecursive(new File(udisk, "HDDVR"));
                }

                // 2. Asegurar que dvr_path apunte a udiskh si está montado
                File udiskh = new File("/storage/udiskh");
                if (!udiskh.exists() || !udiskh.isDirectory()) {
                    udiskh = new File("/mnt/media_rw/udiskh");
                }
                if (!udiskh.exists() || !udiskh.isDirectory()) return;

                if (appCtx != null) {
                    try {
                        Settings.System.putString(appCtx.getContentResolver(), "dvr_path", "/mnt/media_rw/udiskh");
                    } catch (Exception ignored) {
                    }
                }

                File hddvr = new File(udiskh, "HDDVR");
                if (!hddvr.exists()) hddvr.mkdirs();
                new File(hddvr, ".formatflag").mkdirs();
                new File(hddvr, ".nomedia").mkdirs();

                String ym = new SimpleDateFormat("yyyyMM", Locale.US).format(new Date());
                String dd = new SimpleDateFormat("dd", Locale.US).format(new Date());
                String hh = new SimpleDateFormat("HH", Locale.US).format(new Date());

                String[] subdirs = {"rearcam", "frontcam", "sensorlock", "pic"};
                for (String sub : subdirs) {
                    File dir = new File(hddvr, sub);
                    if (dir.exists() && (!dir.isDirectory() || !dir.canRead())) {
                        if (!dir.delete()) {
                            dir.renameTo(new File(hddvr, "." + sub + "_bad_" + (System.currentTimeMillis() % 100000)));
                        }
                    }
                    if (!dir.exists()) dir.mkdirs();

                    // Verificar si el subdirectorio del mes actual (ej. rearcam/202611) es una entrada FAT32 corrupta
                    File ymDir = new File(dir, ym);
                    boolean ymCorrupt = ymDir.exists() && (!ymDir.isDirectory() || !ymDir.canRead());
                    if (!ymCorrupt && dir.isDirectory()) {
                        File[] children = dir.listFiles();
                        if (children != null) {
                            for (File ch : children) {
                                if (!ch.canRead() || (!ch.isDirectory() && !ch.isFile())) {
                                    if (!ch.delete()) {
                                        ymCorrupt = true;
                                        break;
                                    }
                                }
                            }
                        }
                    }

                    if (ymCorrupt && !ymDir.delete()) {
                        // En FAT32 sin root no se puede borrar el inodo corrupto, pero sí renombrar el padre y rescatar lo sano
                        File quarantine = new File(hddvr, "." + sub + "_corrupt_" + (System.currentTimeMillis() % 100000));
                        if (dir.renameTo(quarantine)) {
                            File freshDir = new File(hddvr, sub);
                            freshDir.mkdirs();
                            File[] oldItems = quarantine.listFiles();
                            if (oldItems != null) {
                                for (File item : oldItems) {
                                    if (item.canRead() && (item.isDirectory() || (item.isFile() && item.length() > 0))) {
                                        item.renameTo(new File(freshDir, item.getName()));
                                    }
                                }
                            }
                            dir = freshDir;
                        }
                    }

                    cleanCorruptEntriesRecursive(dir, 0);
                    new File(dir, ".nomedia").mkdirs();
                    if ("rearcam".equals(sub) || "frontcam".equals(sub)) {
                        new File(dir, ym + "/" + dd + "/" + hh).mkdirs();
                    }
                }
            } catch (Exception ignored) {
            }
        }, "K4811-DvrRepair").start();
    }

    private static void cleanCorruptEntriesRecursive(File dir, int depth) {
        if (dir == null || depth > 4 || !dir.exists() || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String name = f.getName();
            if (".nomedia".equals(name) || ".formatflag".equals(name)) continue;
            boolean badDate = f.lastModified() <= 315532800000L; // <= 1980
            boolean neither = !f.isDirectory() && !f.isFile();
            boolean emptyFile = f.isFile() && f.length() == 0;
            if (!f.canRead() || neither || (emptyFile && badDate)) {
                f.delete();
            } else if (f.isDirectory()) {
                cleanCorruptEntriesRecursive(f, depth + 1);
            }
        }
    }

    private static void deleteEmptyOrCorruptRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteEmptyOrCorruptRecursive(c);
            }
        }
        if (f.isDirectory() || f.length() == 0) {
            f.delete();
        }
    }

    public static void openVideo(Context c) {
        open(c, PKG_VIDEO, PKG_VIDEO + HOME_HORIZONTAL);
    }

    public static void openZlink(Context c) {
        open(c, PKG_ZLINK, ZLINK_MAIN);
    }

    /**
     * 360 camera: with the ivicar AVM, requests it via broadcast (opens on the last view). If the
     * current screen stays in front, the AVM process wasn't alive: opens via the launcher Activity.
     */
    public static void open360(Activity from) {
        if (hasAvm(from)) {
            Intent in = new Intent(ACTION_360_IN);
            in.putExtra(EXTRA_VIEW, -1);
            from.sendBroadcast(in);
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (from.isFinishing() || !from.hasWindowFocus()) return;
                open(from, PKG_AVM, AVM_MAIN);
            }, AVM_FALLBACK_MS);
            return;
        }
        open(from, PKG_CARKIT_360, PKG_CARKIT_360 + HOME_HORIZONTAL);
    }

    public static void open360Settings(Context c) {
        open(c, PKG_AVM, AVM_SETTINGS);
    }

    public static void open360Calibration(Context c) {
        open(c, PKG_AVM, AVM_CALIBRATE);
    }

    /**
     * Config. Veículo de fábrica. {@code page} (opcional) vai no extra ref0, ex.: setNaviPath.
     * Se o app original ({@link #PKG_CAR_SETTING}) não estiver instalado, abre o das configurações
     * da suíte ({@link Apps#SETTINGS}).
     */
    public static void openCarSettings(Context c, String page) {
        if (installed(c, PKG_CAR_SETTING)) {
            Intent i = component(PKG_CAR_SETTING, PKG_CAR_SETTING + HOME_HORIZONTAL);
            if (page != null) i.putExtra("ref0", page);
            start(c, i, PKG_CAR_SETTING);
            return;
        }
        start(c, component(Apps.SETTINGS, "com.k4811.car.setting.SettingsActivity"), Apps.SETTINGS);
    }

    public static void openFactoryMenu(Context c) {
        open(c, PKG_FACTORY, PKG_FACTORY + ".LoginActivity");
    }

    public static String factoryPassword(Context c) {
        String custom = Settings.System.getString(c.getContentResolver(), "nwd_factory_password");
        return custom == null || custom.trim().isEmpty() ? FACTORY_PASSWORD : custom.trim();
    }

    public static void openAndroidSettings(Context c) {
        open(c, "com.android.settings", "com.android.settings.Settings");
    }

    /** Opens the Activity; if it isn't exported or doesn't exist, falls back to the package's launcher Activity. */
    private static void open(Context c, String pkg, String cls) {
        start(c, component(pkg, cls), pkg);
    }

    private static Intent component(String pkg, String cls) {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.setComponent(new ComponentName(pkg, cls));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    private static void start(Context c, Intent i, String pkg) {
        try {
            c.startActivity(i);
            return;
        } catch (Exception ignored) {
            // Activity not exported (e.g. internal AVM screens) or missing on this head unit.
        }
        Intent launch = c.getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                c.startActivity(launch);
                Toast.makeText(c, c.getString(R.string.adv_toast_opened_launcher), Toast.LENGTH_LONG).show();
                return;
            } catch (Exception ignored) {
            }
        }
        Toast.makeText(c, c.getString(R.string.adv_toast_app_missing, pkg), Toast.LENGTH_LONG).show();
    }
}
