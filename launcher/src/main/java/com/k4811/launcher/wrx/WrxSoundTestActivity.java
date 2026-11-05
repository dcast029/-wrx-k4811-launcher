package com.k4811.launcher.wrx;

import android.app.Activity;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.k4811.common.audio.AudioGains;
import com.k4811.common.eq.EqModel;
import com.k4811.common.eq.SettingClient;
import com.k4811.common.system.NwdSettings;
import com.k4811.common.ui.Ui;
import com.nwd.setting.service.SettingFeature;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Centro de Audio y Sonido WRX.
 * Separa claramente:
 * 1) Ecualizaciones y Presets (con indicación luminosa del perfil activo).
 * 2) Pruebas acústicas de parlantes y Subwoofer (aislamiento FL/FR/RL/RR).
 * 3) Nivelación de ganancias entre fuentes (Radio FM vs USB/CarPlay).
 */
public class WrxSoundTestActivity extends Activity {

    public static final String PREF_ACTIVE_EQ = "active_eq_name";
    private static final String DEFAULT_EQ = "WRX Boxer Bass & Clarity";

    private WrxTheme t;
    private SettingClient mcu;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean tonePlaying;
    private volatile AudioTrack activeTrack;
    private TextView statusLabel;

    private int activeTab = 0; // 0 = EQ, 1 = Pruebas, 2 = Ganancias
    private FrameLayout contentFrame;
    private TextView tabEqBtn;
    private TextView tabTestBtn;
    private TextView tabGainBtn;

    private int userFader = 7, userBalance = 7;
    private long sourceClaimedAt;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable restoreSite = () -> setSite(userFader, userBalance, "Listo · balance y fader como estaban");

    public static class EqPreset {
        public final String name;
        public final String desc;
        public final int[] bands; // 10 bandas
        public final boolean loudness;
        public final boolean isSpecialWrx;

        public EqPreset(String name, String desc, int[] bands, boolean loudness, boolean isSpecialWrx) {
            this.name = name;
            this.desc = desc;
            this.bands = bands;
            this.loudness = loudness;
            this.isSpecialWrx = isSpecialWrx;
        }
    }

    private static final EqPreset[] PRESETS = {
        // Perfiles Especiales WRX
        new EqPreset("WRX Boxer Bass & Clarity", "Sub-graves firmes para motor Boxer + agudos nítidos + Loudness",
            new int[]{10, 9, 7, 6, 7, 8, 8, 9, 10, 10}, true, true),
        new EqPreset("Ruta & Autopista", "Compensa ruido de escape, viento y rodado a 120 km/h",
            new int[]{9, 9, 8, 7, 7, 8, 9, 9, 9, 8}, true, true),
        new EqPreset("Subwoofer Punch", "Impacto seco y contundente en frecuencias bajas (55–80 Hz)",
            new int[]{12, 11, 9, 6, 6, 7, 7, 8, 8, 9}, false, true),

        // Ecualizaciones Típicas de Fábrica
        new EqPreset("Plano (Original)", "Respuesta plana de referencia sin coloración de audio",
            new int[]{7, 7, 7, 7, 7, 7, 7, 7, 7, 7}, false, false),
        new EqPreset("Rock", "Curva en V clásica: bajos marcados y platillos vivos para guitarras y batería",
            new int[]{11, 9, 7, 5, 6, 7, 9, 10, 11, 11}, true, false),
        new EqPreset("Pop", "Realce armónico en frecuencias medias para destacar voces e instrumentos",
            new int[]{8, 9, 10, 9, 7, 6, 8, 9, 9, 9}, false, false),
        new EqPreset("Electrónica / Dance", "Sub-graves extendidos con agudos brillantes para ritmos electrónicos",
            new int[]{12, 11, 8, 6, 6, 7, 8, 10, 11, 12}, true, false),
        new EqPreset("Jazz", "Sonido acústico cálido, íntimo y balanceado con cuerpo en contrabajos",
            new int[]{9, 8, 7, 7, 8, 8, 8, 9, 9, 9}, false, false),
        new EqPreset("Clásica", "Máximo rango dinámico, naturalidad y balance para piezas orquestales",
            new int[]{10, 9, 8, 7, 7, 7, 8, 9, 10, 10}, false, false),
        new EqPreset("Voz / Podcast", "Atenuación de graves y realce de presencia vocal para noticias y podcasts",
            new int[]{5, 6, 7, 9, 11, 10, 9, 8, 6, 5}, false, false)
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Ui.applyDesignDensity(this);
        super.onCreate(savedInstanceState);
        WrxTheme.loadFonts(this);
        int mode = WrxPrefs.themeMode(this);
        t = new WrxTheme(mode == 2 || (mode == 0 && NwdSettings.headlightsOn(this)));

        userFader = Settings.System.getInt(getContentResolver(), "mcu_fader", 7);
        userBalance = Settings.System.getInt(getContentResolver(), "mcu_balance", 7);

        mcu = new SettingClient(this, new AudioGains.Callbacks() {
            @Override
            public void onConnection(EqModel.Connection connection) {
                runOnUiThread(() -> {
                    if (statusLabel != null) {
                        statusLabel.setText(connection == EqModel.Connection.CONNECTED
                            ? "MCU de Audio conectada · Lista"
                            : "Conectando con procesador de audio MCU…");
                    }
                });
            }
        });
        mcu.connect();

        buildUi();
    }

