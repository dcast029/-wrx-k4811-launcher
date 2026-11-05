package com.k4811.launcher.wrx;

import android.app.Activity;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.k4811.common.system.NwdSettings;
import com.k4811.common.ui.Ui;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Explorador CAN: muestra en vivo todo lo que la MCU de la radio deja en Settings.System (y los
 * avisos que manda), marca en dorado lo que cambia y guarda un registro en K4811/ para revisarlo
 * en el PC. También calibra el freno de mano y la reversa: se pone y se quita, y se busca la
 * clave/bit que cambió.
 */
public class CanExplorerActivity extends Activity {

    private static final String[] CAR_WORDS = {
        "mcu", "can", "car", "nwd", "fan", "ill", "brake", "break", "back", "reverse", "acc", "door",
        "temp", "volt", "light", "speed", "gear", "park", "radar", "steer",
    };
    private static final String[] ACTIONS = {
        "com.nwd.action.ACTION_CAN_BATTERY_VOLTAGE", "com.nwd.action.ACTION_HAND_BREAK_STATE_CHANGE",
        "com.nwd.action.ACTION_MCU_STATE_CHANGE", "com.nwd.ACTION_MUTE_STATE_CHANGE",
        "com.nwd.action.ACTION_CHANGE_SOURCE", "com.nwd.action.ACTION_APP_IN_OUT",
        "com.android.action_update_backcar_setup",
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private WrxTheme t;
    private LinearLayout list;
    private TextView filterBtn, logView;
    private boolean onlyCar = true;
    private Map<String, String> last = new LinkedHashMap<>();
    private final Map<String, Long> changedAt = new HashMap<>();
    private final Map<String, TextView[]> rows = new HashMap<>();
    private final List<String> log = new ArrayList<>();
    private Dialog dialog;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 700);
        }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            StringBuilder b = new StringBuilder(intent.getAction());
            if (intent.getExtras() != null) {
                for (String k : intent.getExtras().keySet()) b.append(' ').append(k).append('=').append(intent.getExtras().get(k));
            }
            addLog("aviso " + b);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Ui.applyDesignDensity(this);
        super.onCreate(savedInstanceState);
        WrxTheme.loadFonts(this);
        int mode = WrxPrefs.themeMode(this);
        t = new WrxTheme(mode == 2 || (mode == 0 && NwdSettings.headlightsOn(this)));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(t.bg);
        WrxTheme.pad(root, 28, 18, 28, 16);
        setContentView(root);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(head, new LinearLayout.LayoutParams(-1, px(60)));
        TextView back = WrxDialog.button(this, t, "‹  Volver", false);
        head.addView(back, new LinearLayout.LayoutParams(px(130), px(52)));
        back.setOnClickListener(v -> finish());
        TextView title = t.label(this, "Explorador CAN", 26, WrxTheme.bodyBold(), t.text);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1);
        tlp.leftMargin = px(18);
        head.addView(title, tlp);
        filterBtn = headButton(head, "", false);
        filterBtn.setOnClickListener(v -> {
            onlyCar = !onlyCar;
            rows.clear();
            list.removeAllViews();
            refresh();
        });
        headButton(head, "Calibrar freno", false).setOnClickListener(v -> calibrate(Signal.HANDBRAKE,
            "Pon el freno de mano", "Ahora suéltalo", "Ponlo otra vez"));
        headButton(head, "Calibrar reversa", false).setOnClickListener(v -> calibrate(Signal.REVERSE,
            "Con el motor encendido y el pie en el freno, pon reversa", "Saca la reversa (neutro)", "Pon reversa otra vez"));
        headButton(head, "Guardar registro", true).setOnClickListener(v -> saveLog());

        LinearLayout body = new LinearLayout(this);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, 0, 1);
        blp.topMargin = px(12);
        root.addView(body, blp);

        ScrollView sv = new ScrollView(this);
        sv.setBackground(t.card(this, 18));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        WrxTheme.pad(list, 16, 10, 16, 10);
        sv.addView(list);
        body.addView(sv, new LinearLayout.LayoutParams(0, -1, 3));

        ScrollView lsv = new ScrollView(this);
        lsv.setBackground(t.card(this, 18));
        logView = t.label(this, "", 12.5f, WrxTheme.body(), t.muted);
        logView.setSingleLine(false);
        WrxTheme.pad(logView, 14, 12, 14, 12);
        lsv.addView(logView);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0, -1, 2);
        llp.leftMargin = px(12);
        body.addView(lsv, llp);

        IntentFilter f = new IntentFilter();
        for (String a : ACTIONS) f.addAction(a);
        registerReceiver(receiver, f);
        last = Signal.snapshot(this);
        addLog("inicio: " + last.size() + " claves en Settings.System");
        addLog("Prueba: abre/cierra puertas, luces, freno de mano, reversa, intermitentes… y mira qué cambia.");
    }

    private TextView headButton(LinearLayout head, String text, boolean primary) {
        TextView b = WrxDialog.button(this, t, text, primary);
        b.setTextSize(15);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, px(52));
        lp.leftMargin = px(10);
        head.addView(b, lp);
        return b;
    }

    @Override
    protected void onResume() {
        super.onResume();
        Ui.hideSystemBars(getWindow());
        handler.post(poll);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(poll);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(receiver);
        } catch (Exception ignored) {
        }
        if (dialog != null) dialog.dismiss();
        super.onDestroy();
    }

    private int px(float v) {
        return WrxTheme.px(this, v);
    }

    private static boolean isCarKey(String k) {
        String l = k.toLowerCase(Locale.US);
        for (String w : CAR_WORDS) if (l.contains(w)) return true;
        return false;
    }

    private void refresh() {
        filterBtn.setText(onlyCar ? "Ver todo" : "Solo del auto");
        Map<String, String> now = Signal.snapshot(this);
        long el = SystemClock.elapsedRealtime();
        for (Map.Entry<String, String> e : now.entrySet()) {
            String k = e.getKey();
            String old = last.get(k);
            String v = e.getValue();
            if (last.containsKey(k) && !eqs(old, v)) {
                changedAt.put(k, el);
                addLog(k + ": " + old + " → " + v + bitsChanged(old, v));
            }
        }
        last = now;
        for (Map.Entry<String, String> e : now.entrySet()) {
            String k = e.getKey();
            if (onlyCar && !isCarKey(k)) continue;
            TextView[] r = rows.get(k);
            if (r == null) {
                r = addRow(k);
                rows.put(k, r);
            }
            String v = e.getValue() == null ? "null" : e.getValue();
            Long n = Signal.number(v);
            r[1].setText(n != null && n > 1 ? v + "   (" + Long.toBinaryString(n) + ")" : v);
            Long at = changedAt.get(k);
            boolean hot = at != null && el - at < 10000;
            r[0].setTextColor(hot ? t.gold : t.muted);
            r[1].setTextColor(hot ? t.gold2 : t.text);
        }
    }

    private static boolean eqs(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static String bitsChanged(String a, String b) {
        Long x = Signal.number(a), y = Signal.number(b);
        if (x == null || y == null) return "";
        long d = x ^ y;
        if (d == 0) return "";
        StringBuilder s = new StringBuilder("  [bits");
        for (int i = 0; i < 32; i++) {
            if ((d & (1L << i)) != 0) s.append(' ').append(i).append((y & (1L << i)) != 0 ? "↑" : "↓");
        }
        return s.append(']').toString();
    }

    private TextView[] addRow(String key) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView k = t.label(this, key, 14, WrxTheme.bodyMedium(), t.muted);
        TextView v = t.label(this, "", 14, WrxTheme.numMedium(), t.text);
        r.addView(k, new LinearLayout.LayoutParams(0, -2, 1));
        r.addView(v, new LinearLayout.LayoutParams(0, -2, 1));
        list.addView(r, new LinearLayout.LayoutParams(-1, px(34)));
        return new TextView[]{k, v};
    }

    private void addLog(String s) {
        log.add(clock.format(new Date()) + "  " + s);
        if (log.size() > 600) log.remove(0);
        StringBuilder b = new StringBuilder();
        for (int i = log.size() - 1; i >= 0 && i >= log.size() - 80; i--) b.append(log.get(i)).append('\n');
        logView.setText(b.toString());
    }

    // ------------------------------------------------------------------ calibración

    private Map<String, String> snapA, snapB;

    private void calibrate(String name, String step1, String step2, String step3) {
        step(name, 1, step1, () -> {
            snapA = Signal.snapshot(this);
            step(name, 2, step2, () -> {
                snapB = Signal.snapshot(this);
                step(name, 3, step3, () -> finishCalibration(name, Signal.snapshot(this)));
            });
        });
    }

    private void step(String name, int n, String text, Runnable next) {
        String label = Signal.HANDBRAKE.equals(name) ? "freno de mano" : "reversa";
        dialog = WrxDialog.list(this, t, "Calibrar " + label + " · paso " + n + " de 3\n" + text + " y toca Listo.",
            new String[]{"Listo"}, i -> handler.postDelayed(next, 700));
    }

    private void finishCalibration(String name, Map<String, String> snapC) {
        List<Signal> found = new ArrayList<>();
        for (Map.Entry<String, String> e : snapA.entrySet()) {
            String k = e.getKey();
            Long a = Signal.number(e.getValue()), b = Signal.number(snapB.get(k)), c = Signal.number(snapC.get(k));
            if (a == null || b == null || c == null) continue;
            long diff = a ^ b;
            if (diff == 0) continue;
            for (int i = 0; i < 31; i++) {
                long bit = 1L << i;
                if ((diff & bit) == 0) continue;
                if ((a & bit) != (c & bit)) continue; // no volvió igual: no es esta
                found.add(new Signal(k, (int) bit, (a & bit) != 0));
            }
        }
        String label = Signal.HANDBRAKE.equals(name) ? "freno de mano" : "reversa";
        if (found.isEmpty()) {
            addLog("calibración " + label + ": no cambió nada");
            dialog = WrxDialog.list(this, t, "No vi ningún cambio con el " + label + ". Puede que la radio no lo "
                    + "reciba (revisa que el cable BRAKE/PARKING o el de reversa estén conectados).",
                new String[]{"Entendido"}, i -> {
                });
            return;
        }
        String[] opts = new String[found.size()];
        for (int i = 0; i < found.size(); i++) opts[i] = found.get(i).describe();
        addLog("calibración " + label + ": candidatos " + java.util.Arrays.toString(opts));
        dialog = WrxDialog.list(this, t, found.size() == 1 ? "Encontré la señal del " + label + ". ¿Guardarla?"
            : "Cambiaron varias cosas. Elige la del " + label + ":", opts, i -> {
                Signal.save(this, name, found.get(i));
                CarWatchService.reloadSignals(this);
                addLog("guardado " + label + " = " + found.get(i).describe());
                Toast.makeText(this, "Guardado: " + found.get(i).describe(), Toast.LENGTH_LONG).show();
            });
    }

    // ------------------------------------------------------------------ registro

    private void saveLog() {
        StringBuilder b = new StringBuilder();
        b.append("Explorador CAN · ").append(new Date()).append("\n\n== Settings.System ==\n");
        for (Map.Entry<String, String> e : Signal.snapshot(this).entrySet()) {
            b.append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
        }
        b.append("\n== Cambios y avisos ==\n");
        for (String l : log) b.append(l).append('\n');
        Signal hb = Signal.load(this, Signal.HANDBRAKE), rv = Signal.load(this, Signal.REVERSE);
        b.append("\nfreno de mano: ").append(hb == null ? "sin calibrar" : hb.describe());
        b.append("\nreversa: ").append(rv == null ? "sin calibrar" : rv.describe()).append('\n');
        String name = "can_explorer_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".txt";
        List<String> saved = new ArrayList<>();
        File dir = new File(Environment.getExternalStorageDirectory(), "K4811");
        if (write(new File(dir, name), b.toString())) saved.add("K4811/" + name);
        // Además, a cualquier pendrive conectado (si el sistema lo permite).
        File[] vols = new File("/storage").listFiles();
        if (vols != null) {
            for (File v : vols) {
                String n = v.getName();
                if (n.equals("emulated") || n.equals("self") || !v.isDirectory()) continue;
                if (write(new File(v, name), b.toString())) saved.add(v.getPath() + "/" + name);
            }
        }
        Toast.makeText(this, saved.isEmpty() ? "No pude guardar el registro" : "Guardado en: " + saved,
            Toast.LENGTH_LONG).show();
        addLog(saved.isEmpty() ? "no se pudo guardar" : "guardado en " + saved);
    }

    private static boolean write(File f, String text) {
        try {
            File p = f.getParentFile();
            if (p != null && !p.isDirectory() && !p.mkdirs()) return false;
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(text.getBytes("UTF-8"));
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
