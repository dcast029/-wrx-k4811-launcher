package com.k4811.launcher.wrx;

import android.content.Context;
import android.os.SystemClock;

import com.k4811.launcher.home.VolumeClient;

/**
 * Volumen según la velocidad: en carretera sube unos pasos para tapar el ruido del escape y los
 * neumáticos, y al bajar la velocidad vuelve. Solo suma o resta la diferencia, así que si el
 * conductor cambia el volumen a mano se respeta su ajuste (queda como nueva base).
 *
 *   menos de 50 km/h: +0 · 50–80: +1 · 80–100: +2 · más de 100: +3
 *
 * Usa histéresis (4 km/h) y espera 6 s estable antes de cambiar, para no subir y bajar en cada
 * frenada. Se activa en Ajustes WRX ("Volumen según velocidad").
 */
final class SpeedVolume {

    private static final int[] LIMITS = {50, 80, 100};
    private static final int HYSTERESIS = 4;
    private static final long STABLE_MS = 6000;

    private final Context c;
    private VolumeClient volume;
    /** Pasos que hemos sumado nosotros sobre el volumen del usuario. */
    private int applied;
    private int pending = -1;
    private long pendingSince;

    SpeedVolume(Context c) {
        this.c = c.getApplicationContext();
    }

    void onSpeed(float kmh) {
        boolean on = WrxPrefs.on(c, WrxPrefs.SPEED_VOLUME, true);
        int target = (!on || Float.isNaN(kmh)) ? 0 : level(kmh);
        long now = SystemClock.elapsedRealtime();
        if (target == applied) {
            pending = -1;
            return;
        }
        if (target != pending) {
            pending = target;
            pendingSince = now;
            return;
        }
        // Apagar o perder el GPS: devolver de inmediato; si no, esperar a que sea estable.
        if (target != 0 && now - pendingSince < STABLE_MS) return;
        ensure();
        if (volume == null) return;
        int delta = target - applied;
        if (volume.volume() <= 0 && delta > 0) return; // silenciado: no subir solo
        volume.nudge(delta);
        applied = target;
        pending = -1;
    }

    /** Nivel con histéresis: para bajar de nivel hay que estar 4 km/h bajo el límite. */
    private int level(float kmh) {
        int lvl = 0;
        for (int i = 0; i < LIMITS.length; i++) {
            int limit = LIMITS[i];
            if (applied > i) limit -= HYSTERESIS;
            if (kmh >= limit) lvl = i + 1;
        }
        return lvl;
    }

    private void ensure() {
        if (volume != null) return;
        volume = new VolumeClient(c);
        volume.connect();
    }

    /** Al apagar: dejar el volumen como lo tenía el conductor. */
    void release() {
        if (volume != null) {
            if (applied != 0) volume.nudge(-applied);
            applied = 0;
            volume.release();
            volume = null;
        }
    }
}
