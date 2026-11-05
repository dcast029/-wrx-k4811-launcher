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
            bg = 0xFF0A0D12; bgTop = 0xFF141A24; panel = 0xFF121620; panel2 = 0xFF19202C;
            line = 0x24355580; text = 0xFFEEF1F6; muted = 0xFF8E9AAA; dim = 0xFF58667A;
            gold = 0xFF1E6BFF; gold2 = 0xFF60A5FA; onGold = 0xFFFFFFFF; // Azul Subaru Rally Oficial
        } else {
            bg = 0xFFE5EAF2; bgTop = 0xFFF5F8FC; panel = 0xFFFFFFFF; panel2 = 0xFFF0F4FA;
            line = 0x200D52D6; text = 0xFF121620; muted = 0xFF5A6678; dim = 0xFF95A2B5;
            gold = 0xFF0D52D6; gold2 = 0xFF1E60D5; onGold = 0xFFFFFFFF;
        }
    }

    /** Color dinámico: Azul Subaru Rally en reposo, Rojo STI en velocidad. */
    public int dynamicAccent(boolean moving) {
        return moving ? 0xFFEF4444 : gold;
    }

    public int dynamicAccentLight(boolean moving) {
        return moving ? 0xFFFF6B75 : gold2;
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
