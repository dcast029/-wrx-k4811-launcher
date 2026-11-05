package com.k4811.launcher.wrx;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * Íconos de línea de la pantalla WRX, dibujados en una grilla de 24×24 con el mismo grosor,
 * para que el dock y las tarjetas se vean parejos (sin imágenes).
 */
public class Glyph extends View {

    public enum Kind {
        HOME, RADIO, MUSIC, BLUETOOTH, PHONE, CAM_FRONT, CAM_REAR, DASHCAM, CARPLAY, YOUTUBE, APPS, EQ,
        MAPS, VIDEO, CAM360, AUX, WRENCH, TRIP, SETTINGS, BATTERY, FAN, LIGHTS, PLAY, PAUSE, PREV, NEXT,
        POWER, PLUS, CHECK, TRASH, WARNING, HANDBRAKE, CAN, APP, CLOSE
    }

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF();
    private Kind kind = Kind.APP;
    private float weight = 1.8f;

    public Glyph(Context c) {
        super(c);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        fill.setStyle(Paint.Style.FILL);
        color(0xFFFFFFFF);
    }

    public Glyph set(Kind k) {
        kind = k;
        invalidate();
        return this;
    }

    public Kind kind() {
        return kind;
    }

    public Glyph color(int c) {
        stroke.setColor(c);
        fill.setColor(c);
        invalidate();
        return this;
    }

    public Glyph weight(float w) {
        weight = w;
        invalidate();
        return this;
    }

    @Override
    protected void onDraw(Canvas c) {
        float size = Math.min(getWidth(), getHeight());
        if (size <= 0) return;
        float s = size / 24f;
        c.save();
        c.translate((getWidth() - size) / 2f, (getHeight() - size) / 2f);
        c.scale(s, s);
        stroke.setStrokeWidth(weight);
        paintGlyph(c);
        c.restore();
    }

    private void line(Canvas c, float x1, float y1, float x2, float y2) {
        c.drawLine(x1, y1, x2, y2, stroke);
    }

    private void rrect(Canvas c, float l, float t, float rt, float b, float rad) {
        r.set(l, t, rt, b);
        c.drawRoundRect(r, rad, rad, stroke);
    }

    private void rrectFill(Canvas c, float l, float t, float rt, float b, float rad) {
        r.set(l, t, rt, b);
        c.drawRoundRect(r, rad, rad, fill);
    }

    private void circle(Canvas c, float x, float y, float rad) {
        c.drawCircle(x, y, rad, stroke);
    }

    private void poly(Canvas c, boolean close, boolean filled, float... p) {
        path.reset();
        path.moveTo(p[0], p[1]);
        for (int i = 2; i < p.length; i += 2) path.lineTo(p[i], p[i + 1]);
        if (close) path.close();
        c.drawPath(path, filled ? fill : stroke);
    }

    private void arc(Canvas c, float cx, float cy, float rad, float start, float sweep) {
        r.set(cx - rad, cy - rad, cx + rad, cy + rad);
        c.drawArc(r, start, sweep, false, stroke);
    }

    private void camera(Canvas c) {
        rrect(c, 3, 7, 15, 17, 2);
        poly(c, true, false, 15, 10.5f, 21, 7.5f, 21, 16.5f, 15, 13.5f);
    }

