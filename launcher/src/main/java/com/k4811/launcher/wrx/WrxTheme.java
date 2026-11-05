package com.k4811.launcher.wrx;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Colores y fuentes de la pantalla WRX. Un solo acento (dorado, como las llantas) y dos modos:
 * noche (faros encendidos) y día. Las medidas están en unidades de diseño 1280×720
 * (Ui.applyDesignDensity hace 1 dp = 1 unidad).
 */
public final class WrxTheme {

    public final boolean night;
    public final int bg, bgTop, panel, panel2, line, text, muted, dim, gold, gold2, onGold;
    public static final int OK = 0xFF5ED39A;
    public static final int COOL = 0xFF7CC4FF;
    public static final int WARN = 0xFFF2B84B;
    public static final int RED = 0xFFEF5350;

    private static Typeface exoLight, exo, exoMedium, exoBold, inter, interMedium, interBold;

    public WrxTheme(boolean night) {
        this.night = night;
        if (night) {
            bg = 0xFF07090C; bgTop = 0xFF151B23; panel = 0xFF11151B; panel2 = 0xFF181D25;
            line = 0x14FFFFFF; text = 0xFFEEF1F4; muted = 0xFF8A94A3; dim = 0xFF56606D;
            gold = 0xFFD6A94A; gold2 = 0xFFF0CD7A; onGold = 0xFF111111;
        } else {
            bg = 0xFFE4E8ED; bgTop = 0xFFF7F8FA; panel = 0xFFFFFFFF; panel2 = 0xFFF2F4F7;
            line = 0x16000000; text = 0xFF14181D; muted = 0xFF5D6774; dim = 0xFF9AA3AE;
            gold = 0xFFC9962F; gold2 = 0xFFAE7F1E; onGold = 0xFF111111;
        }
    }

    // ------------------------------------------------------------------ fuentes (vienen en el common)

    public static void loadFonts(Context c) {
        if (exo != null) return;
        exoLight = font(c, "fonts/Exo2-400.ttf");
        exo = exoLight;
        exoMedium = font(c, "fonts/Exo2-500.ttf");
        exoBold = font(c, "fonts/Exo2-600.ttf");
        inter = font(c, "fonts/Inter-400.ttf");
        interMedium = font(c, "fonts/Inter-500.ttf");
        interBold = font(c, "fonts/Inter-600.ttf");
    }

    private static Typeface font(Context c, String path) {
        try {
            return Typeface.createFromAsset(c.getAssets(), path);
        } catch (Exception e) {
            return Typeface.DEFAULT;
        }
    }

    public static Typeface num() { return exo; }
    public static Typeface numMedium() { return exoMedium; }
    public static Typeface numBold() { return exoBold; }
    public static Typeface body() { return inter; }
    public static Typeface bodyMedium() { return interMedium; }
    public static Typeface bodyBold() { return interBold; }

    // ------------------------------------------------------------------ helpers de vistas

    public static float dp(Context c, float v) {
        return v * c.getResources().getDisplayMetrics().density;
    }

    public static int px(Context c, float v) {
        return Math.round(dp(c, v));
    }

    /** Fondo de tarjeta: relleno, borde fino y esquinas redondeadas; más claro al presionar. */
    public android.graphics.drawable.Drawable card(Context c, float radius) {
        return cardOf(c, panel, radius, true);
    }

    public android.graphics.drawable.Drawable cardOf(Context c, int fill, float radius, boolean pressable) {
        GradientDrawable normal = shape(c, fill, radius, line);
        if (!pressable) return normal;
        GradientDrawable pressed = shape(c, night ? 0xFF1E242D : 0xFFE9ECF0, radius, gold);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, pressed);
        s.addState(new int[]{}, normal);
        return s;
    }

    public GradientDrawable shape(Context c, int fill, float radius, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, radius));
        if (stroke != 0) g.setStroke(Math.max(1, px(c, 1)), stroke);
        return g;
    }

    public TextView label(Context c, String s, float size, Typeface tf, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size);
        t.setTypeface(tf);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return t;
    }

    /** LayoutParams absolutos en unidades de diseño (x, y desde arriba a la izquierda). */
    public static FrameLayout.LayoutParams at(Context c, float x, float y, float w, float h) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            w < 0 ? FrameLayout.LayoutParams.WRAP_CONTENT : px(c, w),
            h < 0 ? FrameLayout.LayoutParams.WRAP_CONTENT : px(c, h));
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.leftMargin = px(c, x);
        lp.topMargin = px(c, y);
        return lp;
    }

    /** Espaciado entre letras (API 21+; en versiones viejas se ignora). */
    public static void spacing(TextView v, float em) {
        if (android.os.Build.VERSION.SDK_INT >= 21) v.setLetterSpacing(em);
    }

    public static void pad(View v, float l, float t, float r, float b) {
        Context c = v.getContext();
        v.setPadding(px(c, l), px(c, t), px(c, r), px(c, b));
    }
}
