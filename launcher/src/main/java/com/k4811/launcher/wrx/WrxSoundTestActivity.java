package com.k4811.launcher.wrx;

import android.app.Activity;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
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
 * Centro de Pruebas de Sonido, Parlantes, Subwoofer y Ganancias para el Subaru WRX.
 * Permite aislar cada parlante (FL, FR, RL, RR) vía Fader/Balance de la MCU,
 * emitir un barrido de sub-graves (40-80 Hz) para el subwoofer, aplicar perfiles EQ
 * y nivelar la diferencia de volumen entre Radio FM (actualmente en 9) y USB/CarPlay.
 */
public class WrxSoundTestActivity extends Activity {

    private WrxTheme t;
    private SettingClient mcu;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean tonePlaying;
    private volatile AudioTrack activeTrack;
    private TextView statusLabel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Ui.applyDesignDensity(this);
        super.onCreate(savedInstanceState);
        WrxTheme.loadFonts(this);
        int mode = WrxPrefs.themeMode(this);
        t = new WrxTheme(mode == 2 || (mode == 0 && NwdSettings.headlightsOn(this)));

        // Balance y fader del usuario: las pruebas de cada parlante los mueven solo un momento.
        userFader = Settings.System.getInt(getContentResolver(), "mcu_fader", 7);
        userBalance = Settings.System.getInt(getContentResolver(), "mcu_balance", 7);