    private int px(float v) {
        return WrxTheme.px(this, v);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(t.bg);
        WrxTheme.pad(root, 28, 16, 28, 0);
        setContentView(root);

        // Header
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(head, new LinearLayout.LayoutParams(-1, px(60)));

        TextView back = WrxDialog.button(this, t, "‹  Volver", false);
        head.addView(back, new LinearLayout.LayoutParams(px(130), px(50)));
        back.setOnClickListener(v -> finish());

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1);
        tlp.leftMargin = px(20);
        head.addView(titles, tlp);

        titles.addView(t.label(this, "Audio y Ecualizador WRX", 25, WrxTheme.bodyBold(), t.text));
        statusLabel = t.label(this, "Conectando con MCU…", 13, WrxTheme.bodyMedium(), t.gold);
        titles.addView(statusLabel);

        TextView stopBtn = WrxDialog.button(this, t, "Detener Tono", true);
        stopBtn.setBackground(t.cardOf(this, WrxTheme.RED, 16, true));
        stopBtn.setTextColor(0xFFFFFFFF);
        head.addView(stopBtn, new LinearLayout.LayoutParams(px(150), px(48)));
        stopBtn.setOnClickListener(v -> {
            stopTone();
            ui.removeCallbacks(restoreSite);
            restoreSite.run();
        });

        // Tabs de navegación
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams tblp = new LinearLayout.LayoutParams(-1, px(52));
        tblp.topMargin = px(12);
        root.addView(tabs, tblp);

        tabEqBtn = createTabBtn("🎛️  ECUALIZACIÓN", 0, tabs);
        tabTestBtn = createTabBtn("🔊  PRUEBA DE PARLANTES", 1, tabs);
        tabGainBtn = createTabBtn("⚖️  GANANCIAS", 2, tabs);

