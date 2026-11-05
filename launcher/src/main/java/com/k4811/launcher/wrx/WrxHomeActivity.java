package com.k4811.launcher.wrx;

import android.app.Activity;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.k4811.common.Apps;
import com.k4811.common.audio.PlayerHandoff;
import com.k4811.common.bt.BtClient;
import com.k4811.common.music.AlbumArt;
import com.k4811.common.radio.Band;
import com.k4811.common.radio.Preset;
import com.k4811.common.radio.RadioClient;
import com.k4811.common.radio.RadioState;
import com.k4811.common.radio.StationArt;
import com.k4811.common.ui.Ui;
import com.k4811.launcher.HomeActivity;
import com.k4811.launcher.home.HomeSession;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pantalla de inicio WRX. Tres zonas fijas: a la izquierda la hora, las mantenciones y los
 * medidores (batería CAN, temperatura de la radio, ventilador); al centro el auto con el
 * velocímetro GPS en arco; a la derecha lo que está sonando y el viaje/odómetro. Abajo un dock
 * editable (mantener presionado un botón para cambiarlo). Modo noche con los faros.
 *
 * Los datos del auto vienen de {@link CarState} (los llena {@link CarWatchService}); la música, de
 * {@link HomeSession} y RadioClient, igual que en la pantalla clásica ({@link HomeActivity}).
 */
public class WrxHomeActivity extends Activity implements CarState.Listener {

    private static final int SOURCE_ANDROID = 0;
    private static final int SOURCE_RADIO = 4;
    private static final Locale ES = new Locale("es", "CL");
    private static final String[] BT_ACTIONS = {
        "com.bt.ACTION_BT_HFP_ESTABLISHED", "com.bt.ACTION_BT_HFP_RELEASE",
        "com.bt.ACTION_BT_CONNECTION_CHANGE", "com.bt.ACTION_A2DP_ESTABLISHED",
        "com.bt.ACTION_A2DP_RELEASE",
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.US);
    private final SimpleDateFormat dayFmt = new SimpleDateFormat("EEEE", ES);
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("d 'de' MMMM", ES);

    private WrxTheme t;
    private boolean builtNight;
    private FrameLayout root;

    // Arriba
    private View chipBrake, chipReverse, chipDoor;
    private TextView chipLightsText, chipVoltText, chipTempText, chipDoorText, carStatusBadge;
    private Glyph btGlyph;
    // Izquierda
    private TextView clock, dayView, dateView;
    private LinearLayout maintList;
    private ArcGauge gBatt, gTemp, gFan;
    private TextView gBattText, gTempText, gFanText;
    // Centro
    private ArcGauge speedGauge;
    private TextView speedText, speedUnit;
    // Derecha
    private TextView npSource, npTitle, npSub, npLine3, npArtText;
    private ImageView npArt;
    private View npSourceDot, npProgressTrack, npProgress, npControls, npContent, callBox;
    private LinearLayout npPresets;
    private Glyph npPlay;
    private TextView callState, callName, callAnswer;
    private TextView tripValue, tripSub, odoValue, odoSub;
    // Dock
    private LinearLayout dock;

