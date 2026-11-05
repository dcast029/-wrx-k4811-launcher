package com.k4811.launcher.wrx;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.Dialog;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.k4811.common.system.NwdSettings;
import com.k4811.common.ui.Ui;

import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Hub Unificado de Registro y Mediciones para el Subaru WRX.
 * Agrupa de forma rápida y accesible:
 * 1) Bencina & Rendimiento (Cargas de combustible, litros, km/L calculados e historial).
 * 2) Mantenciones (Aceite, filtros, bujías, frenos, con odómetro y alertas de km).
 * 3) Odómetro, Viaje y Modo Pista (Trip A, métricas y récords 0-100 / 0-60 km/h).
 */
public class WrxRegistryActivity extends Activity {

    private static final Locale ES = new Locale("es", "CL");

    private WrxTheme t;
    private int activeTab = 0; // 0 = Bencina, 1 = Mantenciones, 2 = Viaje & Pista
    private FrameLayout contentFrame;
    private TextView tabFuelBtn;
    private TextView tabMaintBtn;
    private TextView tabTripBtn;
    private TextView odoChip;
    private Dialog dialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Ui.applyDesignDensity(this);
        super.onCreate(savedInstanceState);
        WrxTheme.loadFonts(this);
        int mode = WrxPrefs.themeMode(this);
        t = new WrxTheme(mode == 2 || (mode == 0 && NwdSettings.headlightsOn(this)));

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

