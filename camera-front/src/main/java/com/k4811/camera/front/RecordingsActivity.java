package com.k4811.camera.front;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.location.Address;
import android.location.Geocoder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.k4811.common.system.ExternalApps;
import com.k4811.common.ui.Ui;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Grabaciones del auto, ordenadas por día y hora, con el lugar (GPS) donde se grabó:
 * cámara trasera (Streaming HD2, en el pendrive), golpes (sensorlock), dashcam y las protegidas.
 * Avisa si el pendrive no está grabando o se está llenando, y permite liberar espacio, reparar el
 * pendrive o ir a formatearlo. No abre cámaras en vivo.
 */
public class RecordingsActivity extends Activity {

    private static final int BG = 0xFF090C10, PANEL = 0xFF12161C, PANEL2 = 0xFF1A2028, LINE = 0x16FFFFFF,
        TEXT = 0xFFEEF1F4, MUTED = 0xFF8A94A3, DIM = 0xFF56606D, GOLD = 0xFFD6A94A, OK = 0xFF5ED39A,
        WARN = 0xFFF2B84B, RED = 0xFFEF5350, BLUE = 0xFF7CC4FF;
    private static final Locale ES = new Locale("es", "CL");
    private static final String[] TABS = {"Todas", Recordings.REAR, Recordings.DASHCAM, Recordings.LOCKED, Recordings.PROTECTED};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService geo = Executors.newSingleThreadExecutor();
    private final Map<String, String> places = new HashMap<>();
    private final List<Object> rows = new ArrayList<>();
    private List<Recordings.Video> all = new ArrayList<>();
    private String tab = "Todas";

    private Typeface num, body, bold;
    private TextView rearStatus, spaceText, spaceWarn, countText;
    private View spaceBar;
    private FrameLayout spaceTrack;
    private LinearLayout tabsRow;
    private Adapter adapter;