    private HomeSession session;
    private HomeSession.Snapshot snap;
    private RadioClient radio;
    private RadioState radioState = new RadioState();
    private boolean radioReady;
    private BtClient bt;
    private boolean btHfp;
    private String artKey;
    private int artGen;
    private Dialog dialog;

    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            renderClock();
            handler.postDelayed(this, 1000L - (System.currentTimeMillis() % 1000L));
        }
    };

    private final BroadcastReceiver btReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            handler.removeCallbacks(btQuery);
            handler.postDelayed(btQuery, 300);
        }
    };
    private final Runnable btQuery = this::queryBt;

    // ------------------------------------------------------------------ ciclo de vida

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Ui.applyDesignDensity(this);
        super.onCreate(savedInstanceState);
        WrxTheme.loadFonts(this);
        CarWatchService.start(this);
        builtNight = wantNight();
        t = new WrxTheme(builtNight);
        build();

        session = new HomeSession(this);
        session.setListener(s -> {
            snap = s;
            renderNowPlaying();
        });
        session.start();

        radio = new RadioClient(this);
        radio.setListener(state -> {
            radioState = state;
            if (state.connection == RadioState.Connection.CONNECTED) radioReady = true;
            renderNowPlaying();
        });
        radio.connect(false);

        bt = new BtClient(this);
        bt.connect();
        IntentFilter f = new IntentFilter();
        for (String a : BT_ACTIONS) f.addAction(a);
        registerReceiver(btReceiver, f);
        handler.postDelayed(this::checkDefaultLauncher, 1500);
    }

    /** El sistema NWD guarda el launcher por defecto en persist.nwd.launcher.default. */
    private void checkDefaultLauncher() {
        if (isFinishing() || !com.k4811.common.config.LauncherConfig.askDefaultLauncher(this)) return;
        String key = com.k4811.common.system.SystemProps.PROP_DEFAULT_LAUNCHER;
        String current = com.k4811.common.system.SystemProps.get(key, "");
        if (getPackageName().equals(current)) return;
        dialog = WrxDialog.list(this, t, "¿Usar esta pantalla como inicio de la radio?",
            new String[]{"Sí, usar la pantalla WRX"}, i ->
                com.k4811.common.system.SystemProps.requestSet(this, key, getPackageName()));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (wantNight() != builtNight) {
            recreate();
            return;
        }
        Ui.hideSystemBars(getWindow());
        CarState.add(this);
        CarWatchService.reloadOdometer(this);
        handler.removeCallbacks(clockTick);
        handler.post(clockTick);
        if (session != null) session.refresh();
        queryBt();
        renderDock();
        renderMaintenance();
        onCarState();
    }

    @Override
    protected void onPause() {
        CarState.remove(this);
        handler.removeCallbacks(clockTick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        CarState.remove(this);
        try {
            unregisterReceiver(btReceiver);
        } catch (Exception ignored) {
        }
        if (bt != null) bt.release();
        if (session != null) {
            session.setListener(null);
            session.stop();
        }
        if (radio != null) {
            radio.setListener(null);
            radio.release();
        }
        if (dialog != null) dialog.dismiss();
        loader.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Ui.hideSystemBars(getWindow());
    }

    @Override
    public void onBackPressed() {
        // Pantalla de inicio: Atrás no cierra nada.
    }

    private boolean wantNight() {
        int mode = WrxPrefs.themeMode(this);
        if (mode == 1) return false;
        if (mode == 2) return true;
        return com.k4811.common.system.NwdSettings.headlightsOn(this);
    }

    // ------------------------------------------------------------------ construcción

    private int px(float v) {
        return WrxTheme.px(this, v);
    }

    private FrameLayout.LayoutParams at(float x, float y, float w, float h) {
        return WrxTheme.at(this, x, y, w, h);
    }

    private void build() {
        root = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{t.bgTop, t.bg});
        bg.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        bg.setGradientCenter(0.52f, 0.46f);
        bg.setGradientRadius(px(820));
        root.setBackground(bg);
        setContentView(root);

        buildTopBar();
        buildLeft();
        buildCenter();
        buildRight();
        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER_VERTICAL);
        dock.setBackground(t.shape(this, t.panel, 26, t.line));
        WrxTheme.pad(dock, 10, 0, 10, 0);
        root.addView(dock, at(28, 618, 1224, 88));
    }

    private void buildTopBar() {
        LinearLayout brand = new LinearLayout(this);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        TextView s1 = t.label(this, "SUBARU ", 15, WrxTheme.numMedium(), t.muted);
        TextView s2 = t.label(this, "WRX", 15, WrxTheme.numBold(), t.text);
        WrxTheme.spacing(s1, 0.32f);
        WrxTheme.spacing(s2, 0.32f);
        brand.addView(s1);
        brand.addView(s2);
        root.addView(brand, at(28, 14, -1, 26));
        // Mantener presionado: pantalla de inicio clásica.
        brand.setOnLongClickListener(v -> {
            startActivity(new Intent(this, HomeActivity.class));
            return true;
        });

        LinearLayout chips = new LinearLayout(this);
        chips.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, px(44), Gravity.TOP | Gravity.END);
        lp.topMargin = px(6);
        lp.rightMargin = px(20);
        root.addView(chips, lp);

        chipDoor = chip(chips, Glyph.Kind.DOOR, WrxTheme.RED, "PUERTA");
        chipDoorText = (TextView) ((LinearLayout) chipDoor).getChildAt(1);
        chipDoorText.setTextColor(WrxTheme.RED);
        chipDoor.setVisibility(View.GONE);
        chipDoor.setOnClickListener(v -> {
            if (CarState.anyDoorOpen()) {
                android.widget.Toast.makeText(this, "Abierta(s): " + CarState.openDoorsDescription(), android.widget.Toast.LENGTH_SHORT).show();
            }
        });

        chipBrake = chip(chips, Glyph.Kind.HANDBRAKE, WrxTheme.RED, "FRENO");
        ((TextView) ((LinearLayout) chipBrake).getChildAt(1)).setTextColor(WrxTheme.RED);
        chipBrake.setVisibility(View.GONE);
        chipReverse = chip(chips, Glyph.Kind.CAM_REAR, WrxTheme.RED, "REVERSA");
        ((TextView) ((LinearLayout) chipReverse).getChildAt(1)).setTextColor(WrxTheme.RED);
        chipReverse.setVisibility(View.GONE);
        View lights = chip(chips, Glyph.Kind.LIGHTS, t.gold2, "Luces");
        chipLightsText = (TextView) ((LinearLayout) lights).getChildAt(1);
        View volt = chip(chips, Glyph.Kind.BATTERY, WrxTheme.OK, "-- V");
        chipVoltText = (TextView) ((LinearLayout) volt).getChildAt(1);
        View temp = chip(chips, Glyph.Kind.FAN, WrxTheme.COOL, "-- °C");
        chipTempText = (TextView) ((LinearLayout) temp).getChildAt(1);
        temp.setOnClickListener(v -> openPkg(Apps.FAN));
        volt.setOnClickListener(v -> startActivity(new Intent(this, CanExplorerActivity.class)));

        btGlyph = iconButton(chips, Glyph.Kind.BLUETOOTH);
        ((View) btGlyph.getParent()).setOnClickListener(v -> DockSlots.openOrExplain(this, "phone"));
        Glyph gear = iconButton(chips, Glyph.Kind.SETTINGS);
        View gearBox = (View) gear.getParent();
        gearBox.setOnClickListener(v -> startActivity(new Intent(this, WrxSettingsActivity.class)));
        gearBox.setOnLongClickListener(v -> {
            openPkg(Apps.SETTINGS);
            return true;
        });
    }

    private View chip(LinearLayout parent, Glyph.Kind kind, int iconColor, String text) {
        LinearLayout c = new LinearLayout(this);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setBackground(t.cardOf(this, t.panel, 18, true));
        WrxTheme.pad(c, 12, 0, 14, 0);
        c.addView(new Glyph(this).set(kind).color(iconColor).weight(2f), new LinearLayout.LayoutParams(px(18), px(18)));
        TextView tv = t.label(this, text, 15, WrxTheme.numMedium(), t.text);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.leftMargin = px(7);
        c.addView(tv, tlp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, px(36));
        lp.leftMargin = px(10);
        parent.addView(c, lp);
        return c;
    }

    private Glyph iconButton(LinearLayout parent, Glyph.Kind kind) {
        FrameLayout box = new FrameLayout(this);
        Glyph g = new Glyph(this).set(kind).color(t.muted).weight(2f);
        box.addView(g, new FrameLayout.LayoutParams(px(24), px(24), Gravity.CENTER));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(48), px(44));
        lp.leftMargin = px(6);
        parent.addView(box, lp);
        return g;
    }

    private void buildLeft() {
        clock = t.label(this, "00:00", 118, WrxTheme.num(), t.text);
        WrxTheme.spacing(clock, -0.03f);
        root.addView(clock, at(24, 58, 340, -1));
        clock.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_DATE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {
            }
        });
        LinearLayout date = new LinearLayout(this);
        dayView = t.label(this, "", 18, WrxTheme.bodyBold(), t.text);
        dateView = t.label(this, "", 18, WrxTheme.bodyMedium(), t.muted);
        date.addView(dayView);
        date.addView(dateView);
        root.addView(date, at(30, 192, 330, -1));

        // Mantenciones
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(t.card(this, 22));
        WrxTheme.pad(card, 18, 16, 18, 14);
        root.addView(card, at(28, 234, 330, 238));
        card.setOnClickListener(v -> startActivity(new Intent(this, WrxRegistryActivity.class)));
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(new Glyph(this).set(Glyph.Kind.TRIP).color(t.gold), new LinearLayout.LayoutParams(px(18), px(18)));
        TextView ht = t.label(this, "REGISTRO & BENCINA", 12, WrxTheme.bodyBold(), t.gold);
        WrxTheme.spacing(ht, 0.16f);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(0, -2, 1);
        hlp.leftMargin = px(8);
        head.addView(ht, hlp);
        TextView openLbl = t.label(this, "ABRIR ›", 11.5f, WrxTheme.bodyBold(), t.gold);
        head.addView(openLbl);
        card.addView(head);
        maintList = new LinearLayout(this);
        maintList.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, 0, 1);
        mlp.topMargin = px(10);
        card.addView(maintList, mlp);

        // Medidores
        gBatt = ring(28, Glyph.Kind.BATTERY, "BATERÍA");
        gBattText = (TextView) ((FrameLayout) gBatt.getParent()).getTag();
        gTemp = ring(28 + 113, Glyph.Kind.FAN, "RADIO");
        gTempText = (TextView) ((FrameLayout) gTemp.getParent()).getTag();
        gFan = ring(28 + 226, Glyph.Kind.FAN, "VENTILADOR");
        gFanText = (TextView) ((FrameLayout) gFan.getParent()).getTag();
        gBatt.range(10.5f, 15f);
        gTemp.range(30, 90);
        gFan.range(0, 1);
        ((View) gBatt.getParent().getParent()).setOnClickListener(
            v -> startActivity(new Intent(this, CanExplorerActivity.class)));
        ((View) gTemp.getParent().getParent()).setOnClickListener(v -> openPkg(Apps.FAN));
        ((View) gFan.getParent().getParent()).setOnClickListener(v -> openPkg(Apps.FAN));
    }

    /** Tarjeta con anillo, valor al centro y nombre abajo. El TextView del valor queda en el tag. */
    private ArcGauge ring(float x, Glyph.Kind kind, String name) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setBackground(t.card(this, 20));
        WrxTheme.pad(card, 4, 12, 4, 10);
        root.addView(card, at(x, 486, 104, 116));
        FrameLayout box = new FrameLayout(this);
        ArcGauge g = new ArcGauge(this).mode(ArcGauge.Mode.RING);
        g.colors(t.line, t.gold, 0, t.dim);
        box.addView(g, new FrameLayout.LayoutParams(-1, -1));
        TextView v = t.label(this, "--", 18, WrxTheme.numBold(), t.text);
        v.setGravity(Gravity.CENTER);
        box.addView(v, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        box.setTag(v);
        card.addView(box, new LinearLayout.LayoutParams(px(70), px(70)));
        TextView l = t.label(this, name, 10.5f, WrxTheme.bodyBold(), t.muted);
        WrxTheme.spacing(l, 0.12f);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-2, -2);
        llp.topMargin = px(6);
        card.addView(l, llp);
        return g;
    }

    private void buildCenter() {
        speedGauge = new ArcGauge(this).mode(ArcGauge.Mode.SPEED).range(0, 200).labels(0, 60, 100, 140, 200);
        speedGauge.colors(t.line, t.gold, t.gold2, t.dim);
        root.addView(speedGauge, at(368, 52, 500, 300));
        speedText = t.label(this, "--", 104, WrxTheme.num(), t.text);
        speedText.setGravity(Gravity.CENTER);
        root.addView(speedText, at(368, 120, 500, -1));
        speedUnit = t.label(this, "KM/H · GPS", 13, WrxTheme.bodyBold(), t.muted);
        speedUnit.setGravity(Gravity.CENTER);
        WrxTheme.spacing(speedUnit, 0.3f);
        root.addView(speedUnit, at(368, 252, 500, -1));

        View glow = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setColors(new int[]{(t.gold & 0x00FFFFFF) | (t.night ? 0x48000000 : 0x38000000), 0x00000000});
        g.setGradientRadius(px(280));
        g.setShape(GradientDrawable.OVAL);
        glow.setBackground(g);
        root.addView(glow, at(338, 548, 560, 64));

        ImageView car = new ImageView(this);
        car.setScaleType(ImageView.ScaleType.FIT_CENTER);
        car.setAdjustViewBounds(true);
        Bitmap custom = loadCustomCar();
        if (custom != null) car.setImageBitmap(custom);
        else car.setImageResource(com.k4811.launcher.R.drawable.wrx_car);
        root.addView(car, at(378, 356, 480, 236));
        car.setOnClickListener(v -> showCarMenu());

        carStatusBadge = t.label(this, "", 12.5f, WrxTheme.bodyBold(), WrxTheme.RED);
        carStatusBadge.setGravity(Gravity.CENTER);
        carStatusBadge.setBackground(t.cardOf(this, 0xCC1A0C0C, 8, true));
        carStatusBadge.setVisibility(View.GONE);
        WrxTheme.pad(carStatusBadge, 10, 4, 10, 4);
        root.addView(carStatusBadge, at(450, 320, 336, 32));

        // Barra de Accesos Rápidos directa en Inicio
        LinearLayout quickBar = new LinearLayout(this);
        quickBar.setOrientation(LinearLayout.HORIZONTAL);
        quickBar.setGravity(Gravity.CENTER);
        quickBar.setBackground(t.cardOf(this, t.panel, 14, true));
        WrxTheme.pad(quickBar, 6, 3, 6, 3);
        addQuickChip(quickBar, "📋 REGISTRO", () -> startActivity(new Intent(this, WrxRegistryActivity.class)));
        addQuickChip(quickBar, "⛽ BENCINA", this::showFuelDialog);
        addQuickChip(quickBar, "🎛️ AUDIO", () -> startActivity(new Intent(this, WrxSoundTestActivity.class)));
        addQuickChip(quickBar, "🏁 PISTA", this::showSportMenu);
        root.addView(quickBar, at(378, 554, 480, 44));
    }

    private void addQuickChip(LinearLayout parent, String label, Runnable action) {
        TextView b = t.label(this, label, 12.5f, WrxTheme.bodyBold(), t.text);
        b.setGravity(Gravity.CENTER);
        b.setBackground(t.cardOf(this, t.panel2, 10, true));
        WrxTheme.pad(b, 8, 0, 8, 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
        if (parent.getChildCount() > 0) lp.leftMargin = px(6);
        parent.addView(b, lp);
        b.setOnClickListener(v -> action.run());
    }

    /** Foto propia opcional: K4811/wrx_auto.png (PNG con fondo transparente). */
    private Bitmap loadCustomCar() {
        File f = new File(Environment.getExternalStorageDirectory(), "K4811/wrx_auto.png");
        if (!f.isFile()) return null;
        try {
            return BitmapFactory.decodeFile(f.getPath());
        } catch (Throwable e) {
            return null;
        }
    }

    private void buildRight() {
        FrameLayout card = new FrameLayout(this);
        card.setBackground(t.cardOf(this, t.panel, 22, false));
        root.addView(card, at(888, 70, 364, 436));

        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        WrxTheme.pad(c, 20, 18, 20, 16);
        card.addView(c, new FrameLayout.LayoutParams(-1, -1));
        npContent = c;
        c.setOnClickListener(v -> openNowPlaying());

        LinearLayout src = new LinearLayout(this);
        src.setGravity(Gravity.CENTER_VERTICAL);
        npSourceDot = new View(this);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(t.gold);
        npSourceDot.setBackground(dot);
        src.addView(npSourceDot, new LinearLayout.LayoutParams(px(8), px(8)));
        npSource = t.label(this, "", 11.5f, WrxTheme.bodyBold(), t.gold);
        WrxTheme.spacing(npSource, 0.16f);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.leftMargin = px(8);
        src.addView(npSource, slp);
        c.addView(src);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = px(14);
        c.addView(row, rlp);
        FrameLayout art = new FrameLayout(this);
        GradientDrawable artBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            t.night ? new int[]{0xFF2B3340, 0xFF11161C} : new int[]{0xFF1D232B, 0xFF3A4452});
        artBg.setCornerRadius(px(16));
        art.setBackground(artBg);
        npArtText = t.label(this, "FM", 26, WrxTheme.numBold(), 0xFFF0CD7A);
        npArtText.setGravity(Gravity.CENTER);
        art.addView(npArtText, new FrameLayout.LayoutParams(-1, -1));
        npArt = new ImageView(this);
        npArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        npArt.setVisibility(View.GONE);
        art.addView(npArt, new FrameLayout.LayoutParams(-1, -1));
        if (android.os.Build.VERSION.SDK_INT >= 21) art.setClipToOutline(true);
        row.addView(art, new LinearLayout.LayoutParams(px(96), px(96)));
        LinearLayout meta = new LinearLayout(this);
        meta.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0, -2, 1);
        mlp.leftMargin = px(14);
        row.addView(meta, mlp);
        npTitle = t.label(this, "", 21, WrxTheme.bodyBold(), t.text);
        npSub = t.label(this, "", 14.5f, WrxTheme.bodyMedium(), t.muted);
        npLine3 = t.label(this, "", 15, WrxTheme.num(), t.muted);
        meta.addView(npTitle);
        LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(-2, -2);
        l2.topMargin = px(5);
        meta.addView(npSub, l2);
        LinearLayout.LayoutParams l3 = new LinearLayout.LayoutParams(-2, -2);
        l3.topMargin = px(6);
        meta.addView(npLine3, l3);

        FrameLayout track = new FrameLayout(this);
        track.setBackground(t.shape(this, t.line, 2, 0));
        npProgress = new View(this);
        npProgress.setBackground(t.shape(this, t.gold, 2, 0));
        track.addView(npProgress, new FrameLayout.LayoutParams(-1, -1));
        npProgressTrack = track;
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, px(4));
        tlp.topMargin = px(18);
        c.addView(track, tlp);

        npPresets = new LinearLayout(this);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, px(40));
        plp.topMargin = px(14);
        c.addView(npPresets, plp);

        c.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout ctrl = new LinearLayout(this);
        ctrl.setGravity(Gravity.CENTER_VERTICAL);
        npControls = ctrl;
        c.addView(ctrl, new LinearLayout.LayoutParams(-1, px(76)));
        transportButton(ctrl, Glyph.Kind.PREV, false).setOnClickListener(v -> transport(-1));
        ctrl.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        FrameLayout play = transportButton(ctrl, Glyph.Kind.PAUSE, true);
        npPlay = (Glyph) play.getChildAt(0);
        play.setOnClickListener(v -> transport(0));
        ctrl.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        transportButton(ctrl, Glyph.Kind.NEXT, false).setOnClickListener(v -> transport(1));

        // Llamada (tapa el contenido de la tarjeta)
        LinearLayout call = new LinearLayout(this);
        call.setOrientation(LinearLayout.VERTICAL);
        call.setGravity(Gravity.CENTER_HORIZONTAL);
        WrxTheme.pad(call, 20, 40, 20, 20);
        call.setBackground(t.cardOf(this, t.panel, 22, false));
        call.setVisibility(View.GONE);
        callBox = call;
        card.addView(call, new FrameLayout.LayoutParams(-1, -1));
        call.addView(new Glyph(this).set(Glyph.Kind.PHONE).color(t.gold), new LinearLayout.LayoutParams(px(48), px(48)));
        callState = t.label(this, "", 14, WrxTheme.bodyBold(), t.gold);
        LinearLayout.LayoutParams cs = new LinearLayout.LayoutParams(-2, -2);
        cs.topMargin = px(16);
        call.addView(callState, cs);
        callName = t.label(this, "", 26, WrxTheme.bodyBold(), t.text);
        LinearLayout.LayoutParams cn = new LinearLayout.LayoutParams(-2, -2);
        cn.topMargin = px(10);
        call.addView(callName, cn);
        call.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout cb = new LinearLayout(this);
        call.addView(cb, new LinearLayout.LayoutParams(-1, px(64)));
        callAnswer = WrxDialog.button(this, t, "Contestar", true);
        callAnswer.setBackground(t.cardOf(this, WrxTheme.OK, 18, true));
        TextView hang = WrxDialog.button(this, t, "Colgar", false);
        hang.setBackground(t.cardOf(this, WrxTheme.RED, 18, true));
        hang.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(0, -1, 1);
        b1.rightMargin = px(10);
        cb.addView(callAnswer, b1);
        cb.addView(hang, new LinearLayout.LayoutParams(0, -1, 1));
        callAnswer.setOnClickListener(v -> session.answerCall());
        hang.setOnClickListener(v -> session.hangupCall());

        // Viaje y odómetro
        View trip = tile(888, Glyph.Kind.TRIP);
        tripValue = (TextView) ((LinearLayout) ((LinearLayout) trip).getChildAt(1)).getChildAt(0);
        tripSub = (TextView) ((LinearLayout) ((LinearLayout) trip).getChildAt(1)).getChildAt(1);
        trip.setOnClickListener(v -> startActivity(new Intent(this, WrxRegistryActivity.class)));
        View odo = tile(888 + 188, Glyph.Kind.WRENCH);
        ((Glyph) ((LinearLayout) ((LinearLayout) odo).getChildAt(0)).getChildAt(0)).set(Glyph.Kind.CAN);
        odoValue = (TextView) ((LinearLayout) ((LinearLayout) odo).getChildAt(1)).getChildAt(0);
        odoSub = (TextView) ((LinearLayout) ((LinearLayout) odo).getChildAt(1)).getChildAt(1);
        odo.setOnClickListener(v -> startActivity(new Intent(this, WrxRegistryActivity.class)));
    }

    private FrameLayout transportButton(LinearLayout parent, Glyph.Kind kind, boolean big) {
        FrameLayout box = new FrameLayout(this);
        int size = big ? 70 : 60;
        Glyph g = new Glyph(this).set(kind).color(big ? t.onGold : t.text);
        box.addView(g, new FrameLayout.LayoutParams(px(big ? 30 : 28), px(big ? 30 : 28), Gravity.CENTER));
        if (big) {
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(t.gold);
            box.setBackground(d);
        } else {
            box.setBackground(t.cardOf(this, 0x00000000, 30, true));
        }
        parent.addView(box, new LinearLayout.LayoutParams(px(size), px(size)));
        return box;
    }

    /** Tarjeta chica (ícono + valor + texto) abajo a la derecha. */
    private View tile(float x, Glyph.Kind kind) {
        LinearLayout c = new LinearLayout(this);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setBackground(t.card(this, 20));
        WrxTheme.pad(c, 14, 0, 12, 0);
        root.addView(c, at(x, 518, 176, 84));
        LinearLayout ic = new LinearLayout(this);
        ic.setGravity(Gravity.CENTER);
        ic.setBackground(t.shape(this, t.panel2, 14, t.line));
        ic.addView(new Glyph(this).set(kind).color(t.gold2), new LinearLayout.LayoutParams(px(22), px(22)));
        c.addView(ic, new LinearLayout.LayoutParams(px(44), px(44)));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        lp.leftMargin = px(11);
        c.addView(texts, lp);
        texts.addView(t.label(this, "--", 17, WrxTheme.numMedium(), t.text));
        texts.addView(t.label(this, "", 12, WrxTheme.bodyMedium(), t.muted));
        return c;
    }

    // ------------------------------------------------------------------ dock

    private void renderDock() {
        dock.removeAllViews();
        List<String> ids = DockSlots.load(this);
        for (int i = 0; i < ids.size(); i++) {
            final int idx = i;
            String id = ids.get(i);
            boolean active = "home".equals(id);
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setBackground(t.cardOf(this, 0x00000000, 18, true));
            View icon;
            if (DockSlots.isApp(id) && DockSlots.glyph(id) == Glyph.Kind.APP) {
                ImageView iv = new ImageView(this);
                try {
                    iv.setImageDrawable(getPackageManager().getApplicationIcon(id.substring(4)));
                } catch (Exception e) {
                    iv.setImageDrawable(null);
                }
                icon = iv;
            } else {
                icon = new Glyph(this).set(DockSlots.glyph(id)).color(active ? t.text : t.muted);
            }
            cell.addView(icon, new LinearLayout.LayoutParams(px(30), px(30)));
            TextView l = t.label(this, DockSlots.label(this, id), 11.5f, WrxTheme.bodyBold(), active ? t.text : t.muted);
            WrxTheme.spacing(l, 0.03f);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-2, -2);
            llp.topMargin = px(7);
            cell.addView(l, llp);
            View bar = new View(this);
            bar.setBackground(t.shape(this, active ? t.gold : 0x00000000, 2, 0));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(px(26), px(3));
            blp.topMargin = px(6);
            cell.addView(bar, blp);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, px(80), 1);
            dock.addView(cell, clp);
            cell.setOnClickListener(v -> DockSlots.openOrExplain(this, id));
            cell.setOnLongClickListener(v -> {
                editDock(idx);
                return true;
            });
        }
    }

    private void editDock(int index) {
        List<String> ids = DockSlots.load(this);
        String name = DockSlots.label(this, ids.get(index));
        List<String> opts = new ArrayList<>();
        opts.add("Cambiar «" + name + "» por otra app");
        if (ids.size() < DockSlots.MAX) opts.add("Agregar un botón a la derecha");
        opts.add("Mover a la izquierda");
        opts.add("Mover a la derecha");
        if (ids.size() > 2) opts.add("Quitar este botón");
        opts.add("Volver al dock original");
        dialog = WrxDialog.list(this, t, "Botón " + name, opts.toArray(new String[0]), i -> {
            String o = opts.get(i);
            if (o.startsWith("Cambiar")) {
                dialog = WrxDialog.pickDockItem(this, t, id -> {
                    ids.set(index, id);
                    DockSlots.save(this, ids);
                    renderDock();
                });
            } else if (o.startsWith("Agregar")) {
                dialog = WrxDialog.pickDockItem(this, t, id -> {
                    ids.add(index + 1, id);
                    DockSlots.save(this, ids);
                    renderDock();
                });
            } else if (o.startsWith("Mover a la izq") && index > 0) {
                ids.add(index - 1, ids.remove(index));
                DockSlots.save(this, ids);
                renderDock();
            } else if (o.startsWith("Mover a la der") && index < ids.size() - 1) {
                ids.add(index + 1, ids.remove(index));
                DockSlots.save(this, ids);
                renderDock();
            } else if (o.startsWith("Quitar")) {
                ids.remove(index);
                DockSlots.save(this, ids);
                renderDock();
            } else if (o.startsWith("Volver")) {
                WrxPrefs.get(this).edit().remove(WrxPrefs.DOCK).apply();
                renderDock();
            }
        });
    }

    // ------------------------------------------------------------------ reloj y auto

    private void renderClock() {
        Date now = new Date();
        clock.setText(timeFmt.format(now));
        String d = dayFmt.format(now);
        dayView.setText(d.isEmpty() ? "" : Character.toUpperCase(d.charAt(0)) + d.substring(1));
        dateView.setText(" · " + dateFmt.format(now));
    }

    @Override
    public void onCarState() {
        if (wantNight() != builtNight) {
            recreate();
            return;
        }
        // Velocidad
        float s = CarState.speedKmh;
        if (Float.isNaN(s)) {
            speedText.setText("--");
            speedUnit.setText(CarState.gpsAvailable ? "KM/H · BUSCANDO GPS" : "KM/H · SIN GPS");
            speedGauge.setValue(0, true);
        } else {
            speedText.setText(String.valueOf(Math.round(s)));
            if (CarState.sportArmed) {
                speedUnit.setText("🏁 LISTO PARA LARGAR");
                speedUnit.setTextColor(WrxTheme.OK);
            } else if (!Double.isNaN(CarState.altitudeMeters) && s > 3) {
                speedUnit.setText(String.format(ES, "KM/H · %d M", Math.round(CarState.altitudeMeters)));
                speedUnit.setTextColor(t.muted);
            } else {
                speedUnit.setText("KM/H · GPS");
                speedUnit.setTextColor(t.muted);
            }
            speedGauge.setValue(s, true);
        }
        // Batería
        float v = CarState.volts;
        if (Float.isNaN(v)) {
            chipVoltText.setText("-- V");
            gBattText.setText("--");
            gBatt.setValue(10.5f, false);
        } else {
            String vs = String.format(Locale.US, "%.1f", v);
            chipVoltText.setText(vs + " V");
            gBattText.setText(vs);
            int col = v < 11.6f ? WrxTheme.RED : v < 12.2f ? WrxTheme.WARN : WrxTheme.OK;
            gBatt.colors(t.line, col, 0, t.dim);
            gBatt.setValue(v, true);
        }
        // Temperatura de la radio
        float temp = CarState.radioTempC;
        if (Float.isNaN(temp)) {
            chipTempText.setText(CarState.fanOn ? "ON" : "--");
            gTempText.setText("--");
        } else {
            chipTempText.setText(Math.round(temp) + " °C");
            gTempText.setText(Math.round(temp) + "°");
            int col = temp >= 75 ? WrxTheme.RED : temp >= 60 ? WrxTheme.WARN : WrxTheme.COOL;
            gTemp.colors(t.line, col, 0, t.dim);
            gTemp.setValue(temp, true);
        }
        // Ventilador
        gFanText.setText(CarState.fanOn ? "ON" : "OFF");
        gFan.colors(t.line, t.gold, 0, t.dim);
        gFan.setValue(CarState.fanOn ? 1 : 0, true);
        // Luces, puertas, freno de mano y reversa
        chipLightsText.setText(CarState.headlights ? "Luces" : "Día");
        boolean brake = CarState.handbrake != null && CarState.handbrake;
        chipBrake.setVisibility(brake ? View.VISIBLE : View.GONE);
        boolean rev = CarState.reverse != null && CarState.reverse;
        if (chipReverse != null) chipReverse.setVisibility(rev ? View.VISIBLE : View.GONE);

        boolean doorOpen = CarState.anyDoorOpen();
        if (chipDoor != null) {
            chipDoor.setVisibility(doorOpen ? View.VISIBLE : View.GONE);
            if (doorOpen && chipDoorText != null) {
                if (CarState.doorTrunk && CarState.openDoorsCount() == 1) {
                    chipDoorText.setText("MALETERO");
                } else if (CarState.doorHood && CarState.openDoorsCount() == 1) {
                    chipDoorText.setText("CAPÓ");
                } else if (CarState.openDoorsCount() == 1) {
                    chipDoorText.setText(CarState.openDoorsDescription().toUpperCase(Locale.US));
                } else {
                    chipDoorText.setText("PUERTAS (" + CarState.openDoorsCount() + ")");
                }
            }
        }

        if (carStatusBadge != null) {
            if (doorOpen) {
                String desc = CarState.openDoorsDescription().toUpperCase(Locale.US);
                carStatusBadge.setText("\u26a0\ufe0f " + desc + " ABIERTA");
                carStatusBadge.setTextColor(WrxTheme.RED);
                carStatusBadge.setVisibility(View.VISIBLE);
            } else if (brake) {
                carStatusBadge.setText("\u26a0\ufe0f FRENO DE MANO ACTIVO");
                carStatusBadge.setTextColor(WrxTheme.RED);
                carStatusBadge.setVisibility(View.VISIBLE);
            } else if (rev) {
                carStatusBadge.setText("\ud83d\udd34 MARCHA ATR\u00c1S / REVERSA");
                carStatusBadge.setTextColor(WrxTheme.RED);
                carStatusBadge.setVisibility(View.VISIBLE);
            } else {
                carStatusBadge.setVisibility(View.GONE);
            }
        }
        // Viaje y odómetro
        tripValue.setText(String.format(ES, "%.1f km", CarState.tripKm).replace('.', ','));
        long min = CarState.tripMovingMs / 60000;
        float avg = CarState.tripAvgKmh();
        tripSub.setText("Viaje · " + (min >= 60 ? (min / 60) + " h " + (min % 60) + " min" : min + " min")
            + (Float.isNaN(avg) ? "" : " · " + Math.round(avg) + " prom"));
        if (Double.isNaN(CarState.odometerKm)) {
            odoValue.setText("Odómetro");
            odoSub.setText("Toca para ingresar km");
        } else {
            odoValue.setText(Maintenance.km(Math.round(CarState.odometerKm)) + " km");
            odoSub.setText("Odómetro (GPS)");
        }
    }

    private void renderMaintenance() {
        maintList.removeAllViews();

        // 1. Fila Bencina rápida
        float avgKmL = FuelTracker.getAverageKmL(this);
        FuelTracker.FillUp lastFuel = FuelTracker.getLastFillUp(this);
        LinearLayout fuelRow = new LinearLayout(this);
        fuelRow.setGravity(Gravity.CENTER_VERTICAL);
        fuelRow.addView(t.label(this, "⛽", 14, WrxTheme.bodyBold(), t.gold));
        String sFuel = !Float.isNaN(avgKmL) ? String.format(ES, " %.1f km/L prom.", avgKmL)
            : (lastFuel != null && !Float.isNaN(lastFuel.kmL) ? String.format(ES, " %.1f km/L últ.", lastFuel.kmL)
            : (lastFuel != null ? String.format(ES, " %.1f L cargados", lastFuel.liters) : " Cargar bencina"));
        TextView fuelTxt = t.label(this, sFuel, 13.5f, WrxTheme.bodyBold(), t.text);
        fuelTxt.setSingleLine(true);
        LinearLayout.LayoutParams ftlp = new LinearLayout.LayoutParams(0, -2, 1);
        ftlp.leftMargin = px(6);
        fuelRow.addView(fuelTxt, ftlp);
        TextView refuelBtn = t.label(this, "+ CARGAR", 11, WrxTheme.bodyBold(), t.gold);
        refuelBtn.setBackground(t.cardOf(this, t.panel2, 8, true));
        WrxTheme.pad(refuelBtn, 8, 4, 8, 4);
        fuelRow.addView(refuelBtn);
        fuelRow.setOnClickListener(v -> showFuelDialog());
        maintList.addView(fuelRow);

        // 2. Fila Modo Pista (0-100)
        float b100 = WrxPrefs.getBest0to100(this);
        LinearLayout sportRow = new LinearLayout(this);
        sportRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams splp = new LinearLayout.LayoutParams(-1, -2);
        splp.topMargin = px(8);
        sportRow.setLayoutParams(splp);
        sportRow.addView(t.label(this, "🏁", 14, WrxTheme.bodyBold(), t.gold));
        String sSport = b100 > 0 ? String.format(ES, " 0-100: %.2f s", b100) : " Modo Pista (0-100)";
        TextView sportTxt = t.label(this, sSport, 13.5f, WrxTheme.bodyBold(), t.text);
        sportTxt.setSingleLine(true);
        LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(0, -2, 1);
        stlp.leftMargin = px(6);
        sportRow.addView(sportTxt, stlp);
        TextView sportBtn = t.label(this, "VER ›", 11, WrxTheme.bodyBold(), t.muted);
        sportRow.addView(sportBtn);
        sportRow.setOnClickListener(v -> showSportMenu());
        maintList.addView(sportRow);

        // 3. Fila Mantención
        List<Maintenance.Item> list = Maintenance.pending(this);
        LinearLayout maintRow = new LinearLayout(this);
        maintRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, -2);
        mlp.topMargin = px(8);
        maintRow.setLayoutParams(mlp);
        maintRow.addView(t.label(this, "🔧", 14, WrxTheme.bodyBold(), t.gold));
        String sMaint = list.isEmpty() ? " Mantenciones al día" : " " + list.get(0).title + " (" + list.get(0).status() + ")";
        TextView maintTxt = t.label(this, sMaint, 13.5f, WrxTheme.bodyBold(), list.isEmpty() ? t.muted : t.gold);
        maintTxt.setSingleLine(true);
        LinearLayout.LayoutParams mtlp = new LinearLayout.LayoutParams(0, -2, 1);
        mtlp.leftMargin = px(6);
        maintRow.addView(maintTxt, mtlp);
        TextView maintBtn = t.label(this, "VER ›", 11, WrxTheme.bodyBold(), t.muted);
        maintRow.addView(maintBtn);
        maintRow.setOnClickListener(v -> startActivity(new Intent(this, WrxRegistryActivity.class)));
        maintList.addView(maintRow);
    }

    private void showCarMenu() {
        boolean sport = WrxPrefs.isSportMode(this);
        String[] opts = {
            "📋 REGISTRO WRX (Bencina, Mantención, Odómetro)",
            "🎛️ Centro de Audio & Ecualización",
            "🏁 Modo Pista WRX (0-100 / 0-60)" + (sport ? " (Activo)" : " (Apagado)"),
            "🛡️ Proteger último video (Cámara Trasera)",
            "🖼️ Foto del auto: guardar en /sdcard/K4811/wrx_auto.png"
        };
        dialog = WrxDialog.list(this, t, "Subaru WRX", opts, i -> {
            if (i == 0) startActivity(new Intent(this, WrxRegistryActivity.class));
            else if (i == 1) startActivity(new Intent(this, WrxSoundTestActivity.class));
            else if (i == 2) showSportMenu();
            else if (i == 3) protectVideo();
            else if (i == 4) {
                Toast.makeText(this, "Guarda tu imagen PNG transparente en:\n/sdcard/K4811/wrx_auto.png", Toast.LENGTH_LONG).show();
            }
        });
    }

    private void showSportMenu() {
        float b100 = WrxPrefs.getBest0to100(this);
        float b60 = WrxPrefs.getBest0to60(this);
        float l100 = CarState.last0to100Sec;
        float l60 = CarState.last0to60Sec;
        boolean active = WrxPrefs.isSportMode(this);

        String sBest100 = b100 > 0 ? String.format(ES, "%.2f s", b100) : "Sin registro";
        String sBest60 = b60 > 0 ? String.format(ES, "%.2f s", b60) : "Sin registro";
        String sLast100 = l100 > 0 ? String.format(ES, "%.2f s", l100) : "--";
        String sLast60 = l60 > 0 ? String.format(ES, "%.2f s", l60) : "--";

        String[] opts = {
            "🏆 Récord 0-100 km/h: " + sBest100,
            "🏆 Récord 0-60 km/h: " + sBest60,
            "⏱️ Último 0-100: " + sLast100,
            "⏱️ Último 0-60: " + sLast60,
            active ? "🔴 Desactivar Modo Pista" : "🟢 Activar Modo Pista",
            "🗑️ Reiniciar mejores récords",
        };

        dialog = WrxDialog.list(this, t, "Modo Pista WRX", opts, i -> {
            if (i == 4) {
                WrxPrefs.setSportMode(this, !active);
                Toast.makeText(this, !active ? "Modo Pista ACTIVADO: detente a 0 km/h para largar" : "Modo Pista desactivado", Toast.LENGTH_SHORT).show();
            } else if (i == 5) {
                SportTimer.resetBest(this);
                Toast.makeText(this, "Récords de pista reiniciados", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showFuelDialog() {
        String summary = FuelTracker.getSummary(this);
        String[] info = summary.split("\\n");
        List<String> list = new ArrayList<>();
        for (String line : info) {
            if (!line.trim().isEmpty()) list.add(line.trim());
        }
        list.add("➕ Registrar nueva carga de bencina");
        String[] opts = list.toArray(new String[0]);

        dialog = WrxDialog.list(this, t, "Bencina y Rendimiento", opts, i -> {
            if (i == opts.length - 1) {
                dialog = WrxDialog.input(this, t, "Cargar Bencina", "Litros cargados (ej: 45.0)", "", false, s -> {
                    try {
                        float liters = Float.parseFloat(s.trim().replace(',', '.'));
                        if (liters <= 0) return;
                        String res = FuelTracker.onRefuel(this, liters);
                        WrxDialog.list(this, t, "Carga Guardada", new String[]{res, "Aceptar"}, idx -> {});
                    } catch (Exception e) {
                        Toast.makeText(this, "Litros no válidos", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    private void protectVideo() {
        Toast.makeText(this, "Buscando último video para proteger...", Toast.LENGTH_SHORT).show();
        VideoProtect.protectLatestVideo(this, (success, message) -> {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        });
    }

    private void showTrip() {
        long min = CarState.tripMovingMs / 60000;
        float avg = CarState.tripAvgKmh();
        String[] opts = {
            String.format(ES, "Recorrido: %.1f km", CarState.tripKm),
            "En movimiento: " + min + " min",
            "Promedio: " + (Float.isNaN(avg) ? "--" : Math.round(avg) + " km/h")
                + "  ·  Máxima: " + Math.round(CarState.tripMaxKmh) + " km/h",
            "Reiniciar el viaje",
        };
        dialog = WrxDialog.list(this, t, "Viaje (por GPS)", opts, i -> {
            if (i == 3) CarWatchService.resetTrip(this);
        });
    }

    private void askOdometer() {
        String cur = Double.isNaN(CarState.odometerKm) ? "" : String.valueOf(Math.round(CarState.odometerKm));
        dialog = WrxDialog.input(this, t, "Kilometraje del tablero",
            "Ej: 92345", cur, true, s -> {
                try {
                    double km = Double.parseDouble(s);
                    CarWatchService.setOdometer(this, km);
                    onCarState();
                    renderMaintenance();
                } catch (Exception ignored) {
                }
            });
    }

    // ------------------------------------------------------------------ lo que está sonando

    private void renderNowPlaying() {
        if (snap == null || npTitle == null) return;
        HomeSession.Snapshot s = snap;
        boolean calling = s.call != HomeSession.CallPhase.NONE;
        callBox.setVisibility(calling ? View.VISIBLE : View.GONE);
        if (calling) {
            callState.setText(s.call == HomeSession.CallPhase.INCOMING ? "LLAMADA ENTRANTE"
                : s.call == HomeSession.CallPhase.OUTGOING ? "LLAMANDO…" : "EN LLAMADA");
            callName.setText(!s.callName.isEmpty() ? s.callName : !s.callNumber.isEmpty() ? s.callNumber : "Número privado");
            callAnswer.setVisibility(s.call == HomeSession.CallPhase.INCOMING ? View.VISIBLE : View.GONE);
        }
        boolean controls = true;
        float progress = -1f;
        String line3 = "";
        npPresets.setVisibility(View.GONE);
        switch (s.kind) {
            case RADIO: {
                boolean live = s.source == SOURCE_RADIO;
                npSource.setText(live ? "RADIO · SONANDO" : "RADIO · APAGADA");
                if (!radioReady) {
                    npTitle.setText(radioState.connection == RadioState.Connection.NOT_FOUND
                        ? "Radio no disponible" : "Radio");
                    npSub.setText("");
                } else {
                    boolean fm = Band.isFm(radioState.band);
                    String freq = Band.formatValue(radioState.frequency, fm) + " " + Band.unit(fm);
                    boolean named = radioState.psName != null && !radioState.psName.trim().isEmpty();
                    npTitle.setText(named ? radioState.psName.trim() : freq);
                    npSub.setText(Band.label(radioState.band));
                    line3 = named ? freq : "";
                    renderPresets(fm);
                }
                npArtText.setText(Band.isFm(radioState.band) ? "FM" : "AM");
                npPlay.set(Glyph.Kind.POWER);
                break;
            }
            case USB:
                npSource.setText("MÚSICA USB · " + (s.playing ? "SONANDO" : "EN PAUSA"));
                npTitle.setText(s.title.isEmpty() ? "Música" : s.title);
                npSub.setText(s.artist.isEmpty() ? "USB / SD" : s.artist);
                progress = s.durationMs > 0 ? Math.min(1f, s.positionMs / (float) s.durationMs) : 0f;
                npArtText.setText("♪");
                npPlay.set(s.playing ? Glyph.Kind.PAUSE : Glyph.Kind.PLAY);
                break;
            case BT: {
                npSource.setText("BLUETOOTH · " + (s.btPlaying ? "SONANDO" : "EN PAUSA"));
                boolean named = !s.btTitle.isEmpty();
                if (s.a2dpKnown && !s.a2dpConnected && !named) {
                    npTitle.setText("Teléfono desconectado");
                    npSub.setText("Toca para conectar");
                } else {
                    npTitle.setText(named ? s.btTitle : "Música Bluetooth");
                    npSub.setText(s.btArtist);
                }
                npArtText.setText("BT");
                npPlay.set(s.btPlaying ? Glyph.Kind.PAUSE : Glyph.Kind.PLAY);
                break;
            }
            default:
                npSource.setText("NADA SONANDO");
                npTitle.setText("Elige una fuente");
                npSub.setText("Radio, música o Bluetooth en el dock");
                npArtText.setText("♪");
                controls = false;
                break;
        }
        npLine3.setText(line3);
        npLine3.setVisibility(line3.isEmpty() ? View.GONE : View.VISIBLE);
        npControls.setVisibility(controls ? View.VISIBLE : View.INVISIBLE);
        npProgressTrack.setVisibility(progress < 0 ? View.GONE : View.VISIBLE);
        if (progress >= 0) {
            npProgress.setPivotX(0f);
            npProgress.setScaleX(progress);
        }
        renderArt(s);
    }

    private void renderPresets(boolean fm) {
        Preset[] presets = radioState.currentPresets();
        npPresets.removeAllViews();
        if (presets == null) return;
        int count = 0;
        for (Preset p : presets) {
            if (p == null || p.isEmpty()) continue;
            if (count == 5) break;
            boolean on = p.frequency == radioState.frequency;
            TextView chip = t.label(this, Band.formatValue(p.frequency, fm), 14, on ? WrxTheme.numBold() : WrxTheme.numMedium(),
                on ? t.onGold : t.muted);
            chip.setGravity(Gravity.CENTER);
            chip.setBackground(on ? t.cardOf(this, t.gold, 12, true) : t.cardOf(this, t.panel2, 12, true));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
            if (count > 0) lp.leftMargin = px(6);
            npPresets.addView(chip, lp);
            chip.setOnClickListener(v -> playPreset(p));
            count++;
        }
        npPresets.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
    }

    private void playPreset(Preset p) {
        if (snap == null || snap.source != SOURCE_RADIO) {
            PlayerHandoff.switchToRadio(this, radio::activateSource);
            session.assumeSource(SOURCE_RADIO);
        }
        radio.playPreset(p);
    }

    /** Carátula del MP3 o imagen de la emisora (K4811/radio/...), igual que la pantalla clásica. */
    private void renderArt(HomeSession.Snapshot s) {
        long id = 0;
        if (s.kind == HomeSession.Kind.USB && s.mediaId != null) {
            try {
                id = Long.parseLong(s.mediaId.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        String path = s.kind == HomeSession.Kind.USB ? s.path : null;
        boolean station = s.kind == HomeSession.Kind.RADIO && radioReady && radioState.frequency > 0;
        int band = radioState.band, frequency = radioState.frequency;
        String key = station ? "radio:" + StationArt.baseName(band, frequency)
            : id > 0 ? "id:" + id : path != null ? "path:" + path : null;
        if (key == null) {
            clearArt();
            return;
        }
        if (key.equals(artKey)) return;
        clearArt();
        artKey = key;
        int gen = ++artGen;
        long mediaId = id;
        int size = px(96);
        try {
            loader.execute(() -> {
                Bitmap bmp = station ? StationArt.load(band, frequency, size)
                    : AlbumArt.decode(getApplicationContext(), mediaId, path);
                handler.post(() -> {
                    if (isFinishing() || gen != artGen || bmp == null) return;
                    npArt.setImageBitmap(bmp);
                    npArt.setVisibility(View.VISIBLE);
                    npArtText.setVisibility(View.GONE);
                });
            });
        } catch (Exception ignored) {
        }
    }

    private void clearArt() {
        artGen++;
        artKey = null;
        npArt.setImageDrawable(null);
        npArt.setVisibility(View.GONE);
        npArtText.setVisibility(View.VISIBLE);
    }

    private void transport(int what) {
        if (snap == null) return;
        switch (snap.kind) {
            case RADIO:
                if (what == 0) {
                    if (snap.source == SOURCE_RADIO) {
                        radio.deactivateSource();
                        session.assumeSource(SOURCE_ANDROID);
                    } else {
                        PlayerHandoff.switchToRadio(this, radio::activateSource);
                        session.assumeSource(SOURCE_RADIO);
                    }
                } else {
                    if (snap.source != SOURCE_RADIO) {
                        PlayerHandoff.switchToRadio(this, radio::activateSource);
                        session.assumeSource(SOURCE_RADIO);
                    }
                    radio.presetStep(what > 0);
                }
                break;
            case USB:
                session.usbTransport(what < 0 ? HomeSession.USB_PREV
                    : what > 0 ? HomeSession.USB_NEXT : HomeSession.USB_TOGGLE);
                break;
            case BT:
                if (what < 0) session.btTransport(HomeSession.BT_PREV);
                else if (what > 0) session.btTransport(HomeSession.BT_NEXT);
                else session.btTransport(snap.btPlaying ? HomeSession.BT_PAUSE : HomeSession.BT_PLAY);
                break;
            default:
                break;
        }
    }

    private void openNowPlaying() {
        if (snap == null) return;
        switch (snap.kind) {
            case RADIO:
                DockSlots.open(this, "radio");
                break;
            case USB:
                DockSlots.open(this, "music");
                break;
            case BT:
                DockSlots.open(this, "bt_music");
                break;
            default:
                break;
        }
    }

    // ------------------------------------------------------------------ Bluetooth y apps

    private void queryBt() {
        if (bt == null) return;
        bt.queryPhoneInfo((hfp, powered, localName, pin, remoteName, autoFeatures, callState) -> {
            btHfp = hfp;
            renderBt();
        });
    }

    private void renderBt() {
        boolean a2dp = snap != null && snap.a2dpKnown && snap.a2dpConnected;
        btGlyph.color(btHfp || a2dp ? t.gold2 : t.dim);
    }

    private void openPkg(String pkg) {
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) {
            Toast.makeText(this, "No está instalada: " + pkg, Toast.LENGTH_LONG).show();
            return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }
}