        mcu = new SettingClient(this, new AudioGains.Callbacks() {
            @Override
            public void onConnection(EqModel.Connection connection) {
                runOnUiThread(() -> {
                    if (statusLabel != null) {
                        statusLabel.setText(connection == EqModel.Connection.CONNECTED
                            ? "MCU de Audio conectada · Lista para pruebas"
                            : "Conectando con el procesador de audio MCU…");
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
        WrxTheme.pad(root, 28, 18, 28, 0);
        setContentView(root);

        // Header
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(head, new LinearLayout.LayoutParams(-1, px(64)));

        TextView back = WrxDialog.button(this, t, "‹  Volver", false);
        head.addView(back, new LinearLayout.LayoutParams(px(130), px(52)));
        back.setOnClickListener(v -> finish());

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1);
        tlp.leftMargin = px(20);
        head.addView(titles, tlp);

        titles.addView(t.label(this, "Sonido, Parlantes y Subwoofer WRX", 26, WrxTheme.bodyBold(), t.text));
        statusLabel = t.label(this, "Conectando con MCU…", 13, WrxTheme.bodyMedium(), t.gold);
        titles.addView(statusLabel);

        TextView stopBtn = WrxDialog.button(this, t, "Detener Tono", true);
        stopBtn.setBackground(t.cardOf(this, WrxTheme.RED, 16, true));
        stopBtn.setTextColor(0xFFFFFFFF);
        head.addView(stopBtn, new LinearLayout.LayoutParams(px(160), px(50)));
        stopBtn.setOnClickListener(v -> {
            stopTone();
            ui.removeCallbacks(restoreSite);
            restoreSite.run();
        });

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        WrxTheme.pad(list, 0, 8, 0, 28);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        // 1. Probador de Parlantes
        section(list, "1. PROBADOR DE PARLANTES (BALANCE / FADER + TONO)");
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

        // 2. Subwoofer
        section(list, "2. PRUEBA DE SUBWOOFER Y VIBRACIÓN DE PUERTAS");
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

        // 3. Perfiles EQ
        section(list, "3. PERFILES DE ECUALIZACIÓN RÁPIDA (1 TOQUE)");
        LinearLayout rowEq = new LinearLayout(this);
        rowEq.setOrientation(LinearLayout.HORIZONTAL);
        list.addView(rowEq, new LinearLayout.LayoutParams(-1, px(78)));

        actionBtn(rowEq, "WRX Bass & Clarity",
            "Sub-graves firmes + agudos nítidos + Loudness", true,
            () -> applyCustomMcuEq(new int[]{10, 9, 7, 6, 7, 8, 8, 9, 10, 10}, true, "Perfil WRX Bass & Clarity activado"));
        actionBtn(rowEq, "Ruta / Motor Boxer",
            "Compensa ruido de escape y neumáticos en carretera", false,
            () -> applyCustomMcuEq(new int[]{9, 9, 8, 7, 7, 8, 9, 9, 9, 8}, true, "Perfil Ruta / Motor Boxer activado"));
        actionBtn(rowEq, "Plano (Original)",
            "Todas las bandas al centro sin colorear", false,
            () -> applyCustomMcuEq(new int[]{7, 7, 7, 7, 7, 7, 7, 7, 7, 7}, false, "Ecualizador plano restaurado"));

        // 4. Nivelador de Ganancia entre Fuentes
        section(list, "4. NIVELADOR DE VOLUMEN ENTRE FUENTES (RADIO FM vs USB / CARPLAY)");
        LinearLayout rowGain = new LinearLayout(this);
        rowGain.setOrientation(LinearLayout.HORIZONTAL);
        list.addView(rowGain, new LinearLayout.LayoutParams(-1, px(82)));

        actionBtn(rowGain, "Emparejar Volumen de Fuentes",
            "Baja Radio FM de 9 a 5 y sube USB/AUX a 6 para evitar saltos bruscos", true,
            () -> normalizeSourceGains(5, 6, 6, 6));
        actionBtn(rowGain, "Ganancias de Fábrica",
            "Vuelve a Radio=9, BT=9, AUX=3, USB=0", false,
            () -> normalizeSourceGains(9, 9, 3, 0));
    }

    private void section(LinearLayout list, String text) {
        TextView h = t.label(this, text, 12, WrxTheme.bodyBold(), t.gold);
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
        card.addView(t.label(this, title, 17, WrxTheme.bodyBold(), highlight ? t.gold : t.text));
        TextView s = t.label(this, sub, 12.5f, WrxTheme.body(), t.muted);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.topMargin = px(3);
        card.addView(s, slp);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
        if (row.getChildCount() > 0) lp.leftMargin = px(10);
        row.addView(card, lp);
        card.setOnClickListener(v -> onTap.run());
    }

    private int userFader = 7, userBalance = 7;
    private long sourceClaimedAt;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable restoreSite = () -> setSite(userFader, userBalance, "Listo · balance y fader como estaban");

    /** Cambia la posición del sonido y la deja como la del usuario (no se revierte). */
    private void keepSite(int fader, int balance, String msg) {
        userFader = fader;
        userBalance = balance;
        ui.removeCallbacks(restoreSite);
        setSite(fader, balance, msg);
    }

    /**
     * El tono sale por el audio de Android: la radio solo lo deja pasar si la fuente activa es
     * Android (si está la radio FM, se escucha la FM). Igual que la app de música: pausa los otros
     * reproductores y pide la fuente 0.
     */
    private void claimAndroidSource() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - sourceClaimedAt < 15000) return;
        sourceClaimedAt = now;
        try {
            com.k4811.common.audio.PlayerHandoff.beforeMp3(this);
            android.content.Intent src = new android.content.Intent(com.k4811.common.system.NwdConstants.ACTION_REQUEST_CHANGE_SOURCE);
            src.putExtra(com.k4811.common.system.NwdConstants.EXTRA_SOURCE_ID, (byte) 0);
            sendBroadcast(src);
            // Damos tiempo a la MCU para cambiar de fuente antes de sonar.
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
                    AudioGains.apply(this, f, 7, radioGain); // Radio
                    AudioGains.apply(this, f, 2, btGain);    // BT
                    AudioGains.apply(this, f, 6, auxGain);   // AUX
                    AudioGains.apply(this, f, 9, usbGain);   // Media/USB
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
                // Dos pulsos suaves por segundo para ubicar fácil el parlante
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
                double freq = 40.0 + (40.0 * progress); // 40 Hz -> 80 Hz
                phase += (2.0 * Math.PI * freq) / sampleRate;
                // Fade in / out suave
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