    private void paintGlyph(Canvas c) {
        switch (kind) {
            case HOME:
                poly(c, false, false, 3, 11, 12, 4, 21, 11);
                poly(c, false, false, 5, 9.5f, 5, 20, 10, 20, 10, 14, 14, 14, 14, 20, 19, 20, 19, 9.5f);
                break;
            case RADIO:
                rrect(c, 3, 8, 21, 20, 2);
                line(c, 7, 8, 17, 3);
                line(c, 7, 14, 11, 14);
                circle(c, 16, 14, 1.6f);
                break;
            case MUSIC:
                poly(c, false, false, 9, 18, 9, 5, 21, 3, 21, 16);
                circle(c, 6, 18, 3);
                circle(c, 18, 16, 3);
                break;
            case BLUETOOTH:
                poly(c, false, false, 7, 7, 17, 17, 12, 21, 12, 3, 17, 7, 7, 17);
                break;
            case PHONE:
                path.reset();
                path.moveTo(5, 3);
                path.lineTo(9, 3);
                path.lineTo(10.5f, 7.5f);
                path.lineTo(8, 9.5f);
                path.cubicTo(9.5f, 12.5f, 11.5f, 14.5f, 14.5f, 16);
                path.lineTo(16.5f, 13.5f);
                path.lineTo(21, 15);
                path.lineTo(21, 19);
                path.cubicTo(21, 20.2f, 20, 21, 19, 21);
                path.cubicTo(10.5f, 20.5f, 3.5f, 13.5f, 3, 5);
                path.cubicTo(3, 4, 3.8f, 3, 5, 3);
                c.drawPath(path, stroke);
                break;
            case CAM_FRONT:
                camera(c);
                poly(c, false, false, 7, 3.5f, 9, 1.8f, 11, 3.5f);
                break;
            case CAM_REAR:
                camera(c);
                poly(c, false, false, 7, 20.2f, 9, 22, 11, 20.2f);
                break;
            case DASHCAM:
                circle(c, 12, 12, 8);
                circle(c, 12, 12, 3);
                c.drawCircle(18.5f, 5.5f, 2f, fill);
                break;
            case CARPLAY:
                rrect(c, 6, 2, 18, 22, 2.5f);
                line(c, 11, 18.5f, 13, 18.5f);
                break;
            case YOUTUBE:
                rrect(c, 2, 5, 22, 19, 4.5f);
                poly(c, true, true, 10, 9, 15.5f, 12, 10, 15);
                break;
            case APPS:
                rrectFill(c, 4, 4, 10, 10, 1.6f);
                rrectFill(c, 14, 4, 20, 10, 1.6f);
                rrectFill(c, 4, 14, 10, 20, 1.6f);
                rrectFill(c, 14, 14, 20, 20, 1.6f);
                break;
            case EQ:
                line(c, 6, 4, 6, 20);
                line(c, 12, 4, 12, 20);
                line(c, 18, 4, 18, 20);
                c.drawCircle(6, 14, 2.4f, fill);
                c.drawCircle(12, 8, 2.4f, fill);
                c.drawCircle(18, 16, 2.4f, fill);
                break;
            case MAPS:
                poly(c, true, false, 3, 6, 9, 4, 15, 6, 21, 4, 21, 18, 15, 20, 9, 18, 3, 20);
                line(c, 9, 4, 9, 18);
                line(c, 15, 6, 15, 20);
                break;
            case VIDEO:
                rrect(c, 3, 5, 21, 19, 2.5f);
                poly(c, true, true, 10, 9, 15, 12, 10, 15);
                break;
            case CAM360:
                circle(c, 12, 12, 9);
                circle(c, 12, 12, 3);
                arc(c, 12, 12, 6, 200, 140);
                break;
            case AUX:
                rrect(c, 4, 9, 20, 15, 3);
                line(c, 12, 15, 12, 21);
                break;
            case WRENCH:
                path.reset();
                path.moveTo(14.7f, 6.3f);
                path.cubicTo(13.3f, 4.9f, 13.6f, 2.8f, 15.5f, 2.3f);
                path.lineTo(13, 4.8f);
                path.moveTo(14.7f, 6.3f);
                c.drawPath(path, stroke);
                circle(c, 16.5f, 7.5f, 4);
                line(c, 13.5f, 10.5f, 4.5f, 19.5f);
                c.drawCircle(4.8f, 19.2f, 1.6f, fill);
                break;
            case TRIP:
                line(c, 4, 20, 20, 20);
                poly(c, false, false, 5, 15, 9.5f, 9, 13.5f, 13, 19, 5);
                c.drawCircle(19, 5, 1.6f, fill);
                break;
            case SETTINGS:
                circle(c, 12, 12, 3.2f);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI / 4 * i;
                    line(c, 12 + (float) Math.cos(a) * 6.5f, 12 + (float) Math.sin(a) * 6.5f,
                        12 + (float) Math.cos(a) * 9.5f, 12 + (float) Math.sin(a) * 9.5f);
                }
                circle(c, 12, 12, 6.5f);
                break;
            case BATTERY:
                rrect(c, 3, 7, 21, 19, 2);
                line(c, 7, 7, 7, 4.5f);
                line(c, 17, 7, 17, 4.5f);
                line(c, 6.5f, 13, 10, 13);
                line(c, 14, 13, 17.5f, 13);
                line(c, 15.75f, 11.25f, 15.75f, 14.75f);
                break;
            case FAN:
                circle(c, 12, 12, 2);
                for (int i = 0; i < 4; i++) {
                    c.save();
                    c.rotate(90 * i, 12, 12);
                    path.reset();
                    path.moveTo(12, 10);
                    path.cubicTo(11, 6, 12, 3, 14.5f, 3.5f);
                    path.cubicTo(17, 4, 16, 8, 13.8f, 10.6f);
                    c.drawPath(path, stroke);
                    c.restore();
                }
                break;
            case LIGHTS:
                path.reset();
                path.moveTo(11, 5);
                path.cubicTo(6, 5, 4, 8.5f, 4, 12);
                path.cubicTo(4, 15.5f, 6, 19, 11, 19);
                path.close();
                c.drawPath(path, stroke);
                line(c, 15, 7.5f, 21, 6);
                line(c, 15, 12, 21, 12);
                line(c, 15, 16.5f, 21, 18);
                break;
            case PLAY:
                poly(c, true, true, 7, 4.5f, 19.5f, 12, 7, 19.5f);
                break;
            case PAUSE:
                rrectFill(c, 6.5f, 5, 10.5f, 19, 1);
                rrectFill(c, 13.5f, 5, 17.5f, 19, 1);
                break;
            case PREV:
                rrectFill(c, 5, 5, 7.5f, 19, 0.8f);
                poly(c, true, true, 20, 5, 20, 19, 9, 12);
                break;
            case NEXT:
                rrectFill(c, 16.5f, 5, 19, 19, 0.8f);
                poly(c, true, true, 4, 5, 4, 19, 15, 12);
                break;
            case POWER:
                arc(c, 12, 13, 8, -60, 300);
                line(c, 12, 3, 12, 12);
                break;
            case PLUS:
                line(c, 12, 5, 12, 19);
                line(c, 5, 12, 19, 12);
                break;
            case CHECK:
                poly(c, false, false, 5, 12.5f, 10, 17.5f, 19.5f, 7);
                break;
            case TRASH:
                line(c, 4, 6.5f, 20, 6.5f);
                poly(c, false, false, 9, 6.5f, 9.5f, 3.5f, 14.5f, 3.5f, 15, 6.5f);
                poly(c, false, false, 6, 6.5f, 7, 20.5f, 17, 20.5f, 18, 6.5f);
                line(c, 10, 10.5f, 10, 16.5f);
                line(c, 14, 10.5f, 14, 16.5f);
                break;
            case WARNING:
                poly(c, true, false, 12, 3, 22, 20.5f, 2, 20.5f);
                line(c, 12, 9.5f, 12, 14);
                c.drawCircle(12, 17.3f, 1.2f, fill);
                break;
            case HANDBRAKE:
                circle(c, 12, 12, 6.5f);
                arc(c, 12, 12, 10, 135, 90);
                arc(c, 12, 12, 10, -45, 90);
                path.reset();
                // "P"
                path.moveTo(10.2f, 15.5f);
                path.lineTo(10.2f, 8.5f);
                path.lineTo(12.6f, 8.5f);
                path.cubicTo(14.8f, 8.5f, 14.8f, 12.2f, 12.6f, 12.2f);
                path.lineTo(10.2f, 12.2f);
                c.drawPath(path, stroke);
                break;
            case CAN:
                rrect(c, 4, 4, 20, 20, 3);
                line(c, 8, 9, 16, 9);
                line(c, 8, 12, 13, 12);
                line(c, 8, 15, 15, 15);
                line(c, 1.5f, 9, 4, 9);
                line(c, 1.5f, 15, 4, 15);
                line(c, 20, 9, 22.5f, 9);
                line(c, 20, 15, 22.5f, 15);
                break;
            case CLOSE:
                line(c, 6, 6, 18, 18);
                line(c, 18, 6, 6, 18);
                break;
            case APP:
            default:
                rrect(c, 4, 4, 20, 20, 5);
                circle(c, 12, 12, 3);
                break;
        }
    }
}
