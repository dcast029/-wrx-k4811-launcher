package com.k4811.launcher.wrx;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.k4811.common.Apps;
import com.k4811.common.system.NwdSettings;
import com.k4811.common.ui.Ui;
import com.k4811.launcher.HomeActivity;

import java.util.Locale;

/** Ajustes de la pantalla WRX: tema, avisos, calibración, odómetro, modo pista y dock. */
public class WrxSettingsActivity extends Activity {

    private WrxTheme t;
    private LinearLayout list;
    private Dialog dialog;

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
        WrxTheme.pad(root, 28, 18, 28, 0);
        setContentView(root);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(head, new LinearLayout.LayoutParams(-1, px(64)));
        TextView back = WrxDialog.button(this, t, "‹  Inicio", false);
        head.addView(back, new LinearLayout.LayoutParams(px(130), px(52)));
        back.setOnClickListener(v -> finish());
        TextView title = t.label(this, "Ajustes WRX", 28, WrxTheme.bodyBold(), t.text);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1);
        tlp.leftMargin = px(20);
        head.addView(title, tlp);
        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        WrxTheme.pad(list, 0, 6, 0, 28);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    @Override
    protected void onResume() {
        super.onResume();
        Ui.hideSystemBars(getWindow());
        render();
    }

    @Override
    protected void onDestroy() {
        if (dialog != null) dialog.dismiss();
        super.onDestroy();
    }

    private int px(float v) {
        return WrxTheme.px(this, v);
    }

    private void render() {
        list.removeAllViews();
        Signal hb = Signal.load(this, Signal.HANDBRAKE);
        Signal rv = Signal.load(this, Signal.REVERSE);

        section("PANTALLA");
        String[] modes = {"Automático (con los faros)", "Siempre día", "Siempre noche"};
        action("Tema", modes[WrxPrefs.themeMode(this)], () ->
            dialog = WrxDialog.list(this, t, "Tema de la pantalla", modes, i -> {
                WrxPrefs.setThemeMode(this, i);
                recreate();
            }));
        action("Dock", "Mantén presionado un botón del dock en el inicio para cambiarlo", () ->
            dialog = WrxDialog.list(this, t, "Dock", new String[]{"Volver al dock original"}, i -> {
                WrxPrefs.get(this).edit().remove(WrxPrefs.DOCK).apply();
                render();
            }));
        action("Foto del auto", "Pon un PNG transparente en K4811/wrx_auto.png para usar otra foto", null);

        section("MODO PISTA Y CÁMARAS");
        toggle("Modo Pista WRX (0 a 100 km/h)", "Mide aceleración automáticamente por GPS al detenerte",
            WrxPrefs.SPORT_MODE, true);
        action("Récord 0 a 100 km/h",
            Float.isNaN(CarState.best0to100Sec) ? "Sin registros aún (acelera a fondo en 0 km/h)" : String.format(Locale.US, "Mejor tiempo: %.2f s (toca para reiniciar)", CarState.best0to100Sec),
            () -> {
                if (!Float.isNaN(CarState.best0to100Sec)) {
                    SportTimer.resetRecords(this);
                    render();
                }
            });
        toggle("Burbuja flotante de cámara frontal", "Botón en la esquina para mirar cunetas; se oculta sobre 20 km/h",
            WrxPrefs.CAM_BUBBLE, true);

        section("SONIDO");
        toggle("Volumen según velocidad", "Sube 1 paso a 50 km/h, 2 a 80, 3 a 100 (tapa escape y neumáticos)",
            WrxPrefs.SPEED_VOLUME, true);
        action("Prueba de parlantes y ecualizador", "Cada parlante, woofer y perfiles Ruta / Bass",
            () -> startActivity(new Intent(this, WrxSoundTestActivity.class)));

        section("AVISOS");
        toggle("Puerta o maletero abierto en marcha", "Avisa y pita si inicias la marcha con puertas abiertas",
            WrxPrefs.ALERT_DOORS, true);
        toggle("Freno de mano puesto andando",
            hb == null ? "Primero calíbralo en el Explorador CAN" : "Señal: " + hb.describe(),
            WrxPrefs.ALERT_HANDBRAKE, true);
        toggle("Luces apagadas cuando oscurece", "Calcula la puesta de sol con el GPS", WrxPrefs.ALERT_LIGHTS, true);
        toggle("Batería baja / no carga", "Bajo 11,5 V detenido, o bajo 12,6 V andando", WrxPrefs.ALERT_BATTERY, true);
        toggle("Cámara trasera: pendrive lleno o sin grabar", "Revisa el pendrive cada 30 s mientras andas",
            WrxPrefs.ALERT_DVR, true);
        toggle("Radio muy caliente", "Sobre 75 °C", WrxPrefs.ALERT_HOT, true);
        toggle("Recordar mantenciones al encender", "Si algo vence en 30 días o 1.000 km", WrxPrefs.ALERT_MAINTENANCE, true);
        toggle("Cámara frontal al sacar la reversa",
            rv == null ? "Primero calibra la reversa en el Explorador CAN" : "Se cierra sola al pasar 20 km/h",
            WrxPrefs.AUTO_FRONT_CAM, false);
        action("Probar un aviso", "Muestra cómo se ve el cartel por 6 segundos", () -> CarWatchService.test(this));

        section("AUTO Y TELEMETRÍA");
        action("Explorador CAN y calibración", "Ver todo lo que la radio recibe del auto · calibrar freno de mano y reversa",
            () -> startActivity(new Intent(this, CanExplorerActivity.class)));
        action("Odómetro", Double.isNaN(CarState.odometerKm) ? "Ingresar el kilometraje del tablero"
            : Maintenance.km(Math.round(CarState.odometerKm)) + " km (toca para corregir)", () ->
            dialog = WrxDialog.input(this, t, "Kilometraje del tablero", "Ej: 92345",
                Double.isNaN(CarState.odometerKm) ? "" : String.valueOf(Math.round(CarState.odometerKm)), true, s -> {
                    try {
                        CarWatchService.setOdometer(this, Double.parseDouble(s));
                        render();
                    } catch (Exception ignored) {
                    }
                }));
        action("Mantenciones", "Revisión técnica, permiso, SOAP, aceite…",
            () -> startActivity(new Intent(this, MaintenanceActivity.class)));

        section("OTROS");
        action("Pantalla de inicio clásica", "La anterior, con módulos (también: mantener presionado «SUBARU WRX»)",
            () -> startActivity(new Intent(this, HomeActivity.class)));
        action("Ajustes de la suite", "Idioma, volumen en pantalla, menú de la borda…", () -> {
            Intent i = getPackageManager().getLaunchIntentForPackage(Apps.SETTINGS);
            if (i != null) startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        });
    }

    private void section(String s) {
        TextView h = t.label(this, s, 12, WrxTheme.bodyBold(), t.gold);
        WrxTheme.spacing(h, 0.15f);
        WrxTheme.pad(h, 4, 22, 0, 6);
        list.addView(h);
    }

    private LinearLayout row(String title, String sub) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackground(t.card(this, 18));
        WrxTheme.pad(r, 22, 0, 18, 0);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        r.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        texts.addView(t.label(this, title, 19, WrxTheme.bodyBold(), t.text));
        if (sub != null) {
            TextView s = t.label(this, sub, 14, WrxTheme.body(), t.muted);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.topMargin = px(4);
            texts.addView(s, lp);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, px(78));
        lp.topMargin = px(8);
        list.addView(r, lp);
        return r;
    }

    private void action(String title, String sub, Runnable onTap) {
        LinearLayout r = row(title, sub);
        if (onTap != null) {
            r.addView(t.label(this, "›", 30, WrxTheme.body(), t.dim));
            r.setOnClickListener(v -> onTap.run());
        }
    }

    private void toggle(String title, String sub, String key, boolean def) {
        LinearLayout r = row(title, sub);
        TextView pill = t.label(this, "", 15, WrxTheme.bodyBold(), t.text);
        pill.setGravity(Gravity.CENTER);
        r.addView(pill, new LinearLayout.LayoutParams(px(84), px(42)));
        Runnable paint = () -> {
            boolean on = WrxPrefs.on(this, key, def);
            pill.setText(on ? "SÍ" : "NO");
            pill.setTextColor(on ? t.onGold : t.muted);
            pill.setBackground(on ? t.shape(this, t.gold, 21, 0) : t.shape(this, t.panel2, 21, t.line));
        };
        paint.run();
        r.setOnClickListener(v -> {
            WrxPrefs.set(this, key, !WrxPrefs.on(this, key, def));
            paint.run();
        });
    }
}
