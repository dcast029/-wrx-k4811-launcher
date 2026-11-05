package com.k4811.launcher.wrx;

import android.app.Activity;
import android.app.Dialog;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/** Diálogos con el estilo WRX (tarjeta oscura/clara, botones grandes para usar manejando). */
public final class WrxDialog {
    private WrxDialog() {
    }

    public interface OnPick {
        void pick(int index);
    }

    public interface OnText {
        void text(String value);
    }

    public interface OnId {
        void id(String value);
    }

    private static int px(Activity a, float v) {
        return WrxTheme.px(a, v);
    }

    /** Ventana base: devuelve el contenedor vertical donde va el contenido. */
    static LinearLayout base(Activity a, WrxTheme t, Dialog d, String title, float width) {
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(t.shape(a, t.panel, 26, t.line));
        WrxTheme.pad(box, 28, 24, 28, 22);
        TextView h = t.label(a, title, 24, WrxTheme.bodyBold(), t.text);
        h.setSingleLine(false);
        box.addView(h, new LinearLayout.LayoutParams(-1, -2));
        FrameLayout wrap = new FrameLayout(a);
        wrap.addView(box, new FrameLayout.LayoutParams(px(a, width), -2, Gravity.CENTER));
        d.setContentView(wrap);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.6f);
        }
        return box;
    }

    static TextView button(Activity a, WrxTheme t, String text, boolean primary) {
        TextView b = t.label(a, text, 18, WrxTheme.bodyBold(), primary ? t.onGold : t.text);
        b.setGravity(Gravity.CENTER);
        b.setBackground(primary ? t.cardOf(a, t.gold, 16, true) : t.cardOf(a, t.panel2, 16, true));
        WrxTheme.pad(b, 18, 0, 18, 0);
        return b;
    }

    /** Lista de opciones grandes. */
    public static Dialog list(Activity a, WrxTheme t, String title, String[] options, OnPick cb) {
        Dialog d = new Dialog(a);
        LinearLayout box = base(a, t, d, title, 560);
        for (int i = 0; i < options.length; i++) {
            final int idx = i;
            TextView row = button(a, t, options[i], false);
            row.setGravity(Gravity.CENTER_VERTICAL);
            WrxTheme.pad(row, 22, 0, 22, 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, px(a, 64));
            lp.topMargin = px(a, i == 0 ? 18 : 10);
            box.addView(row, lp);
            row.setOnClickListener(v -> {
                d.dismiss();
                cb.pick(idx);
            });
        }
        addCancel(a, t, box, d);
        d.show();
        return d;
    }

    private static void addCancel(Activity a, WrxTheme t, LinearLayout box, Dialog d) {
        TextView cancel = t.label(a, "Cancelar", 17, WrxTheme.bodyMedium(), t.muted);
        cancel.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, px(a, 52));
        lp.topMargin = px(a, 8);
        box.addView(cancel, lp);
        cancel.setOnClickListener(v -> d.dismiss());
    }

    /** Ingresar un texto o número. */
    public static Dialog input(Activity a, WrxTheme t, String title, String hint, String initial,
                               boolean numeric, OnText cb) {
        Dialog d = new Dialog(a);
        LinearLayout box = base(a, t, d, title, 560);
        EditText e = field(a, t, hint, initial, numeric);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, px(a, 64));
        lp.topMargin = px(a, 18);
        box.addView(e, lp);
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.END);
        TextView cancel = button(a, t, "Cancelar", false);
        TextView ok = button(a, t, "Guardar", true);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(px(a, 150), px(a, 56));
        blp.leftMargin = px(a, 12);
        row.addView(cancel, blp);
        row.addView(ok, new LinearLayout.LayoutParams(blp));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = px(a, 18);
        box.addView(row, rlp);
        cancel.setOnClickListener(v -> d.dismiss());
        ok.setOnClickListener(v -> {
            d.dismiss();
            cb.text(e.getText().toString().trim());
        });
        if (d.getWindow() != null) {
            d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        d.show();
        e.requestFocus();
        return d;
    }

    static EditText field(Activity a, WrxTheme t, String hint, String initial, boolean numeric) {
        EditText e = new EditText(a);
        e.setHint(hint);
        e.setText(initial == null ? "" : initial);
        e.setSelection(e.getText().length());
        e.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        e.setTypeface(WrxTheme.body());
        e.setTextColor(t.text);
        e.setHintTextColor(t.dim);
        e.setSingleLine(true);
        e.setBackground(t.shape(a, t.panel2, 14, t.line));
        WrxTheme.pad(e, 18, 0, 18, 0);
        if (numeric) e.setInputType(InputType.TYPE_CLASS_NUMBER);
        else e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        return e;
    }

    /** Elegir qué va en un botón del dock: funciones de la radio o cualquier app instalada. */
    public static Dialog pickDockItem(Activity a, WrxTheme t, OnId cb) {
        Dialog d = new Dialog(a);
        LinearLayout box = base(a, t, d, "¿Qué quieres en este botón?", 1040);
        ScrollView scroll = new ScrollView(a);
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, px(a, 470));
        slp.topMargin = px(a, 14);
        box.addView(scroll, slp);

        list.addView(section(a, t, "FUNCIONES"));
        LinearLayout grid = null;
        int col = 0;
        for (Object[] b : DockSlots.BUILTIN) {
            if (col % 5 == 0) {
                grid = new LinearLayout(a);
                list.addView(grid, new LinearLayout.LayoutParams(-1, px(a, 96)));
            }
            String id = (String) b[0];
            View cell = cell(a, t, (String) b[1], (Glyph.Kind) b[2], null);
            cell.setOnClickListener(v -> {
                d.dismiss();
                cb.id(id);
            });
            grid.addView(cell, new LinearLayout.LayoutParams(0, -1, 1));
            col++;
        }
        while (col % 5 != 0) {
            grid.addView(new View(a), new LinearLayout.LayoutParams(0, -1, 1));
            col++;
        }

        list.addView(section(a, t, "APPS INSTALADAS"));
        List<ResolveInfo> apps = DockSlots.launchable(a);
        col = 0;
        for (ResolveInfo r : apps) {
            if (col % 5 == 0) {
                grid = new LinearLayout(a);
                list.addView(grid, new LinearLayout.LayoutParams(-1, px(a, 96)));
            }
            String pkg = r.activityInfo.packageName;
            View cell = cell(a, t, r.loadLabel(a.getPackageManager()).toString(), null, r);
            cell.setOnClickListener(v -> {
                d.dismiss();
                cb.id("app:" + pkg);
            });
            grid.addView(cell, new LinearLayout.LayoutParams(0, -1, 1));
            col++;
        }
        while (col % 5 != 0) {
            grid.addView(new View(a), new LinearLayout.LayoutParams(0, -1, 1));
            col++;
        }
        addCancel(a, t, box, d);
        d.show();
        return d;
    }

    private static TextView section(Activity a, WrxTheme t, String s) {
        TextView v = t.label(a, s, 12, WrxTheme.bodyBold(), t.gold);
        WrxTheme.spacing(v, 0.15f);
        WrxTheme.pad(v, 6, 14, 0, 8);
        return v;
    }

    private static View cell(Activity a, WrxTheme t, String label, Glyph.Kind kind, ResolveInfo app) {
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER);
        c.setBackground(t.cardOf(a, t.panel, 14, true));
        View icon;
        if (app != null) {
            ImageView iv = new ImageView(a);
            iv.setImageDrawable(app.loadIcon(a.getPackageManager()));
            icon = iv;
        } else {
            icon = new Glyph(a).set(kind).color(t.text);
        }
        c.addView(icon, new LinearLayout.LayoutParams(px(a, 40), px(a, 40)));
        TextView l = t.label(a, label, 12, WrxTheme.bodyMedium(), t.muted);
        l.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = px(a, 6);
        c.addView(l, lp);
        WrxTheme.pad(c, 4, 6, 4, 6);
        return c;
    }
}