    private final Runnable refreshStatus = new Runnable() {
        @Override
        public void run() {
            renderDisk();
            handler.postDelayed(this, 15000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Ui.applyDesignDensity(this);
        super.onCreate(savedInstanceState);
        num = font("fonts/Exo2-500.ttf");
        body = font("fonts/Inter-400.ttf");
        bold = font("fonts/Inter-600.ttf");

        LinearLayout root = new LinearLayout(this);
        root.setBackgroundColor(BG);
        pad(root, 24, 20, 24, 20);
        setContentView(root);

        // ---------------------------------------------------------- izquierda: estado
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        root.addView(left, new LinearLayout.LayoutParams(dp(372), -1));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = button("‹", false);
        back.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 30);
        head.addView(back, new LinearLayout.LayoutParams(dp(56), dp(52)));
        back.setOnClickListener(v -> finish());
        TextView title = text("Grabaciones", 28, bold, TEXT);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.leftMargin = dp(14);
        head.addView(title, tlp);
        left.addView(head);

        LinearLayout rear = card();
        left.addView(rear, top(16));
        rear.addView(caption("CÁMARA TRASERA · PENDRIVE UDISKH"));
        rearStatus = text("Buscando pendrive…", 19, bold, TEXT);
        rearStatus.setSingleLine(false);
        rear.addView(rearStatus, top(10));
        spaceTrack = new FrameLayout(this);
        spaceTrack.setBackground(shape(LINE, 4, 0));
        spaceBar = new View(this);
        spaceTrack.addView(spaceBar, new FrameLayout.LayoutParams(0, -1));
        rear.addView(spaceTrack, new LinearLayout.LayoutParams(-1, dp(8)) {{
            topMargin = dp(16);
        }});
        spaceText = text("", 14, body, MUTED);
        rear.addView(spaceText, top(8));
        spaceWarn = text("", 14, bold, RED);
        spaceWarn.setSingleLine(false);
        spaceWarn.setVisibility(View.GONE);
        rear.addView(spaceWarn, top(8));
        LinearLayout b1 = new LinearLayout(this);
        rear.addView(b1, new LinearLayout.LayoutParams(-1, dp(54)) {{
            topMargin = dp(16);
        }});
        TextView free = button("Liberar espacio", true);
        b1.addView(free, new LinearLayout.LayoutParams(0, -1, 1));
        free.setOnClickListener(v -> askFreeSpace());
        TextView repair = button("Reparar", false);
        b1.addView(repair, new LinearLayout.LayoutParams(dp(120), -1) {{
            leftMargin = dp(10);
        }});
        repair.setOnClickListener(v -> {
            ExternalApps.repairHd2DvrDisk(this);
            Toast.makeText(this, "Revisando y reparando el pendrive…", Toast.LENGTH_LONG).show();
            handler.postDelayed(this::reload, 4000);
        });
        TextView format = button("Formatear pendrive…", false);
        format.setTextColor(WARN);
        rear.addView(format, new LinearLayout.LayoutParams(-1, dp(50)) {{
            topMargin = dp(10);
        }});
        format.setOnClickListener(v -> askFormat());

        LinearLayout dash = card();
        left.addView(dash, top(14));
        dash.addView(caption("DASHCAM · DISCO UDISK"));
        TextView dt = text("Graba en su propia tarjeta SD (disco «udisk»). Sus videos se leen con su app; "
            + "los que bajes con ella aparecen aquí en «Dashcam».", 14, body, MUTED);
        dt.setSingleLine(false);
        dash.addView(dt, top(8));
        TextView importDash = button("📥 Traer videos de la Dashcam", true);
        dash.addView(importDash, new LinearLayout.LayoutParams(-1, dp(50)) {{
            topMargin = dp(12);
        }});
        importDash.setOnClickListener(v -> showDashcamImportDialog());

        TextView openDash = button("Abrir app de la dashcam", false);
        dash.addView(openDash, new LinearLayout.LayoutParams(-1, dp(44)) {{
            topMargin = dp(8);
        }});
        openDash.setOnClickListener(v -> {
            if (!ExternalApps.openTrafficGuard(this)) {
                Toast.makeText(this, "No encontré la app de la dashcam", Toast.LENGTH_LONG).show();
            }
        });

        // ---------------------------------------------------------- derecha: lista
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, -1, 1);
        rlp.leftMargin = dp(20);
        root.addView(right, rlp);
        LinearLayout tabsLine = new LinearLayout(this);
        tabsLine.setGravity(Gravity.CENTER_VERTICAL);
        right.addView(tabsLine, new LinearLayout.LayoutParams(-1, dp(52)));
        tabsRow = new LinearLayout(this);
        tabsLine.addView(tabsRow, new LinearLayout.LayoutParams(0, -1, 1));
        countText = text("", 14, body, MUTED);
        tabsLine.addView(countText);
        TextView reload = button("↻", false);
        reload.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 24);
        tabsLine.addView(reload, new LinearLayout.LayoutParams(dp(56), dp(48)) {{
            leftMargin = dp(10);
        }});
        reload.setOnClickListener(v -> reload());

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setSelector(new android.graphics.drawable.ColorDrawable(0));
        list.setBackground(shape(PANEL, 20, LINE));
        list.setClipToPadding(false);
        pad(list, 12, 8, 12, 8);
        adapter = new Adapter();
        list.setAdapter(adapter);
        right.addView(list, new LinearLayout.LayoutParams(-1, 0, 1) {{
            topMargin = dp(12);
        }});
        renderTabs();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Ui.hideSystemBars(getWindow());
        reload();
        handler.post(refreshStatus);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refreshStatus);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        geo.shutdownNow();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ dashcam usb

    private void showDashcamImportDialog() {
        if (!DashcamReader.isDashcamAvailable()) {
            Toast.makeText(this, "No se detectó el disco de la dashcam (udisk).\nVerifica que esté conectada al puerto USB.", Toast.LENGTH_LONG).show();
            return;
        }

        android.app.ProgressDialog loading = new android.app.ProgressDialog(this);
        loading.setMessage("Leyendo lista de videos de la dashcam…");
        loading.setCancelable(false);
        loading.show();

        worker.execute(() -> {
            final DashcamReader.ListResult res = DashcamReader.listFiles();
            handler.post(() -> {
                if (isFinishing()) return;
                loading.dismiss();
                if (res.items.isEmpty()) {
                    new AlertDialog.Builder(this)
                        .setTitle("Dashcam")
                        .setMessage("No se encontraron grabaciones.\n\nDiagnóstico del canal USB:\n" + res.debugInfo)
                        .setPositiveButton("Aceptar", null)
                        .setNeutralButton("Abrir App Dashcam", (d, which) -> ExternalApps.openTrafficGuard(this))
                        .show();
                    return;
                }

                List<DashcamReader.FileItem> list = res.items;

                String[] names = new String[list.size()];
                for (int i = 0; i < list.size(); i++) {
                    DashcamReader.FileItem item = list.get(i);
                    names[i] = (item.locked ? "🔴 [GOLPE] " : "📹 ") + item.formattedTime() + " (" + item.name + ")";
                }

                new AlertDialog.Builder(this)
                    .setTitle("Videos en la Dashcam (" + list.size() + ")")
                    .setItems(names, (d, which) -> {
                        downloadDashcamVideo(list.get(which));
                    })
                    .setNegativeButton("Cerrar", null)
                    .show();
            });
        });
    }

    private void downloadDashcamVideo(DashcamReader.FileItem item) {
        android.app.ProgressDialog progress = new android.app.ProgressDialog(this);
        progress.setTitle("Descargando de la Dashcam");
        progress.setMessage(item.name + "\nIniciando transferencia...");
        progress.setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL);
        progress.setMax(100);
        progress.setCancelable(false);
        progress.show();

        DashcamReader.download(item, new DashcamReader.DownloadListener() {
            @Override
            public void onProgress(int percent, long currentBytes, long totalBytes) {
                progress.setProgress(percent);
                progress.setMessage(String.format(ES, "%s\n%.1f / %.1f MB (%d%%)",
                    item.name, currentBytes / 1048576f, totalBytes / 1048576f, percent));
            }

            @Override
            public void onComplete(File file) {
                progress.dismiss();
                Toast.makeText(RecordingsActivity.this, "¡Video guardado en Grabaciones!", Toast.LENGTH_SHORT).show();
                tab = Recordings.DASHCAM;
                renderTabs();
                reload();
            }

            @Override
            public void onError(String message) {
                progress.dismiss();
                Toast.makeText(RecordingsActivity.this, "Error: " + message, Toast.LENGTH_LONG).show();
            }
        });
    }

    // ------------------------------------------------------------------ datos

    private void reload() {
        countText.setText("Buscando grabaciones…");
        Recordings.clearGpsCache();
        worker.execute(() -> {
            List<Recordings.Video> list = Recordings.scanAll();
            for (Recordings.Video v : list) Recordings.locate(v);
            handler.post(() -> {
                if (isFinishing()) return;
                all = list;
                renderList();
                renderDisk();
            });
        });
    }

    private void renderDisk() {
        worker.execute(() -> {
            Recordings.DiskInfo d = Recordings.disk();
            handler.post(() -> showDisk(d));
        });
    }

    private void showDisk(Recordings.DiskInfo d) {
        if (!d.present) {
            rearStatus.setText("○ Pendrive no conectado");
            rearStatus.setTextColor(RED);
            spaceText.setText("Streaming HD2 graba en el pendrive del USB «udiskh».");
            spaceBar.getLayoutParams().width = 0;
            spaceWarn.setVisibility(View.GONE);
            return;
        }
        long ago = System.currentTimeMillis() - d.lastRearMs;
        if (d.lastRearMs > 0 && ago < 3 * 60 * 1000) {
            rearStatus.setText("● Grabando ahora");
            rearStatus.setTextColor(OK);
        } else if (d.lastRearMs > 0) {
            rearStatus.setText("○ No está grabando · último video " + since(ago));
            rearStatus.setTextColor(WARN);
        } else {
            rearStatus.setText("○ Sin videos de la trasera todavía");
            rearStatus.setTextColor(WARN);
        }
        float used = d.total > 0 ? 1f - d.free / (float) d.total : 0f;
        int w = spaceTrack.getWidth();
        spaceBar.getLayoutParams().width = Math.round(w * used);
        boolean low = d.free < 1024L * 1024 * 1024 || used > 0.92f;
        spaceBar.setBackground(shape(low ? RED : used > 0.8f ? WARN : GOLD, 4, 0));
        spaceBar.requestLayout();
        spaceText.setText(gb(d.free) + " libres de " + gb(d.total));
        spaceWarn.setVisibility(low ? View.VISIBLE : View.GONE);
        spaceWarn.setText("Queda poco espacio: la cámara trasera puede dejar de grabar. Libera espacio o formatea.");
    }

    private void renderTabs() {
        tabsRow.removeAllViews();
        for (String t : TABS) {
            boolean on = t.equals(tab);
            TextView b = text(t, 16, bold, on ? 0xFF111111 : MUTED);
            b.setGravity(Gravity.CENTER);
            b.setBackground(on ? press(GOLD, 22) : press(PANEL2, 22));
            pad(b, 18, 0, 18, 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(46));
            lp.rightMargin = dp(8);
            tabsRow.addView(b, lp);
            b.setOnClickListener(v -> {
                tab = t;
                renderTabs();
                renderList();
            });
        }
    }

    private void renderList() {
        rows.clear();
        String lastDay = null;
        int n = 0;
        SimpleDateFormat dayKey = new SimpleDateFormat("yyyyMMdd", Locale.US);
        for (Recordings.Video v : all) {
            if (!"Todas".equals(tab) && !tab.equals(v.kind) && !(Recordings.DASHCAM.equals(tab) && Recordings.FRONT.equals(v.kind))) continue;
            String day = dayKey.format(new Date(v.startMs));
            if (!day.equals(lastDay)) {
                rows.add(dayLabel(v.startMs));
                lastDay = day;
            }
            rows.add(v);
            n++;
        }
        countText.setText(n == 0 ? "Sin grabaciones" : n + (n == 1 ? " video" : " videos"));
        if (n == 0) rows.add("Aquí aparecen los videos de la cámara trasera (pendrive), los golpes, la dashcam "
            + "y los que protejas.");
        adapter.notifyDataSetChanged();
    }

    // ------------------------------------------------------------------ acciones

    private void play(Recordings.Video v) {
        String name = v.file.getName().toLowerCase(Locale.US);
        String mime = name.endsWith(".ts") ? "video/mp2ts" : name.endsWith(".avi") ? "video/x-msvideo" : "video/mp4";
        Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(v.file), mime)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(v.file), "video/*")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e2) {
                Toast.makeText(this, "No hay reproductor para este video", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void map(Recordings.Video v) {
        if (Double.isNaN(v.lat)) {
            Toast.makeText(this, "Este video no tiene ubicación GPS guardada", Toast.LENGTH_SHORT).show();
            return;
        }
        String q = String.format(Locale.US, "geo:%.6f,%.6f?q=%.6f,%.6f(Grabación)", v.lat, v.lon, v.lat, v.lon);
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(q)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Toast.makeText(this, String.format(Locale.US, "%.6f, %.6f", v.lat, v.lon), Toast.LENGTH_LONG).show();
        }
    }

    private void protect(Recordings.Video v) {
        Toast.makeText(this, "Guardando copia protegida…", Toast.LENGTH_SHORT).show();
        worker.execute(() -> {
            String msg;
            try {
                File f = Recordings.protect(v);
                msg = "Guardado en la radio: K4811/Protegidas/" + f.getName();
            } catch (Exception e) {
                msg = "No pude guardar la copia: " + e.getMessage();
            }
            String m = msg;
            handler.post(() -> {
                Toast.makeText(this, m, Toast.LENGTH_LONG).show();
                reload();
            });
        });
    }

    private void askFreeSpace() {
        String[] opts = {"Borrar videos de más de 3 días", "Borrar videos de más de 7 días", "Borrar videos de más de 15 días"};
        int[] days = {3, 7, 15};
        new AlertDialog.Builder(this)
            .setTitle("Liberar espacio en el pendrive (no toca golpes ni protegidas)")
            .setItems(opts, (d, which) -> worker.execute(() -> {
                int n = Recordings.deleteOlderThan(days[which]);
                handler.post(() -> {
                    Toast.makeText(this, n + " videos borrados", Toast.LENGTH_LONG).show();
                    reload();
                });
            }))
            .setNegativeButton("Cancelar", null)
            .show();
    }

    private void askFormat() {
        new AlertDialog.Builder(this)
            .setTitle("Formatear el pendrive")
            .setMessage("Formatear BORRA TODO el pendrive de la cámara trasera. Si hay un video importante, "
                + "protégelo antes (botón «Proteger»).\n\nSe abre el almacenamiento de Android: elige el pendrive "
                + "USB → menú ⋮ → «Formatear». Después Streaming HD2 vuelve a crear sus carpetas sola.")
            .setPositiveButton("Ir a formatear", (d, w) -> {
                try {
                    startActivity(new Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception e) {
                    startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                }
            })
            .setNegativeButton("Cancelar", null)
            .show();
    }

    // ------------------------------------------------------------------ lugar (dirección)

    private String place(Recordings.Video v, TextView target) {
        if (Double.isNaN(v.lat)) return "Sin ubicación GPS";
        String key = String.format(Locale.US, "%.4f,%.4f", v.lat, v.lon);
        String cached = places.get(key);
        if (cached != null) return cached;
        String coords = String.format(Locale.US, "%.5f, %.5f", v.lat, v.lon);
        places.put(key, coords);
        if (Geocoder.isPresent()) {
            geo.execute(() -> {
                try {
                    List<Address> a = new Geocoder(this, ES).getFromLocation(v.lat, v.lon, 1);
                    if (a != null && !a.isEmpty()) {
                        Address ad = a.get(0);
                        String street = ad.getThoroughfare() == null ? "" : ad.getThoroughfare()
                            + (ad.getSubThoroughfare() == null ? "" : " " + ad.getSubThoroughfare());
                        String city = ad.getLocality() != null ? ad.getLocality() : ad.getSubAdminArea();
                        String s = (street.isEmpty() ? "" : street + ", ") + (city == null ? "" : city);
                        if (!s.trim().isEmpty()) {
                            places.put(key, s);
                            handler.post(adapter::notifyDataSetChanged);
                        }
                    }
                } catch (Exception ignored) {
                    // Sin internet: quedan las coordenadas.
                }
            });
        }
        return coords;
    }

    // ------------------------------------------------------------------ lista

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int i) {
            return rows.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int i) {
            return rows.get(i) instanceof Recordings.Video ? 1 : 0;
        }

        @Override
        public boolean isEnabled(int i) {
            return false;
        }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            Object o = rows.get(i);
            if (!(o instanceof Recordings.Video)) {
                TextView h = convert instanceof TextView ? (TextView) convert : text("", 14, bold, GOLD);
                h.setText(o.toString());
                h.setSingleLine(false);
                pad(h, 8, 18, 8, 8);
                return h;
            }
            Recordings.Video v = (Recordings.Video) o;
            Row r = convert != null && convert.getTag() instanceof Row ? (Row) convert.getTag() : new Row();
            r.bind(v);
            return r.root;
        }
    }

    private final class Row {
        final LinearLayout root;
        final TextView time, kind, place, size;
        Recordings.Video v;

        Row() {
            root = new LinearLayout(RecordingsActivity.this);
            root.setGravity(Gravity.CENTER_VERTICAL);
            root.setBackground(press(PANEL2, 16));
            pad(root, 18, 10, 12, 10);
            root.setTag(this);
            root.setLayoutParams(new android.widget.AbsListView.LayoutParams(-1, dp(84)));
            time = text("", 26, num, TEXT);
            root.addView(time, new LinearLayout.LayoutParams(dp(92), -2));
            LinearLayout mid = new LinearLayout(RecordingsActivity.this);
            mid.setOrientation(LinearLayout.VERTICAL);
            root.addView(mid, new LinearLayout.LayoutParams(0, -2, 1));
            LinearLayout line1 = new LinearLayout(RecordingsActivity.this);
            line1.setGravity(Gravity.CENTER_VERTICAL);
            kind = text("", 12.5f, bold, TEXT);
            pad(kind, 10, 3, 10, 3);
            line1.addView(kind);
            size = text("", 13, body, DIM);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
            slp.leftMargin = dp(10);
            line1.addView(size, slp);
            mid.addView(line1);
            place = text("", 15, body, MUTED);
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, -2);
            plp.topMargin = dp(6);
            mid.addView(place, plp);
            TextView mapB = button("Mapa", false);
            root.addView(mapB, new LinearLayout.LayoutParams(dp(92), dp(54)) {{
                leftMargin = dp(8);
            }});
            TextView protB = button("Proteger", false);
            protB.setTextColor(GOLD);
            root.addView(protB, new LinearLayout.LayoutParams(dp(118), dp(54)) {{
                leftMargin = dp(8);
            }});
            root.setOnClickListener(x -> play(v));
            mapB.setOnClickListener(x -> map(v));
            protB.setOnClickListener(x -> protect(v));
            protB.setTag("prot");
        }

        void bind(Recordings.Video video) {
            v = video;
            time.setText(new SimpleDateFormat("HH:mm", Locale.US).format(new Date(v.startMs)));
            kind.setText(v.kind.toUpperCase(ES));
            int c = Recordings.REAR.equals(v.kind) ? BLUE : Recordings.LOCKED.equals(v.kind) ? RED
                : Recordings.PROTECTED.equals(v.kind) ? GOLD : Recordings.DASHCAM.equals(v.kind) ? OK : MUTED;
            kind.setTextColor(c);
            kind.setBackground(shape((c & 0x00FFFFFF) | 0x26000000, 10, 0));
            String sp = Float.isNaN(v.speed) || v.speed < 1 ? "" : " · " + Math.round(v.speed) + " km/h";
            size.setText(String.format(ES, "%.0f MB", v.size / 1048576f) + sp);
            place.setText("📍 " + place(v, place));
            View prot = root.findViewWithTag("prot");
            if (prot != null) prot.setVisibility(Recordings.PROTECTED.equals(v.kind) ? View.INVISIBLE : View.VISIBLE);
        }
    }

    // ------------------------------------------------------------------ helpers

    private String dayLabel(long t) {
        Calendar c = Calendar.getInstance();
        Calendar d = Calendar.getInstance();
        d.setTimeInMillis(t);
        String base = new SimpleDateFormat("EEEE d 'de' MMMM yyyy", ES).format(new Date(t)).toUpperCase(ES);
        if (c.get(Calendar.YEAR) == d.get(Calendar.YEAR) && c.get(Calendar.DAY_OF_YEAR) == d.get(Calendar.DAY_OF_YEAR)) {
            return "HOY · " + base;
        }
        c.add(Calendar.DAY_OF_YEAR, -1);
        if (c.get(Calendar.YEAR) == d.get(Calendar.YEAR) && c.get(Calendar.DAY_OF_YEAR) == d.get(Calendar.DAY_OF_YEAR)) {
            return "AYER · " + base;
        }
        return base;
    }

    private static String since(long ms) {
        long min = ms / 60000;
        if (min < 60) return "hace " + min + " min";
        long h = min / 60;
        if (h < 48) return "hace " + h + " h";
        return "hace " + (h / 24) + " días";
    }

    private static String gb(long bytes) {
        return String.format(ES, "%.1f GB", bytes / 1073741824.0);
    }

    private Typeface font(String path) {
        try {
            return Typeface.createFromAsset(getAssets(), path);
        } catch (Exception e) {
            return Typeface.DEFAULT;
        }
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void pad(View v, float l, float t, float r, float b) {
        v.setPadding(dp(l), dp(t), dp(r), dp(b));
    }

    private LinearLayout.LayoutParams top(float m) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(m);
        return lp;
    }

    private TextView text(String s, float size, Typeface tf, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size);
        t.setTypeface(tf);
        t.setTextColor(color);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        return t;
    }

    private TextView caption(String s) {
        TextView t = text(s, 12, bold, GOLD);
        if (android.os.Build.VERSION.SDK_INT >= 21) t.setLetterSpacing(0.14f);
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(shape(PANEL, 20, LINE));
        pad(c, 18, 16, 18, 16);
        return c;
    }

    private TextView button(String s, boolean primary) {
        TextView b = text(s, 16, bold, primary ? 0xFF111111 : TEXT);
        b.setGravity(Gravity.CENTER);
        b.setBackground(press(primary ? GOLD : PANEL2, 14));
        return b;
    }

    private GradientDrawable shape(int color, float r, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(r));
        if (stroke != 0) g.setStroke(Math.max(1, dp(1)), stroke);
        return g;
    }

    private StateListDrawable press(int color, float r) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, shape(0xFF262D37, r, GOLD));
        s.addState(new int[]{}, shape(color, r, LINE));
        return s;
    }
}