        // Frame de contenido
        contentFrame = new FrameLayout(this);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, 0, 1);
        clp.topMargin = px(10);
        root.addView(contentFrame, clp);

        switchTab(0);
    }

    private TextView createTabBtn(String label, int tabIdx, LinearLayout parent) {
        TextView b = t.label(this, label, 16, WrxTheme.bodyBold(), t.muted);
        b.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
        if (parent.getChildCount() > 0) lp.leftMargin = px(10);
        parent.addView(b, lp);
        b.setOnClickListener(v -> switchTab(tabIdx));
        return b;
    }

    private void switchTab(int idx) {
        activeTab = idx;
        stopTone();

        // Actualizar visual de los botones de pestaña
        updateTabStyles(tabEqBtn, activeTab == 0);
        updateTabStyles(tabTestBtn, activeTab == 1);
        updateTabStyles(tabGainBtn, activeTab == 2);

        contentFrame.removeAllViews();
        if (activeTab == 0) {
            contentFrame.addView(buildEqView());
        } else if (activeTab == 1) {
            contentFrame.addView(buildSpeakerTestView());
        } else {
            contentFrame.addView(buildGainView());
        }
    }

    private void updateTabStyles(TextView b, boolean active) {
        b.setBackground(active ? t.cardOf(this, t.gold, 16, true) : t.cardOf(this, t.panel2, 16, false));
        b.setTextColor(active ? t.onGold : t.muted);
    }

    // ------------------------------------------------------------------ 1. Vista Ecualización
    private View buildEqView() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        WrxTheme.pad(list, 0, 8, 0, 24);
        scroll.addView(list);

        String activeEq = WrxPrefs.get(this).getString(PREF_ACTIVE_EQ, DEFAULT_EQ);

        // Banner resumen del perfil actualmente activo
        LinearLayout activeBanner = new LinearLayout(this);
        activeBanner.setOrientation(LinearLayout.HORIZONTAL);
        activeBanner.setGravity(Gravity.CENTER_VERTICAL);
        activeBanner.setBackground(t.cardOf(this, t.panel2, 18, true));
        WrxTheme.pad(activeBanner, 20, 14, 20, 14);

        LinearLayout bannerText = new LinearLayout(this);
        bannerText.setOrientation(LinearLayout.VERTICAL);
        bannerText.addView(t.label(this, "PERFIL SELECCIONADO ACTUALMENTE", 12, WrxTheme.bodyBold(), t.gold));
        TextView curLbl = t.label(this, activeEq, 21, WrxTheme.bodyBold(), t.text);
        bannerText.addView(curLbl);
        activeBanner.addView(bannerText, new LinearLayout.LayoutParams(0, -2, 1));

        TextView appliedBadge = t.label(this, "● ACTIVO EN MCU", 13, WrxTheme.bodyBold(), t.gold);
        appliedBadge.setBackground(t.cardOf(this, t.panel, 12, true));
        WrxTheme.pad(appliedBadge, 14, 8, 14, 8);
        activeBanner.addView(appliedBadge);

        list.addView(activeBanner, new LinearLayout.LayoutParams(-1, -2));

        // 1. Perfiles Especiales WRX
        section(list, "PERFILES ESPECIALES WRX (CALIBRADOS PARA EL AUTO)");
        renderPresetGrid(list, true, activeEq);

        // 2. Ecualizaciones Típicas
        section(list, "ECUALIZACIONES TÍPICAS (DE FÁBRICA)");
        renderPresetGrid(list, false, activeEq);

        return scroll;
    }

    private void renderPresetGrid(LinearLayout list, boolean wrxOnly, String currentActive) {
        LinearLayout row = null;
        int inRow = 0;

        for (EqPreset p : PRESETS) {
            if (p.isSpecialWrx != wrxOnly) continue;

            if (inRow == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
                rlp.topMargin = px(8);
                list.addView(row, rlp);
            }

            boolean isCurrent = p.name.equalsIgnoreCase(currentActive);
            LinearLayout card = createEqCard(p, isCurrent);

            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, px(90), 1);
            if (inRow > 0) clp.leftMargin = px(10);
            row.addView(card, clp);

            inRow++;
            if (inRow == 2) {
                inRow = 0;
            }
        }

        // Rellenar espacio vacío si la fila quedó con 1 solo elemento
        if (inRow == 1 && row != null) {
            View empty = new View(this);
            LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(0, px(90), 1);
            elp.leftMargin = px(10);
            row.addView(empty, elp);
        }
    }

    private LinearLayout createEqCard(EqPreset p, boolean isCurrent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        // Si está seleccionado, resalta con borde dorado y fondo luminoso
        card.setBackground(isCurrent ? t.shape(this, t.panel2, 18, t.gold) : t.card(this, 18));
        WrxTheme.pad(card, 18, 10, 18, 10);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);

        TextView title = t.label(this, p.name, 17, WrxTheme.bodyBold(), isCurrent ? t.gold : t.text);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1));

        if (isCurrent) {
            TextView badge = t.label(this, "✓ ACTIVO", 11.5f, WrxTheme.bodyBold(), t.gold);
            badge.setBackground(t.cardOf(this, t.panel, 10, true));
            WrxTheme.pad(badge, 8, 4, 8, 4);
            titleRow.addView(badge);
        }

        card.addView(titleRow, new LinearLayout.LayoutParams(-1, -2));

        TextView desc = t.label(this, p.desc, 12, WrxTheme.body(), t.muted);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, -2);
        dlp.topMargin = px(4);
        card.addView(desc, dlp);

        card.setOnClickListener(v -> {
            applyCustomMcuEq(p.bands, p.loudness, "Perfil " + p.name + " activado");
            WrxPrefs.get(this).edit().putString(PREF_ACTIVE_EQ, p.name).apply();
            // Re-renderizar la vista para que el highlight se mueva de inmediato
            contentFrame.removeAllViews();
            contentFrame.addView(buildEqView());
        });

        return card;
    }

    // ------------------------------------------------------------------ 2. Vista Pruebas de Parlantes
    private View buildSpeakerTestView() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        WrxTheme.pad(list, 0, 8, 0, 24);
        scroll.addView(list);

        section(list, "PROBADOR INDIVIDUAL DE PARLANTES (BALANCE / FADER + TONO)");
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        list.addView(row1, new LinearLayout.LayoutParams(-1, px(76)));
        speakerBtn(row1, "Delantero Izq (FL)", "Puerta Chofer", 0, 0, 440);
        speakerBtn(row1, "Delantero Der (FR)", "Puerta Copiloto", 0, 14, 554);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams r2lp = new LinearLayout.LayoutParams(-1, px(76));
        r2lp.topMargin = px(8);
        list.addView(row2, r2lp);
        speakerBtn(row2, "Trasero Izq (RL)", "Atrás Izquierda", 14, 0, 330);
        speakerBtn(row2, "Trasero Der (RR)", "Atrás Derecha", 14, 14, 392);

        LinearLayout row3 = new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams r3lp = new LinearLayout.LayoutParams(-1, px(72));
        r3lp.topMargin = px(8);
        list.addView(row3, r3lp);
        actionBtn(row3, "Volver al Centro (4 Parlantes)", "Restaura Fader 7 / Balance 7", true, () -> {
            stopTone();
            keepSite(7, 7, "Sonido centrado en los 4 parlantes");
        });
        actionBtn(row3, "Posición Chofer WRX", "Enfoca levemente hacia tu asiento", false, () -> {
            stopTone();
            keepSite(6, 8, "Enfocado en asiento del conductor");
        });

        section(list, "PRUEBA DE SUBWOOFER Y VIBRACIÓN DE PUERTAS");
        LinearLayout rowSub = new LinearLayout(this);
        rowSub.setOrientation(LinearLayout.HORIZONTAL);
        list.addView(rowSub, new LinearLayout.LayoutParams(-1, px(78)));

        actionBtn(rowSub, "Barrido Subwoofer 40–80 Hz (6 s)",
            "Onda pura de sub-graves para probar el woofer", true, () -> {
                setSite(7, 7, "Probando Subwoofer: barrido 40 Hz → 80 Hz…");
                playBassSweep();
            });
        actionBtn(rowSub, "Golpe Grave 55 Hz (Punch)",
            "Pulsos rítmicos de 55 Hz para revisar golpe seco", false, () -> {
                setSite(7, 7, "Probando golpe de graves a 55 Hz…");
                playBassPulse();
            });

        return scroll;
    }

    // ------------------------------------------------------------------ 3. Vista Ganancias
    private View buildGainView() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        WrxTheme.pad(list, 0, 8, 0, 24);
        scroll.addView(list);

        section(list, "NIVELADOR DE VOLUMEN ENTRE FUENTES (RADIO FM vs USB / CARPLAY)");
        LinearLayout rowGain = new LinearLayout(this);
        rowGain.setOrientation(LinearLayout.HORIZONTAL);
        list.addView(rowGain, new LinearLayout.LayoutParams(-1, px(84)));

        actionBtn(rowGain, "Emparejar Volumen de Fuentes",
            "Baja Radio FM de 9 a 5 y sube USB/AUX a 6 para evitar saltos bruscos", true,
            () -> normalizeSourceGains(5, 6, 6, 6));
        actionBtn(rowGain, "Ganancias de Fábrica",
            "Vuelve a Radio=9, BT=9, AUX=3, USB=0", false,
            () -> normalizeSourceGains(9, 9, 3, 0));

        return scroll;
    }

    private void section(LinearLayout list, String text) {
        TextView h = t.label(this, text, 12.5f, WrxTheme.bodyBold(), t.gold);
        WrxTheme.spacing(h, 0.14f);
        WrxTheme.pad(h, 4, 18, 0, 6);
        list.addView(h);
    }

    private void speakerBtn(LinearLayout row, String title, String sub, int fader, int balance, int freqHz) {
        actionBtn(row, title, sub, false, () -> {
            setSite(fader, balance, "Probando: " + title + " (" + sub + ")");
            playTestBeep(freqHz);
            ui.removeCallbacks(restoreSite);
            ui.postDelayed(restoreSite, 3200);
        });
    }

    private void actionBtn(LinearLayout row, String title, String sub, boolean highlight, Runnable onTap) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(highlight ? t.cardOf(this, t.panel2, 18, true) : t.card(this, 18));
        WrxTheme.pad(card, 18, 10, 18, 10);
        card.addView(t.label(this, title, 16.5f, WrxTheme.bodyBold(), highlight ? t.gold : t.text));
        TextView s = t.label(this, sub, 12, WrxTheme.body(), t.muted);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.topMargin = px(3);
        card.addView(s, slp);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
        if (row.getChildCount() > 0) lp.leftMargin = px(10);
        row.addView(card, lp);
        card.setOnClickListener(v -> onTap.run());
    }

    private void keepSite(int fader, int balance, String msg) {
        userFader = fader;
        userBalance = balance;
        ui.removeCallbacks(restoreSite);
        setSite(fader, balance, msg);
    }

    private void claimAndroidSource() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - sourceClaimedAt < 15000) return;
        sourceClaimedAt = now;
        try {
            com.k4811.common.audio.PlayerHandoff.beforeMp3(this);
            android.content.Intent src = new android.content.Intent(com.k4811.common.system.NwdConstants.ACTION_REQUEST_CHANGE_SOURCE);
            src.putExtra(com.k4811.common.system.NwdConstants.EXTRA_SOURCE_ID, (byte) 0);
            sendBroadcast(src);
            Thread.sleep(900);
        } catch (Exception ignored) {
        }
    }

    private void setSite(int fader, int balance, String msg) {
        if (statusLabel != null) statusLabel.setText(msg);
        worker.execute(() -> {
            try {
                Settings.System.putInt(getContentResolver(), "mcu_fader", fader);
                Settings.System.putInt(getContentResolver(), "mcu_balance", balance);
            } catch (Exception ignored) {
            }
            SettingFeature f = mcu.feature();
            if (f != null) {
                try {
                    f.setAudioParam(SettingClient.AUDIO_FADER, fader);
                    f.setAudioParam(SettingClient.AUDIO_BALANCE, balance);
                } catch (Exception ignored) {
                }
            }
        });
    }

    private void applyCustomMcuEq(int[] bands10, boolean loudness, String msg) {
        if (statusLabel != null) statusLabel.setText(msg);
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        worker.execute(() -> {
            SettingFeature f = mcu.feature();
            if (f == null) return;
            try {
                int max = Settings.System.getInt(getContentResolver(), "dsp_max_eq", 14);
                if (max <= 0) max = 14;
                f.setAudioParam(SettingClient.AUDIO_EQ_PRESET, EqModel.Preset.CUSTOM.mcuCode);
                for (int i = 0; i < bands10.length; i++) {
                    int scaled = Math.min(max, Math.max(0, Math.round(bands10[i] * (max / 14f))));
                    f.setDspAudio((byte) EqModel.dspType(i), (byte) scaled);
                }
                f.setAudioParam(SettingClient.AUDIO_LOUDNESS, loudness ? 1 : 0);
            } catch (Exception ignored) {
            }
        });
    }

    private void normalizeSourceGains(int radioGain, int btGain, int auxGain, int usbGain) {
        String msg = "Ganancias aplicadas: Radio=" + radioGain + ", BT=" + btGain + ", USB/AUX=" + usbGain;
        if (statusLabel != null) statusLabel.setText(msg);
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        worker.execute(() -> {
            try {
                Settings.System.putInt(getContentResolver(), "mcu_gain_radio", radioGain);
                Settings.System.putInt(getContentResolver(), "mcu_gain_bt", btGain);
                Settings.System.putInt(getContentResolver(), "mcu_gain_aux", auxGain);
                Settings.System.putInt(getContentResolver(), "mcu_gain_aux2", auxGain);
                Settings.System.putInt(getContentResolver(), "mcu_gain_canbus_usb", usbGain);
            } catch (Exception ignored) {
            }
            SettingFeature f = mcu.feature();
            if (f != null) {
                try {
                    AudioGains.apply(this, f, 7, radioGain);
                    AudioGains.apply(this, f, 2, btGain);
                    AudioGains.apply(this, f, 6, auxGain);
                    AudioGains.apply(this, f, 9, usbGain);
                } catch (Exception ignored) {
                }
            }
        });
    }

    private void stopTone() {
        tonePlaying = false;
        AudioTrack at = activeTrack;
        activeTrack = null;
        if (at != null) {
            try {
                at.stop();
            } catch (Exception ignored) {
            }
            try {
                at.release();
            } catch (Exception ignored) {
            }
        }
    }

    private void playTestBeep(int freqHz) {
        stopTone();
        tonePlaying = true;
        worker.execute(() -> {
            final int sampleRate = 44100;
            final int durationMs = 1800;
            final int totalSamples = (sampleRate * durationMs) / 1000;
            short[] pcm = new short[totalSamples];
            for (int i = 0; i < totalSamples; i++) {
                double tSec = i / (double) sampleRate;
                double env = (i % (sampleRate / 2)) < (sampleRate / 3) ? 1.0 : 0.15;
                double wave = Math.sin(2.0 * Math.PI * freqHz * tSec)
                    + 0.35 * Math.sin(2.0 * Math.PI * (freqHz * 2) * tSec);
                pcm[i] = (short) (wave * env * 14000);
            }
            playPcmBuffer(pcm, sampleRate);
        });
    }

    private void playBassSweep() {
        stopTone();
        tonePlaying = true;
        worker.execute(() -> {
            final int sampleRate = 44100;
            final int durationSec = 6;
            final int totalSamples = sampleRate * durationSec;
            short[] pcm = new short[totalSamples];
            double phase = 0.0;
            for (int i = 0; i < totalSamples; i++) {
                double progress = i / (double) totalSamples;
                double freq = 40.0 + (40.0 * progress);
                phase += (2.0 * Math.PI * freq) / sampleRate;
                double env = progress < 0.05 ? (progress / 0.05) : (progress > 0.95 ? (1.0 - progress) / 0.05 : 1.0);
                pcm[i] = (short) (Math.sin(phase) * env * 26000);
            }
            playPcmBuffer(pcm, sampleRate);
        });
    }

    private void playBassPulse() {
        stopTone();
        tonePlaying = true;
        worker.execute(() -> {
            final int sampleRate = 44100;
            final int totalSamples = sampleRate * 4;
            short[] pcm = new short[totalSamples];
            int beatSamples = sampleRate / 2;
            for (int i = 0; i < totalSamples; i++) {
                int inBeat = i % beatSamples;
                double decay = Math.exp(-5.0 * inBeat / (double) beatSamples);
                double tSec = i / (double) sampleRate;
                pcm[i] = (short) (Math.sin(2.0 * Math.PI * 55.0 * tSec) * decay * 28000);
            }
            playPcmBuffer(pcm, sampleRate);
        });
    }

    private void playPcmBuffer(short[] pcm, int sampleRate) {
        if (!tonePlaying) return;
        claimAndroidSource();
        if (!tonePlaying) return;
        try {
            AudioTrack track = new AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                pcm.length * 2,
                AudioTrack.MODE_STATIC
            );
            activeTrack = track;
            track.write(pcm, 0, pcm.length);
            track.play();
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Ui.hideSystemBars(getWindow());
    }

    @Override
    protected void onPause() {
        stopTone();
        ui.removeCallbacks(restoreSite);
        setSite(userFader, userBalance, "");
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopTone();
        if (mcu != null) mcu.release();
        worker.shutdownNow();
        super.onDestroy();
    }
}
