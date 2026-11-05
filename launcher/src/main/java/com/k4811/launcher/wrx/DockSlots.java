package com.k4811.launcher.wrx;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.widget.Toast;

import com.k4811.common.system.ExternalApps;
import com.k4811.common.ui.DockNavigator;
import com.k4811.common.ui.DockView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Botones del dock WRX. Cada uno es un id fijo (radio, cámara frontal, dashcam...) o "app:paquete"
 * para cualquier app instalada. El orden se guarda en las preferencias del launcher.
 */
public final class DockSlots {
    private DockSlots() {
    }

    public static final int MAX = 10;
    public static final String DEFAULT =
        "home,radio,bt_music,audio,registry,front_cam,rear_cam,recordings,carplay,apps";

    /** id, nombre corto, ícono. */
    public static final Object[][] BUILTIN = {
        {"home", "INICIO", Glyph.Kind.HOME},
        {"radio", "RADIO", Glyph.Kind.RADIO},
        {"bt_music", "MÚSICA BT", Glyph.Kind.BLUETOOTH},
        {"audio", "AUDIO", Glyph.Kind.EQ},
        {"registry", "REGISTRO", Glyph.Kind.TRIP},
        {"recordings", "GRABACIONES", Glyph.Kind.VIDEO},
        {"front_cam", "CÁM. FRONTAL", Glyph.Kind.CAM_FRONT},
        {"rear_cam", "CÁM. TRASERA", Glyph.Kind.CAM_REAR},
        {"dashcam", "DASHCAM", Glyph.Kind.DASHCAM},
        {"carplay", "CARPLAY", Glyph.Kind.CARPLAY},
        {"music", "MÚSICA", Glyph.Kind.MUSIC},
        {"phone", "TELÉFONO", Glyph.Kind.PHONE},
        {"cam360", "CÁMARA 360", Glyph.Kind.CAM360},
        {"youtube", "YOUTUBE", Glyph.Kind.YOUTUBE},
        {"maps", "MAPAS", Glyph.Kind.MAPS},
        {"video", "VIDEO", Glyph.Kind.VIDEO},
        {"aux", "AUX", Glyph.Kind.AUX},
        {"eq", "ECUALIZ.", Glyph.Kind.EQ},
        {"sound_test", "TEST AUDIO", Glyph.Kind.EQ},
        {"maintenance", "MANTENCIÓN", Glyph.Kind.WRENCH},
        {"apps", "APPS", Glyph.Kind.APPS},
    };

    private static final String[] YOUTUBE = {
        "com.google.android.youtube", "app.revanced.android.youtube", "com.vanced.android.youtube",
        "com.liskovsoft.smarttubetv.beta", "com.liskovsoft.smarttubetv", "org.schabi.newpipe",
    };
    private static final String[] MAPS = {"com.waze", "com.google.android.apps.maps"};
    private static final String[] DASHCAM = {
        "com.fvision.camera", "com.nwd.dvr.nomal", "com.nwd.dvr", "com.nwd.carkithd2cam"
    };
    private static final String[] REAR = {"com.nwd.carkithd2cam", "com.nwd.auxin.rear"};

    public static List<String> load(Context c) {
        String raw = WrxPrefs.get(c).getString(WrxPrefs.DOCK, DEFAULT);
        List<String> out = new ArrayList<>();
        for (String s : raw.split(",")) if (!s.trim().isEmpty()) out.add(s.trim());
        if (out.isEmpty()) out.addAll(Arrays.asList(DEFAULT.split(",")));
        return out;
    }

    public static void save(Context c, List<String> ids) {
        StringBuilder b = new StringBuilder();
        for (String s : ids) {
            if (b.length() > 0) b.append(',');
            b.append(s);
        }
        WrxPrefs.get(c).edit().putString(WrxPrefs.DOCK, b.toString()).apply();
    }

    public static boolean isApp(String id) {
        return id.startsWith("app:");
    }

    public static String label(Context c, String id) {
        if (isApp(id)) {
            String pkg = id.substring(4);
            try {
                PackageManager pm = c.getPackageManager();
                String l = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
                return l.length() > 12 ? l.substring(0, 11).toUpperCase(Locale.getDefault()) + "…"
                    : l.toUpperCase(Locale.getDefault());
            } catch (Exception e) {
                return "APP";
            }
        }
        for (Object[] b : BUILTIN) if (b[0].equals(id)) return (String) b[1];
        return id.toUpperCase(Locale.US);
    }

