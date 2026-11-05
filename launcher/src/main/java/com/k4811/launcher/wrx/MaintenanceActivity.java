package com.k4811.launcher.wrx;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.app.Dialog;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.k4811.common.system.NwdSettings;
import com.k4811.common.ui.Ui;

import java.util.Calendar;
import java.util.Date;
import java.util.List;

/**
 * Lista de mantenciones y trámites del auto: agregar, editar, marcar hecho (si se repite, crea la
 * siguiente) y borrar. Se abre desde la tarjeta "Mantención" del inicio o desde el dock.
 */
public class MaintenanceActivity extends Activity {

    private WrxTheme t;
    private LinearLayout list;
    private TextView odoChip;
    private Dialog dialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Ui.applyDesignDensity(this);
        super.onCreate(savedInstanceState);
        WrxTheme.loadFonts(this);
        int mode = WrxPrefs.themeMode(this);
        t = new WrxTheme(mode == 2 || (mode == 0 && NwdSettings.headlightsOn(this)));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(t.bg);
        WrxTheme.pad(root, 28, 18, 28, 0);
        setContentView(root);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(head, new LinearLayout.LayoutParams(-1, px(64)));
        TextView back = WrxDialog.button(this, t, "‹  Inicio", false);
        head.addView(back, new LinearLayout.LayoutParams(px(130), px(52)));
        back.setOnClickListener(v -> finish());
        TextView title = t.label(this, "Mantención del WRX", 28, WrxTheme.bodyBold(), t.text);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1);
        tlp.leftMargin = px(20);
        head.addView(title, tlp);
        odoChip = WrxDialog.button(this, t, "", false);
        odoChip.setTypeface(WrxTheme.numMedium());
        head.addView(odoChip, new LinearLayout.LayoutParams(-2, px(52)));
        odoChip.setOnClickListener(v -> askOdometer());
        TextView add = WrxDialog.button(this, t, "+  Agregar", true);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(px(170), px(52));
        alp.leftMargin = px(12);
        head.addView(add, alp);
        add.setOnClickListener(v -> edit(null));

        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        WrxTheme.pad(list, 0, 10, 0, 24);
        scroll.addView(list);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, 0, 1);
        slp.topMargin = px(10);
        root.addView(scroll, slp);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Ui.hideSystemBars(getWindow());
        CarWatchService.reloadOdometer(this);
        render();
    }

    @Override
    protected void onDestroy() {
        if (dialog != null) dialog.dismiss();
        super.onDestroy();
    }

    private int px(float v) {
        return WrxTheme.px(this, v);
    }

    private void render() {
        odoChip.setText(Double.isNaN(CarState.odometerKm) ? "Odómetro: ingresar"
            : "Odómetro: " + Maintenance.km(Math.round(CarState.odometerKm)) + " km");
        list.removeAllViews();
        List<Maintenance.Item> pending = Maintenance.pending(this);
        if (pending.isEmpty()) {
            TextView e = t.label(this, "No hay nada pendiente. Agrega la revisión técnica, el permiso de circulación, "
                + "el SOAP o el cambio de aceite y te aviso cuando se acerque.", 18, WrxTheme.body(), t.muted);
            e.setSingleLine(false);
            WrxTheme.pad(e, 4, 30, 4, 0);
            list.addView(e);
        }
        for (Maintenance.Item it : pending) list.addView(row(it), rowLp());

        int done = 0;
        List<Maintenance.Item> all = Maintenance.load(this);
        for (int i = all.size() - 1; i >= 0 && done < 6; i--) {
            Maintenance.Item it = all.get(i);
            if (!it.done) continue;
            if (done == 0) {
                TextView h = t.label(this, "HECHOS RECIENTEMENTE", 12, WrxTheme.bodyBold(), t.gold);
                WrxTheme.spacing(h, 0.15f);
                WrxTheme.pad(h, 4, 22, 0, 4);
                list.addView(h);
            }
            list.addView(row(it), rowLp());
            done++;
        }
    }

    private LinearLayout.LayoutParams rowLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, px(88));
        lp.topMargin = px(10);
        return lp;
    }

    private View row(Maintenance.Item it) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackground(t.card(this, 20));
        WrxTheme.pad(r, 22, 0, 14, 0);
        View dot = new View(this);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        int u = it.urgency();
        d.setColor(it.done ? t.dim : u == 2 ? WrxTheme.RED : u == 1 ? WrxTheme.WARN : WrxTheme.OK);
        dot.setBackground(d);
        r.addView(dot, new LinearLayout.LayoutParams(px(12), px(12)));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1);
        tlp.leftMargin = px(18);
        r.addView(texts, tlp);
        texts.addView(t.label(this, it.title, 20, WrxTheme.bodyBold(), it.done ? t.muted : t.text));
        String sub = it.done ? "Hecho el " + Maintenance.SHOW.format(new Date(it.doneAt)) : it.status();
        if (!it.note.isEmpty()) sub += "  ·  " + it.note;
        TextView st = t.label(this, sub, 14.5f, WrxTheme.body(),
            !it.done && u == 2 ? WrxTheme.RED : !it.done && u == 1 ? (t.night ? WrxTheme.WARN : 0xFFB0781A) : t.muted);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.topMargin = px(5);
        texts.addView(st, slp);
        if (!it.done) {
            TextView ok = WrxDialog.button(this, t, "✓  Hecho", false);
            ok.setTextColor(t.gold2);
            LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(px(140), px(56));
            r.addView(ok, olp);
            ok.setOnClickListener(v -> {
                Maintenance.complete(this, it.id);
                render();
            });
            r.setOnClickListener(v -> edit(it));
        }
        FrameLayout del = new FrameLayout(this);
        del.setBackground(t.cardOf(this, 0x00000000, 16, true));
        del.addView(new Glyph(this).set(Glyph.Kind.TRASH).color(t.muted),
            new FrameLayout.LayoutParams(px(24), px(24), Gravity.CENTER));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(px(56), px(56));
        dlp.leftMargin = px(8);
        r.addView(del, dlp);
        del.setOnClickListener(v -> dialog = WrxDialog.list(this, t, "¿Borrar «" + it.title + "»?",
            new String[]{"Sí, borrar"}, i -> {
                Maintenance.delete(this, it.id);
                render();
            }));
        return r;
    }

    private void askOdometer() {
        String cur = Double.isNaN(CarState.odometerKm) ? "" : String.valueOf(Math.round(CarState.odometerKm));
        dialog = WrxDialog.input(this, t, "Kilometraje del tablero (después se suma solo con el GPS)",
            "Ej: 92345", cur, true, s -> {
                try {
                    CarWatchService.setOdometer(this, Double.parseDouble(s));
                    render();
                } catch (Exception ignored) {
                }
            });
    }

    // ------------------------------------------------------------------ agregar / editar

    private void edit(Maintenance.Item existing) {
        final Maintenance.Item it = existing != null ? existing : new Maintenance.Item();
        if (existing == null) it.id = System.currentTimeMillis();
        final long[] date = {it.dueDate};

        Dialog d = new Dialog(this);
        LinearLayout box = WrxDialog.base(this, t, d, existing == null ? "Agregar mantención" : "Editar", 900);

        if (existing == null) {
            HorizontalScrollView hs = new HorizontalScrollView(this);
            hs.setHorizontalScrollBarEnabled(false);
            LinearLayout chips = new LinearLayout(this);
            hs.addView(chips);
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, px(48));
            hlp.topMargin = px(16);
            box.addView(hs, hlp);
            chipsHolder = chips;
        }

        final EditText title = WrxDialog.field(this, t, "¿Qué es? (ej: Revisión técnica)", it.title, false);
        box.addView(title, fieldLp(16));

        LinearLayout r1 = new LinearLayout(this);
        box.addView(r1, fieldLp(12));
        final TextView dateBtn = WrxDialog.button(this, t, "", false);
        dateBtn.setGravity(Gravity.CENTER_VERTICAL);
        WrxTheme.pad(dateBtn, 18, 0, 18, 0);
        Runnable showDate = () -> dateBtn.setText(date[0] == 0 ? "Fecha: sin fecha (toca para elegir)"
            : "Vence el " + Maintenance.SHOW.format(new Date(date[0])));
        showDate.run();
        r1.addView(dateBtn, new LinearLayout.LayoutParams(0, -1, 1));
        final EditText km = WrxDialog.field(this, t, "o a los km (opcional)", it.dueKm > 0 ? String.valueOf(it.dueKm) : "", true);
        LinearLayout.LayoutParams kmlp = new LinearLayout.LayoutParams(px(300), -1);
        kmlp.leftMargin = px(12);
        r1.addView(km, kmlp);
        dateBtn.setOnClickListener(v -> {
            Calendar c = Calendar.getInstance();
            if (date[0] != 0) c.setTimeInMillis(date[0]);
            DatePickerDialog p = new DatePickerDialog(this, (view, y, m, day) -> {
                Calendar sel = Calendar.getInstance();
                sel.set(y, m, day, 0, 0, 0);
                sel.set(Calendar.MILLISECOND, 0);
                date[0] = sel.getTimeInMillis();
                showDate.run();
            }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH));
            p.setButton(DatePickerDialog.BUTTON_NEUTRAL, "Sin fecha", (dlg, w) -> {
                date[0] = 0;
                showDate.run();
            });
            p.show();
        });

        LinearLayout r2 = new LinearLayout(this);
        box.addView(r2, fieldLp(12));
        final EditText months = WrxDialog.field(this, t, "Repetir cada … meses", it.repeatMonths > 0 ? String.valueOf(it.repeatMonths) : "", true);
        final EditText rkm = WrxDialog.field(this, t, "Repetir cada … km", it.repeatKm > 0 ? String.valueOf(it.repeatKm) : "", true);
        r2.addView(months, new LinearLayout.LayoutParams(0, -1, 1));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, -1, 1);
        rlp.leftMargin = px(12);
        r2.addView(rkm, rlp);

        final EditText note = WrxDialog.field(this, t, "Nota (opcional: taller, aceite 5W-30…)", it.note, false);
        box.addView(note, fieldLp(12));

        if (chipsHolder != null) {
            for (Object[] p : Maintenance.PRESETS) {
                TextView chip = WrxDialog.button(this, t, (String) p[0], false);
                chip.setTextSize(15);
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -1);
                clp.rightMargin = px(8);
                chipsHolder.addView(chip, clp);
                chip.setOnClickListener(v -> {
                    title.setText((String) p[0]);
                    int pm = (Integer) p[1], pk = (Integer) p[2];
                    months.setText(pm > 0 ? String.valueOf(pm) : "");
                    rkm.setText(pk > 0 ? String.valueOf(pk) : "");
                    if (pk > 0 && !Double.isNaN(CarState.odometerKm) && km.getText().length() == 0) {
                        km.setText(String.valueOf(Math.round(CarState.odometerKm) + pk));
                    }
                    if (pm > 0 && date[0] == 0 && pk == 0) {
                        Calendar c = Calendar.getInstance();
                        c.add(Calendar.MONTH, 1);
                        c.set(Calendar.HOUR_OF_DAY, 0);
                        c.set(Calendar.MINUTE, 0);
                        c.set(Calendar.SECOND, 0);
                        c.set(Calendar.MILLISECOND, 0);
                        date[0] = c.getTimeInMillis();
                        showDate.run();
                    }
                });
            }
            chipsHolder = null;
        }

        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.END);
        TextView cancel = WrxDialog.button(this, t, "Cancelar", false);
        TextView save = WrxDialog.button(this, t, "Guardar", true);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(px(160), px(58));
        blp.leftMargin = px(12);
        buttons.addView(cancel, blp);
        buttons.addView(save, new LinearLayout.LayoutParams(blp));
        box.addView(buttons, fieldLp(18));
        cancel.setOnClickListener(v -> d.dismiss());
        save.setOnClickListener(v -> {
            String tt = title.getText().toString().trim();
            if (tt.isEmpty()) {
                title.setError("Escribe qué es");
                return;
            }
            it.title = tt;
            it.note = note.getText().toString().trim();
            it.dueDate = date[0];
            it.dueKm = num(km);
            it.repeatMonths = num(months);
            it.repeatKm = num(rkm);
            Maintenance.upsert(this, it);
            d.dismiss();
            render();
        });
        d.show();
        dialog = d;
    }

    private LinearLayout chipsHolder;

    private LinearLayout.LayoutParams fieldLp(float top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, px(58));
        lp.topMargin = px(top);
        return lp;
    }

    private static int num(EditText e) {
        try {
            return Integer.parseInt(e.getText().toString().trim().replace(".", ""));
        } catch (Exception ex) {
            return 0;
        }
    }
}