        TextView back = WrxDialog.button(this, t, "‹  Inicio", false);
        head.addView(back, new LinearLayout.LayoutParams(px(130), px(50)));
        back.setOnClickListener(v -> finish());

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1);
        tlp.leftMargin = px(20);
        head.addView(titles, tlp);

        titles.addView(t.label(this, "Registro y Mediciones WRX", 25, WrxTheme.bodyBold(), t.text));
        titles.addView(t.label(this, "Bitácora completa · Bencina, Mantenciones y Telemetría", 13, WrxTheme.bodyMedium(), t.gold));

        odoChip = WrxDialog.button(this, t, "", false);
        odoChip.setTypeface(WrxTheme.numMedium());
        head.addView(odoChip, new LinearLayout.LayoutParams(-2, px(50)));
        odoChip.setOnClickListener(v -> askOdometer());

        // Tabs
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams tblp = new LinearLayout.LayoutParams(-1, px(52));
        tblp.topMargin = px(12);
        root.addView(tabs, tblp);

        tabFuelBtn = createTabBtn("⛽  BENCINA & RENDIMIENTO", 0, tabs);
        tabMaintBtn = createTabBtn("🔧  MANTENCIONES", 1, tabs);
        tabTripBtn = createTabBtn("⏱️  VIAJE & PISTA", 2, tabs);

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
        updateTabStyles(tabFuelBtn, activeTab == 0);
        updateTabStyles(tabMaintBtn, activeTab == 1);
        updateTabStyles(tabTripBtn, activeTab == 2);

        renderCurrentTab();
    }

    private void updateTabStyles(TextView b, boolean active) {
        b.setBackground(active ? t.cardOf(this, t.gold, 16, true) : t.cardOf(this, t.panel2, 16, false));
        b.setTextColor(active ? t.onGold : t.muted);
    }

    private void renderCurrentTab() {
        contentFrame.removeAllViews();
        updateOdoChip();
        if (activeTab == 0) {
            contentFrame.addView(buildFuelView());
        } else if (activeTab == 1) {
            contentFrame.addView(buildMaintenanceView());
        } else {
            contentFrame.addView(buildTripView());
        }
    }

    private void updateOdoChip() {
        if (odoChip != null) {
            odoChip.setText(Double.isNaN(CarState.odometerKm) ? "Odómetro: ingresar"
                : "Odómetro: " + Maintenance.km(Math.round(CarState.odometerKm)) + " km");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Ui.hideSystemBars(getWindow());
        CarWatchService.reloadOdometer(this);
        updateOdoChip();
        renderCurrentTab();
    }

    @Override
    protected void onDestroy() {
        if (dialog != null) dialog.dismiss();
        super.onDestroy();
    }

    // ================================================================== 1. TAB BENCINA
    private View buildFuelView() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        WrxTheme.pad(list, 0, 8, 0, 24);
        scroll.addView(list);

        List<FuelTracker.FillUp> history = FuelTracker.loadHistory(this);
        float avgKmL = FuelTracker.getAverageKmL(this);
        FuelTracker.FillUp last = history.isEmpty() ? null : history.get(0);

        // Tarjeta resumen superior
        LinearLayout summaryCard = new LinearLayout(this);
        summaryCard.setOrientation(LinearLayout.HORIZONTAL);
        summaryCard.setGravity(Gravity.CENTER_VERTICAL);
        summaryCard.setBackground(t.cardOf(this, t.panel2, 20, true));
        WrxTheme.pad(summaryCard, 24, 18, 24, 18);

        LinearLayout stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.VERTICAL);

        stats.addView(t.label(this, "RENDIMIENTO PROMEDIO", 12.5f, WrxTheme.bodyBold(), t.gold));
        String sAvg = Float.isNaN(avgKmL) ? "--" : String.format(ES, "%.1f", avgKmL);
        TextView avgVal = t.label(this, sAvg + " km/L", 36, WrxTheme.numBold(), t.text);
        stats.addView(avgVal);

        String sub = last == null ? "Sin cargas registradas aún"
            : (Float.isNaN(last.kmL)
                ? String.format(ES, "Última carga: %.1f L en %.0f km (esperando 2° llenado)", last.liters, last.odo)
                : String.format(ES, "Última carga: %.1f km/L (%.1f L en %.0f km · %s)", last.kmL, last.liters, last.odo, last.dateStr));
        stats.addView(t.label(this, sub, 13.5f, WrxTheme.body(), t.muted));

        summaryCard.addView(stats, new LinearLayout.LayoutParams(0, -2, 1));

        // Botón grande "+ REGISTRAR CARGA"
        TextView addFillBtn = WrxDialog.button(this, t, "+ CARGAR BENCINA", true);
        addFillBtn.setTextSize(17);
        summaryCard.addView(addFillBtn, new LinearLayout.LayoutParams(px(220), px(60)));
        addFillBtn.setOnClickListener(v -> showAddFillUpDialog());

        list.addView(summaryCard, new LinearLayout.LayoutParams(-1, -2));

        // Historial de Cargas
        section(list, "HISTORIAL DE CARGAS REGISTRADAS (" + history.size() + ")");

        if (history.isEmpty()) {
            LinearLayout empty = new LinearLayout(this);
            empty.setOrientation(LinearLayout.VERTICAL);
            empty.setGravity(Gravity.CENTER);
            empty.setBackground(t.card(this, 18));
            WrxTheme.pad(empty, 20, 30, 20, 30);
            empty.addView(t.label(this, "Aún no has registrado cargas de combustible.", 16, WrxTheme.bodyMedium(), t.muted));
            empty.addView(t.label(this, "Toca '+ CARGAR BENCINA' en cada llenado para que calculemos tu consumo real en km/L.", 13, WrxTheme.body(), t.muted));
            list.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        } else {
            for (FuelTracker.FillUp item : history) {
                list.addView(createFillUpCard(item), new LinearLayout.LayoutParams(-1, -2));
            }
        }

        return scroll;
    }

    private View createFillUpCard(FuelTracker.FillUp item) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(t.card(this, 16));
        WrxTheme.pad(card, 20, 14, 20, 14);

        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = px(8);
        card.setLayoutParams(clp);

        // Columna Izquierda: Litros y Octanaje
        LinearLayout col1 = new LinearLayout(this);
        col1.setOrientation(LinearLayout.VERTICAL);
        col1.addView(t.label(this, String.format(ES, "%.1f Litros", item.liters), 20, WrxTheme.bodyBold(), t.text));
        col1.addView(t.label(this, item.octane + " Octanos" + (item.fullTank ? " · Tanque Lleno" : ""), 12.5f, WrxTheme.body(), t.gold));
        card.addView(col1, new LinearLayout.LayoutParams(px(180), -2));

        // Columna Central: Odómetro y Distancia
        LinearLayout col2 = new LinearLayout(this);
        col2.setOrientation(LinearLayout.VERTICAL);
        col2.addView(t.label(this, String.format(ES, "%.0f km", item.odo), 18, WrxTheme.numMedium(), t.text));
        String distStr = item.distanceKm > 0 ? String.format(ES, "Recorridos: %.0f km", item.distanceKm) : "Carga inicial de referencia";
        col2.addView(t.label(this, distStr, 12.5f, WrxTheme.body(), t.muted));
        card.addView(col2, new LinearLayout.LayoutParams(0, -2, 1));

        // Columna Derecha: Rendimiento km/L resultante
        LinearLayout col3 = new LinearLayout(this);
        col3.setOrientation(LinearLayout.VERTICAL);
        col3.setGravity(Gravity.END);

        if (!Float.isNaN(item.kmL)) {
            TextView kmLText = t.label(this, String.format(ES, "%.1f km/L", item.kmL), 22, WrxTheme.numBold(), t.gold);
            kmLText.setGravity(Gravity.END);
            col3.addView(kmLText);
            TextView dateText = t.label(this, item.dateStr + " " + item.timeStr, 12, WrxTheme.body(), t.muted);
            dateText.setGravity(Gravity.END);
            col3.addView(dateText);
        } else {
            TextView refText = t.label(this, "Ref. Inicial", 17, WrxTheme.bodyBold(), t.muted);
            refText.setGravity(Gravity.END);
            col3.addView(refText);
            TextView dateText = t.label(this, item.dateStr, 12, WrxTheme.body(), t.muted);
            dateText.setGravity(Gravity.END);
            col3.addView(dateText);
        }
        card.addView(col3, new LinearLayout.LayoutParams(px(160), -2));

        // Botón Borrar
        TextView delBtn = WrxDialog.button(this, t, "✕", false);
        delBtn.setTextSize(16);
        delBtn.setTextColor(WrxTheme.RED);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(px(46), px(46));
        dlp.leftMargin = px(12);
        card.addView(delBtn, dlp);
        delBtn.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                .setTitle("Eliminar Carga")
                .setMessage("¿Deseas eliminar este registro de " + item.liters + " L?")
                .setPositiveButton("Eliminar", (d, w) -> {
                    FuelTracker.deleteFillUp(this, item.id);
                    renderCurrentTab();
                })
                .setNegativeButton("Cancelar", null)
                .show();
        });

        return card;
    }

    private void showAddFillUpDialog() {
        Dialog d = new Dialog(this);
        LinearLayout box = WrxDialog.base(this, t, d, "Registrar Carga de Bencina", 620);

        // Sugerencias de litros rápidos
        HorizontalScrollView hscroll = new HorizontalScrollView(this);
        LinearLayout quickRow = new LinearLayout(this);
        quickRow.setOrientation(LinearLayout.HORIZONTAL);
        hscroll.addView(quickRow);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, px(50));
        hlp.topMargin = px(10);
        box.addView(hscroll, hlp);

        final EditText litersField = WrxDialog.field(this, t, "Litros cargados (ej: 45.0)", "", true);
        box.addView(litersField, fieldLp(10));

        float[] quickLiters = {10f, 20f, 30f, 40f, 50f, 60f};
        for (float ql : quickLiters) {
            String label = String.format(ES, "+%.0f L", ql);
            if (ql == 50f || ql == 60f) label = String.format(ES, "Lleno %.0f L", ql);
            TextView qBtn = WrxDialog.button(this, t, label, false);
            qBtn.setTextSize(14.5f);
            LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(-2, -1);
            qlp.rightMargin = px(8);
            quickRow.addView(qBtn, qlp);
            final float fl = ql;
            qBtn.setOnClickListener(v -> litersField.setText(String.format(ES, "%.1f", fl)));
        }

        // Odómetro actual
        double curOdo = CarState.odometerKm;
        String odoStr = Double.isNaN(curOdo) ? "" : String.format(ES, "%.0f", curOdo);
        final EditText odoField = WrxDialog.field(this, t, "Odómetro actual en km (ej: 145280)", odoStr, true);
        box.addView(odoField, fieldLp(10));

        // Fila Octanaje (95 vs 97)
        LinearLayout octRow = new LinearLayout(this);
        octRow.setOrientation(LinearLayout.HORIZONTAL);
        box.addView(octRow, fieldLp(10));

        final int[] selOctane = {97};
        final TextView oct95 = WrxDialog.button(this, t, "95 Octanos", false);
        final TextView oct97 = WrxDialog.button(this, t, "97 Octanos (WRX)", true);

        LinearLayout.LayoutParams oblp = new LinearLayout.LayoutParams(0, -1, 1);
        octRow.addView(oct95, oblp);
        LinearLayout.LayoutParams ob2lp = new LinearLayout.LayoutParams(0, -1, 1);
        ob2lp.leftMargin = px(10);
        octRow.addView(oct97, ob2lp);

        oct95.setOnClickListener(v -> {
            selOctane[0] = 95;
            oct95.setBackground(t.cardOf(this, t.gold, 16, true));
            oct95.setTextColor(t.onGold);
            oct97.setBackground(t.cardOf(this, t.panel2, 16, false));
            oct97.setTextColor(t.muted);
        });

        oct97.setOnClickListener(v -> {
            selOctane[0] = 97;
            oct97.setBackground(t.cardOf(this, t.gold, 16, true));
            oct97.setTextColor(t.onGold);
            oct95.setBackground(t.cardOf(this, t.panel2, 16, false));
            oct95.setTextColor(t.muted);
        });

        // Botones Guardar / Cancelar
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setGravity(Gravity.END);
        TextView cancel = WrxDialog.button(this, t, "Cancelar", false);
        TextView save = WrxDialog.button(this, t, "Guardar Carga", true);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(px(160), px(54));
        slp.leftMargin = px(12);
        btnRow.addView(cancel, slp);
        btnRow.addView(save, new LinearLayout.LayoutParams(slp));
        box.addView(btnRow, fieldLp(16));

        cancel.setOnClickListener(v -> d.dismiss());
        save.setOnClickListener(v -> {
            try {
                float liters = Float.parseFloat(litersField.getText().toString().trim().replace(',', '.'));
                if (liters <= 0) {
                    litersField.setError("Ingresa litros válidos");
                    return;
                }

                double odo = Double.NaN;
                String oText = odoField.getText().toString().trim().replace(".", "").replace(",", "");
                if (!oText.isEmpty()) {
                    odo = Double.parseDouble(oText);
                }
                if (Double.isNaN(odo)) {
                    odo = Double.isNaN(CarState.odometerKm) ? CarState.tripKm : CarState.odometerKm;
                }

                FuelTracker.FillUp f = FuelTracker.addFillUp(this, odo, liters, 0, selOctane[0], true);
                d.dismiss();

                if (f != null) {
                    if (!Float.isNaN(f.kmL)) {
                        new AlertDialog.Builder(this)
                            .setTitle("¡Carga Registrada!")
                            .setMessage(String.format(ES, "¡Rendimiento: %.1f km/L!\n\nRecorriste %.0f km con %.1f L de bencina.",
                                f.kmL, f.distanceKm, f.liters))
                            .setPositiveButton("Excelente", null)
                            .show();
                    } else {
                        Toast.makeText(this, "Carga de " + liters + " L guardada como referencia inicial.", Toast.LENGTH_LONG).show();
                    }
                }
                renderCurrentTab();
            } catch (Exception e) {
                Toast.makeText(this, "Revisa los valores ingresados", Toast.LENGTH_SHORT).show();
            }
        });

        d.show();
        dialog = d;
    }

    // ================================================================== 2. TAB MANTENCIONES
    private View buildMaintenanceView() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        WrxTheme.pad(list, 0, 8, 0, 24);
        scroll.addView(list);

        // Header de mantenciones con botón agregar
        LinearLayout headRow = new LinearLayout(this);
        headRow.setGravity(Gravity.CENTER_VERTICAL);
        headRow.addView(t.label(this, "MANTENCIONES DEL SUBARU WRX", 13, WrxTheme.bodyBold(), t.gold), new LinearLayout.LayoutParams(0, -2, 1));

        TextView addBtn = WrxDialog.button(this, t, "+ AGREGAR MANTENCIÓN", true);
        headRow.addView(addBtn, new LinearLayout.LayoutParams(px(220), px(48)));
        addBtn.setOnClickListener(v -> showEditMaintenanceDialog(null));

        list.addView(headRow);

        List<Maintenance.Item> items = Maintenance.load(this);
        if (items.isEmpty()) {
            LinearLayout empty = new LinearLayout(this);
            empty.setOrientation(LinearLayout.VERTICAL);
            empty.setGravity(Gravity.CENTER);
            empty.setBackground(t.card(this, 18));
            WrxTheme.pad(empty, 20, 30, 20, 30);
            empty.addView(t.label(this, "No tienes mantenciones registradas.", 16, WrxTheme.bodyMedium(), t.muted));
            empty.addView(t.label(this, "Toca '+ AGREGAR MANTENCIÓN' para programar cambio de aceite, filtros o bujías.", 13, WrxTheme.body(), t.muted));
            list.addView(empty, fieldLp(12));
        } else {
            for (Maintenance.Item it : items) {
                list.addView(createMaintenanceCard(it));
            }
        }

        return scroll;
    }

    private View createMaintenanceCard(Maintenance.Item it) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(t.card(this, 18));
        WrxTheme.pad(card, 20, 14, 20, 14);

        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = px(10);
        card.setLayoutParams(clp);

        // Fila 1: Título y Urgencia
        LinearLayout r1 = new LinearLayout(this);
        r1.setGravity(Gravity.CENTER_VERTICAL);
        r1.addView(t.label(this, it.title, 19, WrxTheme.bodyBold(), t.text), new LinearLayout.LayoutParams(0, -2, 1));

        int urg = it.urgency();
        String badgeText;
        int badgeColor;
        if (it.done) {
            badgeText = "✓ HECHO";
            badgeColor = 0xFF4CAF50;
        } else if (urg == 2) {
            badgeText = "VENCIDO";
            badgeColor = WrxTheme.RED;
        } else if (urg == 1) {
            badgeText = "PRONTO";
            badgeColor = t.gold;
        } else {
            badgeText = "AL DÍA";
            badgeColor = 0xFF4CAF50;
        }

        TextView badge = t.label(this, badgeText, 12, WrxTheme.bodyBold(), badgeColor);
        badge.setBackground(t.cardOf(this, t.panel2, 10, true));
        WrxTheme.pad(badge, 10, 5, 10, 5);
        r1.addView(badge);
        card.addView(r1);

        // Fila 2: Cuánto falta
        StringBuilder desc = new StringBuilder();
        int kl = it.kmLeft();
        if (kl != Integer.MAX_VALUE) {
            if (kl < 0) desc.append("Vencido hace ").append(Maintenance.km(-kl)).append(" km");
            else desc.append("Faltan ").append(Maintenance.km(kl)).append(" km");
        }
        int dl = it.daysLeft();
        if (dl != Integer.MAX_VALUE) {
            if (desc.length() > 0) desc.append(" · ");
            if (dl < 0) desc.append("Vencido hace ").append(-dl).append(" días");
            else if (dl == 0) desc.append("Vence hoy");
            else desc.append("Faltan ").append(dl).append(" días");
        }
        if (!it.note.isEmpty()) {
            if (desc.length() > 0) desc.append("\n");
            desc.append(it.note);
        }

        TextView descView = t.label(this, desc.toString(), 13.5f, WrxTheme.body(), t.muted);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, -2);
        dlp.topMargin = px(4);
        card.addView(descView, dlp);

        // Fila 3: Botones de Acción
        LinearLayout actRow = new LinearLayout(this);
        actRow.setGravity(Gravity.END);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, -2);
        alp.topMargin = px(8);
        card.addView(actRow, alp);

        if (!it.done) {
            TextView doneBtn = WrxDialog.button(this, t, "Marcar Hecho", true);
            doneBtn.setTextSize(14);
            actRow.addView(doneBtn, new LinearLayout.LayoutParams(px(140), px(44)));
            doneBtn.setOnClickListener(v -> {
                Maintenance.complete(this, it.id);
                Toast.makeText(this, "Mantención completada", Toast.LENGTH_SHORT).show();
                renderCurrentTab();
            });
        }

        TextView editBtn = WrxDialog.button(this, t, "Editar", false);
        editBtn.setTextSize(14);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(px(100), px(44));
        elp.leftMargin = px(8);
        actRow.addView(editBtn, elp);
        editBtn.setOnClickListener(v -> showEditMaintenanceDialog(it));

        TextView delBtn = WrxDialog.button(this, t, "✕", false);
        delBtn.setTextSize(14);
        delBtn.setTextColor(WrxTheme.RED);
        LinearLayout.LayoutParams dbtnLp = new LinearLayout.LayoutParams(px(46), px(44));
        dbtnLp.leftMargin = px(8);
        actRow.addView(delBtn, dbtnLp);
        delBtn.setOnClickListener(v -> {
            Maintenance.delete(this, it.id);
            renderCurrentTab();
        });

        return card;
    }

    private void showEditMaintenanceDialog(Maintenance.Item existing) {
        final Maintenance.Item it = existing != null ? existing : new Maintenance.Item();
        Dialog d = new Dialog(this);
        LinearLayout box = WrxDialog.base(this, t, d, existing != null ? "Editar Mantención" : "Nueva Mantención WRX", 620);

        final EditText title = WrxDialog.field(this, t, "Qué es (ej: Aceite motor)", it.title, false);
        box.addView(title, fieldLp(10));

        final EditText km = WrxDialog.field(this, t, "A qué km (ej: 150000)", it.dueKm > 0 ? String.valueOf(it.dueKm) : "", true);
        box.addView(km, fieldLp(10));

        final EditText rkm = WrxDialog.field(this, t, "Repetir cada … km (ej: 5000)", it.repeatKm > 0 ? String.valueOf(it.repeatKm) : "", true);
        box.addView(rkm, fieldLp(10));

        final EditText note = WrxDialog.field(this, t, "Nota opcional (ej: 5W-40 Motul 300V)", it.note, false);
        box.addView(note, fieldLp(10));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.END);
        TextView cancel = WrxDialog.button(this, t, "Cancelar", false);
        TextView save = WrxDialog.button(this, t, "Guardar", true);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(px(150), px(52));
        blp.leftMargin = px(12);
        buttons.addView(cancel, blp);
        buttons.addView(save, new LinearLayout.LayoutParams(blp));
        box.addView(buttons, fieldLp(16));

        cancel.setOnClickListener(v -> d.dismiss());
        save.setOnClickListener(v -> {
            String tt = title.getText().toString().trim();
            if (tt.isEmpty()) {
                title.setError("Ingresa un título");
                return;
            }
            it.title = tt;
            it.note = note.getText().toString().trim();
            try {
                it.dueKm = Integer.parseInt(km.getText().toString().trim().replace(".", ""));
            } catch (Exception ignored) {}
            try {
                it.repeatKm = Integer.parseInt(rkm.getText().toString().trim().replace(".", ""));
            } catch (Exception ignored) {}
            Maintenance.upsert(this, it);
            d.dismiss();
            renderCurrentTab();
        });

        d.show();
        dialog = d;
    }

    // ================================================================== 3. TAB VIAJE Y PISTA
    private View buildTripView() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        WrxTheme.pad(list, 0, 8, 0, 24);
        scroll.addView(list);

        // Tarjeta Trip A
        LinearLayout tripCard = new LinearLayout(this);
        tripCard.setOrientation(LinearLayout.VERTICAL);
        tripCard.setBackground(t.card(this, 20));
        WrxTheme.pad(tripCard, 24, 18, 24, 18);

        LinearLayout trHead = new LinearLayout(this);
        trHead.setGravity(Gravity.CENTER_VERTICAL);
        trHead.addView(t.label(this, "DATOS DE VIAJE (TRIP A)", 13, WrxTheme.bodyBold(), t.gold), new LinearLayout.LayoutParams(0, -2, 1));

        TextView resetTripBtn = WrxDialog.button(this, t, "Reiniciar Trip A", false);
        resetTripBtn.setTextSize(13);
        trHead.addView(resetTripBtn, new LinearLayout.LayoutParams(px(160), px(44)));
        resetTripBtn.setOnClickListener(v -> {
            CarState.tripKm = 0;
            CarState.tripMovingMs = 0;
            CarState.tripStart = System.currentTimeMillis();
            CarState.tripMaxKmh = 0;
            Toast.makeText(this, "Trip A reiniciado a 0 km", Toast.LENGTH_SHORT).show();
            renderCurrentTab();
        });

        tripCard.addView(trHead);

        // Grid métricas Trip A
        LinearLayout rowM = new LinearLayout(this);
        rowM.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rmlp = new LinearLayout.LayoutParams(-1, -2);
        rmlp.topMargin = px(12);
        tripCard.addView(rowM, rmlp);

        addMetricCol(rowM, String.format(ES, "%.1f km", CarState.tripKm), "Distancia Trip");
        long mins = CarState.tripMovingMs / 60000;
        addMetricCol(rowM, mins + " min", "Tiempo en marcha");
        float avg = CarState.tripAvgKmh();
        addMetricCol(rowM, Float.isNaN(avg) ? "--" : String.format(ES, "%.0f km/h", avg), "Velocidad media");
        addMetricCol(rowM, String.format(ES, "%.0f km/h", CarState.tripMaxKmh), "Velocidad máxima");

        list.addView(tripCard);

        // Tarjeta Modo Pista WRX
        LinearLayout sportCard = new LinearLayout(this);
        sportCard.setOrientation(LinearLayout.VERTICAL);
        sportCard.setBackground(t.cardOf(this, t.panel2, 20, true));
        WrxTheme.pad(sportCard, 24, 18, 24, 18);
        LinearLayout.LayoutParams sclp = new LinearLayout.LayoutParams(-1, -2);
        sclp.topMargin = px(12);
        sportCard.setLayoutParams(sclp);

        boolean sportActive = WrxPrefs.isSportMode(this);

        LinearLayout spHead = new LinearLayout(this);
        spHead.setGravity(Gravity.CENTER_VERTICAL);
        spHead.addView(t.label(this, "TELEMETRÍA MODO PISTA WRX (GPS)", 13, WrxTheme.bodyBold(), t.gold), new LinearLayout.LayoutParams(0, -2, 1));

        TextView toggleSport = WrxDialog.button(this, t, sportActive ? "● Pista ACTIVO" : "Pista APAGADO", sportActive);
        toggleSport.setTextSize(13);
        spHead.addView(toggleSport, new LinearLayout.LayoutParams(px(160), px(44)));
        toggleSport.setOnClickListener(v -> {
            WrxPrefs.setSportMode(this, !sportActive);
            Toast.makeText(this, !sportActive ? "Modo Pista ACTIVADO" : "Modo Pista apagado", Toast.LENGTH_SHORT).show();
            renderCurrentTab();
        });

        sportCard.addView(spHead);

        LinearLayout rowP = new LinearLayout(this);
        rowP.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rplp = new LinearLayout.LayoutParams(-1, -2);
        rplp.topMargin = px(12);
        sportCard.addView(rowP, rplp);

        float b100 = WrxPrefs.getBest0to100(this);
        float b60 = WrxPrefs.getBest0to60(this);
        addMetricCol(rowP, b100 > 0 ? String.format(ES, "%.2f s", b100) : "--", "🏆 Récord 0-100");
        addMetricCol(rowP, b60 > 0 ? String.format(ES, "%.2f s", b60) : "--", "🏆 Récord 0-60");
        addMetricCol(rowP, CarState.last0to100Sec > 0 ? String.format(ES, "%.2f s", CarState.last0to100Sec) : "--", "Último 0-100");
        addMetricCol(rowP, CarState.last0to60Sec > 0 ? String.format(ES, "%.2f s", CarState.last0to60Sec) : "--", "Último 0-60");

        list.addView(sportCard);

        return scroll;
    }

    private void addMetricCol(LinearLayout parent, String val, String title) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(t.label(this, val, 24, WrxTheme.numBold(), t.text));
        col.addView(t.label(this, title, 12.5f, WrxTheme.body(), t.muted));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        parent.addView(col, lp);
    }

    private void section(LinearLayout list, String text) {
        TextView h = t.label(this, text, 12.5f, WrxTheme.bodyBold(), t.gold);
        WrxTheme.spacing(h, 0.14f);
        WrxTheme.pad(h, 4, 18, 0, 6);
        list.addView(h);
    }

    private LinearLayout.LayoutParams fieldLp(float top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, px(54));
        lp.topMargin = px(top);
        return lp;
    }

    private void askOdometer() {
        double cur = CarState.odometerKm;
        String val = Double.isNaN(cur) ? "" : String.valueOf(Math.round(cur));
        dialog = WrxDialog.input(this, t, "Ajustar Odómetro Total",
            "Escribe los km actuales de tu tablero (ej: 145280):", val, true, text -> {
                try {
                    int k = Integer.parseInt(text.trim().replace(".", ""));
                    if (k > 0) {
                        CarWatchService.setOdometer(this, k);
                        updateOdoChip();
                        renderCurrentTab();
                    }
                } catch (Exception ignored) {
                }
            });
    }
}