    public static Glyph.Kind glyph(String id) {
        for (Object[] b : BUILTIN) if (b[0].equals(id)) return (Glyph.Kind) b[2];
        return Glyph.Kind.APP;
    }

    /** Abre lo que corresponde al botón. false = no hay app instalada para eso. */
    public static boolean open(Activity a, String id) {
        switch (id) {
            case "home":
                return true;
            case "radio":
                DockNavigator.navigate(a, null, DockView.Item.RADIO);
                return true;
            case "music":
                DockNavigator.navigate(a, null, DockView.Item.MUSIC);
                return true;
            case "bt_music":
                DockNavigator.navigate(a, null, DockView.Item.BT_MUSIC);
                return true;
            case "phone":
                DockNavigator.navigate(a, null, DockView.Item.PHONE);
                return true;
            case "eq":
                DockNavigator.navigate(a, null, DockView.Item.EQ);
                return true;
            case "apps":
                DockNavigator.navigate(a, null, DockView.Item.APPS);
                return true;
            case "carplay":
                if (!ExternalApps.hasZlink(a)) return false;
                ExternalApps.openZlink(a);
                return true;
            case "cam360":
                ExternalApps.open360(a);
                return true;
            case "video":
                ExternalApps.openVideo(a);
                return true;
            case "aux":
                ExternalApps.openAux(a);
                return true;
            case "front_cam":
                ExternalApps.openAux(a);
                return true;
            case "rear_cam":
                if (ExternalApps.openHd2Cam(a)) return true;
                return launchFirst(a, REAR, "streaming", "hd2", "trasera", "rear");
            case "dashcam":
                if (ExternalApps.openTrafficGuard(a)) return true;
                return launchFirst(a, DASHCAM, "traffic", "guard", "fvision", "dvr", "dashcam", "grabador");
            case "audio":
            case "sound_test":
                a.startActivity(new Intent(a, WrxSoundTestActivity.class));
                return true;
            case "registry":
                a.startActivity(new Intent(a, WrxRegistryActivity.class));
                return true;
            case "maintenance":
                a.startActivity(new Intent(a, WrxRegistryActivity.class));
                return true;
            case "youtube":
                return launchFirst(a, YOUTUBE, "youtube");
            case "maps":
                return launchFirst(a, MAPS, "waze", "maps", "mapas");
            case "recordings":
                return launch(a, CarWatchService.PKG_RECORDINGS);
            default:
                if (isApp(id)) return launch(a, id.substring(4));
                return false;
        }
    }

    public static void openOrExplain(Activity a, String id) {
        if (!open(a, id)) {
            Toast.makeText(a, "No encontré la app de " + label(a, id).toLowerCase(Locale.getDefault())
                + ". Mantén presionado el botón para elegirla.", Toast.LENGTH_LONG).show();
        }
    }

    private static boolean launch(Context c, String pkg) {
        Intent i = c.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) return false;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Primero los paquetes conocidos; si no, una app cuyo nombre contenga alguna palabra. */
    private static boolean launchFirst(Context c, String[] pkgs, String... words) {
        for (String p : pkgs) if (launch(c, p)) return true;
        for (ResolveInfo r : launchable(c)) {
            String l = r.loadLabel(c.getPackageManager()).toString().toLowerCase(Locale.getDefault());
            for (String w : words) if (l.contains(w)) return launch(c, r.activityInfo.packageName);
        }
        return false;
    }

    public static List<ResolveInfo> launchable(Context c) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> list = c.getPackageManager().queryIntentActivities(i, 0);
        List<ResolveInfo> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (ResolveInfo r : list) {
            String p = r.activityInfo.packageName;
            if (p.equals(c.getPackageName()) || !seen.add(p)) continue;
            out.add(r);
        }
        final PackageManager pm = c.getPackageManager();
        java.util.Collections.sort(out, (x, y) -> x.loadLabel(pm).toString()
            .compareToIgnoreCase(y.loadLabel(pm).toString()));
        return out;
    }
}
