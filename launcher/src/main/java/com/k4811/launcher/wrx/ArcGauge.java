package com.k4811.launcher.wrx;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * Medidor en arco. SPEED: semicírculo grande con marcas (velocímetro GPS). RING: anillo de 270°
 * pequeño (batería, temperatura, ventilador). El valor se anima suavemente.
 */
public class ArcGauge extends View {

    public enum Mode { SPEED, RING }

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private Mode mode = Mode.RING;
    private float min = 0, max = 100;
    private float shown = 0;
    private int color = 0xFFD6A94A, color2 = 0;
    private ValueAnimator anim;
    private float[] labels = new float[0];

    public ArcGauge(Context c) {
        super(c);
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeCap(Paint.Cap.ROUND);
        bar.setStyle(Paint.Style.STROKE);
        bar.setStrokeCap(Paint.Cap.ROUND);
        tick.setTextAlign(Paint.Align.CENTER);
    }

    public ArcGauge mode(Mode m) {
        mode = m;
        invalidate();
        return this;
    }

    public ArcGauge range(float min, float max) {
        this.min = min;
        this.max = max;
        invalidate();
        return this;
    }

    public ArcGauge labels(float... values) {
        labels = values;
        invalidate();
        return this;
    }

    public ArcGauge colors(int trackColor, int barColor, int barColor2, int labelColor) {
        track.setColor(trackColor);
        color = barColor;
        color2 = barColor2;
        tick.setColor(labelColor);
        bar.setShader(null);
        invalidate();
        return this;
    }

    public void setValue(float v, boolean animate) {
        float target = Math.max(min, Math.min(max, v));
        if (anim != null) anim.cancel();
        if (!animate) {
            shown = target;
            invalidate();
            return;
        }
        anim = ValueAnimator.ofFloat(shown, target);
        anim.setDuration(600);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> {
            shown = (float) a.getAnimatedValue();
            invalidate();
        });
        anim.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (anim != null) anim.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float d = getResources().getDisplayMetrics().density;
        float frac = (shown - min) / (max - min);
        if (mode == Mode.SPEED) {
            float sw = 10 * d;
            float rad = Math.min(w / 2f - sw - 18 * d, h - sw - 30 * d);
            float cx = w / 2f, cy = rad + sw / 2f + 26 * d;
            oval.set(cx - rad, cy - rad, cx + rad, cy + rad);
            track.setStrokeWidth(sw);
            bar.setStrokeWidth(sw);
            c.drawArc(oval, 180, 180, false, track);
            if (color2 != 0 && bar.getShader() == null) {
                bar.setShader(new LinearGradient(cx - rad, 0, cx + rad, 0, color, color2, Shader.TileMode.CLAMP));
            } else if (color2 == 0) {
                bar.setColor(color);
            }
            if (frac > 0.003f) c.drawArc(oval, 180, 180 * frac, false, bar);
            tick.setTextSize(13 * d);
            tick.setTypeface(WrxTheme.numMedium());
            for (float v : labels) {
                double a = Math.PI * (1 - (v - min) / (max - min));
                float lr = rad + 22 * d;
                float x = cx + (float) Math.cos(a) * lr;
                float y = cy - (float) Math.sin(a) * lr + 5 * d;
                if (v == min || v == max) y = cy + 24 * d;
                c.drawText(String.valueOf(Math.round(v)), x, y, tick);
            }
        } else {
            float sw = 6 * d;
            float rad = Math.min(w, h) / 2f - sw;
            float cx = w / 2f, cy = h / 2f;
            oval.set(cx - rad, cy - rad, cx + rad, cy + rad);
            track.setStrokeWidth(sw);
            bar.setStrokeWidth(sw);
            bar.setShader(null);
            bar.setColor(color);
            c.drawArc(oval, 135, 270, false, track);
            if (frac > 0.003f) c.drawArc(oval, 135, 270 * frac, false, bar);
        }
    }
}
