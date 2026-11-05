package com.k4811.launcher.wrx;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * Minimapa GPS en la esquina estilo GTA V:
 * - Cuadrícula de calles vectoriales en tono nocturno.
 * - Aro de radar con brújula 'N' que cambia de Azul Subaru (detenido) a Rojo STI (en marcha).
 * - Flecha de ubicación del vehículo en el centro.
 * - Barras de estado inferiores estilo GTA (Motor OK, Tracción AWD, Bencina).
 * - Al tocarlo abre Google Maps a pantalla completa.
 */
public class GtaRadarView extends View {

    private final Paint pFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF();

    private float headingDeg = 0f;
    private boolean isMoving = false;

    public GtaRadarView(Context context) {
        super(context);
        init();
    }

    public GtaRadarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        pStroke.setStyle(Paint.Style.STROKE);
        pFill.setStyle(Paint.Style.FILL);
        setOnClickListener(v -> {
            try {
                if (getContext() instanceof Activity) {
                    DockSlots.openOrExplain((Activity) getContext(), "maps");
                } else {
                    Intent i = getContext().getPackageManager().getLaunchIntentForPackage("com.google.android.apps.maps");
                    if (i != null) {
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        getContext().startActivity(i);
                    }
                }
            } catch (Exception ignored) {}
        });
    }

    public void update(boolean moving, float heading) {
        this.isMoving = moving;
        this.headingDeg = heading;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;

        float cx = w * 0.5f;
        float cy = h * 0.48f;
        float radius = Math.min(w, h) * 0.44f;

        // 1. Fondo base del radar (estilo GTA V oscuro)
        r.set(cx - radius, cy - radius, cx + radius, cy + radius);
        pFill.setColor(0xF00C1018);
        c.drawRoundRect(r, radius * 0.35f, radius * 0.35f, pFill);

        // Clip al área interna para las calles
        c.save();
        path.reset();
        path.addRoundRect(r, radius * 0.35f, radius * 0.35f, Path.Direction.CW);
        c.clipPath(path);

        // 2. Calles vectoriales estilo GTA
        pStroke.setColor(0xFF2D374B);
        pStroke.setStrokeWidth(radius * 0.08f); // Avenida principal
        c.drawLine(cx - radius * 1.1f, cy - radius * 0.3f, cx + radius * 1.1f, cy - radius * 0.1f, pStroke);

        pStroke.setColor(0xFF263042);
        pStroke.setStrokeWidth(radius * 0.10f); // Autopista
        c.drawLine(cx - radius * 0.2f, cy - radius * 1.1f, cx + radius * 0.3f, cy + radius * 1.1f, pStroke);

        pStroke.setStrokeWidth(radius * 0.04f); // Calles secundarias
        c.drawLine(cx - radius * 1.1f, cy + radius * 0.4f, cx + radius * 0.2f, cy + radius * 0.4f, pStroke);
        c.drawLine(cx + radius * 0.2f, cy + radius * 0.4f, cx + radius * 1.1f, cy + radius * 0.7f, pStroke);
        c.drawLine(cx - radius * 0.6f, cy - radius * 1.1f, cx - radius * 0.5f, cy + radius * 1.1f, pStroke);
        c.drawLine(cx + radius * 0.6f, cy - radius * 0.5f, cx + radius * 0.7f, cy + radius * 1.1f, pStroke);

        // Curva de río / costa
        pStroke.setColor(0xFF14243C);
        pStroke.setStrokeWidth(radius * 0.14f);
        r.set(cx - radius * 1.3f, cy - radius * 0.8f, cx + radius * 0.4f, cy + radius * 1.3f);
        c.drawArc(r, 40, 100, false, pStroke);

        // Anillos concéntricos de radar
        pStroke.setColor(isMoving ? 0x2AEE3030 : 0x2A1E6BFF);
        pStroke.setStrokeWidth(1.5f);
        c.drawCircle(cx, cy, radius * 0.45f, pStroke);
        c.drawCircle(cx, cy, radius * 0.80f, pStroke);

        // 3. Flecha del auto en el centro (gira según orientación)
        c.save();
        c.rotate(headingDeg, cx, cy);

        // Aura de posición GPS
        pFill.setColor(isMoving ? 0x40FF2A3A : 0x351E6BFF);
        c.drawCircle(cx, cy, radius * 0.22f, pFill);

        // Flecha triangular del jugador
        path.reset();
        float pLen = radius * 0.24f;
        path.moveTo(cx, cy - pLen);
        path.lineTo(cx - pLen * 0.65f, cy + pLen * 0.7f);
        path.lineTo(cx, cy + pLen * 0.35f);
        path.lineTo(cx + pLen * 0.65f, cy + pLen * 0.7f);
        path.close();

        pFill.setColor(0xFFFFFFFF);
        c.drawPath(path, pFill);

        pStroke.setColor(isMoving ? 0xFFEF4444 : 0xFF1E6BFF);
        pStroke.setStrokeWidth(2f);
        c.drawPath(path, pStroke);

        c.restore(); // Deshacer rotación

        c.restore(); // Deshacer clip

        // 4. Borde del radar: Azul Subaru (detenido) / Rojo STI (en marcha)
        int accentColor = isMoving ? 0xFFEF4444 : 0xFF1E6BFF;
        r.set(cx - radius, cy - radius, cx + radius, cy + radius);
        pStroke.setColor(accentColor);
        pStroke.setStrokeWidth(3f);
        c.drawRoundRect(r, radius * 0.35f, radius * 0.35f, pStroke);

        // Indicador 'N' (Norte)
        pText.setColor(isMoving ? 0xFFFF7B85 : 0xFF60A5FA);
        pText.setTextSize(radius * 0.16f);
        pText.setFakeBoldText(true);
        pText.setTextAlign(Paint.Align.CENTER);
        c.drawText("N", cx, cy - radius + (radius * 0.20f), pText);

        // 5. Barras de estado inferiores estilo GTA (Motor, AWD, Bencina)
        float barY = cy + radius - (radius * 0.14f);
        float barH = radius * 0.05f;
        float barW = radius * 1.5f;
        float barLeft = cx - barW * 0.5f;

        // Barra 1: Verde (Motor / Salud OK)
        pFill.setColor(0xFF4ADE80);
        c.drawRect(barLeft, barY, barLeft + barW * 0.45f, barY + barH, pFill);

        // Barra 2: Azul o Rojo (AWD / Turbo)
        pFill.setColor(accentColor);
        c.drawRect(barLeft + barW * 0.48f, barY, barLeft + barW * 0.76f, barY + barH, pFill);

        // Barra 3: Ámbar (Bencina)
        pFill.setColor(0xFFF59E0B);
        c.drawRect(barLeft + barW * 0.79f, barY, barLeft + barW, barY + barH, pFill);
    }
}
