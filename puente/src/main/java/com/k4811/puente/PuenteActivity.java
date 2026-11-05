package com.k4811.puente;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.k4811.common.ui.Ui;

import java.io.File;
import java.util.List;

/**
 * Pantalla del Puente v1.1.0: muestra la dirección y la clave para que el PC se conecte por WiFi,
 * recibe e instala APKs enviados desde el PC, repara el pendrive DVR y permite capturar pantalla.
 */
public class PuenteActivity extends Activity {

    private static final int BG = 0xFF0B0E13, PANEL = 0xFF151A21, TEXT = 0xFFEEF1F4, MUTED = 0xFF8A94A3,
        GOLD = 0xFFD6A94A, OK = 0xFF5ED39A, RED = 0xFFEF5350;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status, address, keyView, logView, onOff, capture, auto;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            render();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Ui.applyDesignDensity(this);
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setBackgroundColor(BG);
        root.setPadding(dp(36), dp(24), dp(36), dp(20));
        setContentView(root);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        root.addView(left, new LinearLayout.LayoutParams(0, -1, 1));
        left.addView(text("PUENTE PC v1.3 · WIFI + REPARADOR DVR", 14, GOLD, true));
        status = text("", 32, TEXT, true);
        left.addView(status, top(8));
        left.addView(text("En el PC (misma red WiFi) se entra a:", 16, MUTED, false), top(16));
        address = text("", 28, TEXT, true);
        address.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        left.addView(address, top(4));
        left.addView(text("Clave:", 16, MUTED, false), top(14));
        keyView = text("", 50, GOLD, true);
        keyView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        left.addView(keyView, top(2));
        left.addView(text("Con la dirección y clave, el PC puede leer diagnóstico y enviarte APKs listos para instalar.",
            14, MUTED, false), top(12));

        LinearLayout buttons = new LinearLayout(this);
        left.addView(buttons, top(20));
        onOff = button("", true);
        buttons.addView(onOff, new LinearLayout.LayoutParams(dp(220), dp(60)));
        onOff.setOnClickListener(v -> {
            if (PuenteService.running) PuenteService.stop(this);
            else PuenteService.start(this);
            handler.postDelayed(this::render, 400);
        });
        capture = button("", false);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(310), dp(60));
        clp.leftMargin = dp(14);
        buttons.addView(capture, clp);
        capture.setOnClickListener(v -> {
            if (!ScreenCapture.supported()) return;
            if (ScreenCapture.active()) ScreenCapture.stop();
            else startActivity(new Intent(this, CaptureActivity.class));
            handler.postDelayed(this::render, 400);
        });

        LinearLayout buttons2 = new LinearLayout(this);
        left.addView(buttons2, top(12));
        auto = button("", false);
        buttons2.addView(auto, new LinearLayout.LayoutParams(dp(360), dp(54)));
        auto.setOnClickListener(v -> {
            boolean a = getSharedPreferences("puente", MODE_PRIVATE).getBoolean("auto", false);
            getSharedPreferences("puente", MODE_PRIVATE).edit().putBoolean("auto", !a).apply();
            render();
        });
        TextView newKey = button("Nueva clave", false);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(dp(170), dp(54));
        nlp.leftMargin = dp(14);
        buttons2.addView(newKey, nlp);
        newKey.setOnClickListener(v -> {
            PuenteService.newKey(this);
            if (PuenteService.running) {
                PuenteService.stop(this);
                handler.postDelayed(() -> PuenteService.start(this), 500);
            }
            handler.postDelayed(this::render, 900);
        });

        LinearLayout buttons3 = new LinearLayout(this);
        left.addView(buttons3, top(12));
        TextView repairBtn = button("Reparar USB DVR", false);
        buttons3.addView(repairBtn, new LinearLayout.LayoutParams(dp(265), dp(54)));
        repairBtn.setOnClickListener(v -> {
            String res = Routes.repairDvrUsb();
            Toast.makeText(this, res.trim(), Toast.LENGTH_LONG).show();
        });

        TextView installLastBtn = button("Instalar APKs recibidos", false);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(265), dp(54));
        ilp.leftMargin = dp(14);
        buttons3.addView(installLastBtn, ilp);
        installLastBtn.setOnClickListener(v -> installLatestApk());

        ScrollView sv = new ScrollView(this);
        sv.setBackground(shape(PANEL, 18));
        logView = text("", 13, MUTED, false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setPadding(dp(16), dp(14), dp(16), dp(14));
        sv.addView(logView);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(470), -1);
        slp.leftMargin = dp(24);
        root.addView(sv, slp);

        PuenteService.start(this);
    }

    private void installLatestApk() {
        File dir = Routes.uploadDir();
        File[] files = dir.listFiles();
        File newest = null;
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && f.getName().endsWith(".apk")) {
                    if (newest == null || f.lastModified() > newest.lastModified()) newest = f;
                }
            }
        }
        if (newest == null) {
            File usbDir = new File("/storage/udisk/1_INSTALAR_AHORA");
            File[] usbFiles = usbDir.listFiles();
            if (usbFiles != null) {
                for (File f : usbFiles) {
                    if (f.isFile() && f.getName().endsWith(".apk")) {
                        newest = f;
                        break;
                    }
                }
            }
        }
        if (newest == null) {
            Toast.makeText(this, "Aún no hay APKs recibidos en K4811/apks ni en el USB", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "Abriendo instalador: " + newest.getName(), Toast.LENGTH_SHORT).show();
        Routes.promptInstallApk(this, newest);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Ui.hideSystemBars(getWindow());
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }

    private void render() {
        boolean on = PuenteService.running;
        status.setText(on ? "● Encendido" : PuenteService.error != null ? "No pude abrir el puerto" : "○ Apagado");
        status.setTextColor(on ? OK : PuenteService.error != null ? RED : MUTED);
        address.setText("http://" + PuenteService.ipAddress(this) + ":" + PuenteService.PORT);
        keyView.setText(PuenteService.key(this));
        onOff.setText(on ? "Apagar" : "Encender");
        capture.setText(!ScreenCapture.supported() ? "Captura no disponible"
            : ScreenCapture.active() ? "Detener captura de pantalla" : "Permitir captura de pantalla");
        boolean a = getSharedPreferences("puente", MODE_PRIVATE).getBoolean("auto", false);
        auto.setText("Encender al prender la radio: " + (a ? "SÍ" : "NO"));
        List<String> log = PuenteService.log();
        StringBuilder b = new StringBuilder();
        for (int i = log.size() - 1; i >= 0; i--) b.append(log.get(i)).append('\n');
        logView.setText(b.length() == 0 ? "Aquí aparece lo que el PC va leyendo o enviando." : b.toString());
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private LinearLayout.LayoutParams top(float m) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(m);
        return lp;
    }

    private TextView text(String s, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView button(String s, boolean primary) {
        TextView b = text(s, 17, primary ? 0xFF111111 : TEXT, true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(shape(primary ? GOLD : PANEL, 16));
        return b;
    }

    private GradientDrawable shape(int color, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(r));
        return g;
    }
}
