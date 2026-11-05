package com.k4811.launcher.wrx;

import android.content.Context;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Recordatorios de mantención del auto (revisión técnica, permiso, SOAP, aceite...). Cada uno vence
 * por fecha, por kilómetros o por lo que llegue primero, y puede repetirse (al marcarlo hecho se crea
 * el siguiente). Se guardan en K4811/mantencion.json para que sobrevivan a una reinstalación.
 */
public final class Maintenance {

    public static final class Item {
        public long id;
        public String title = "";
        public String note = "";
        /** Fecha de vencimiento (medianoche, ms) o 0 = sin fecha. */
        public long dueDate;
        /** Odómetro de vencimiento o 0 = sin km. */
        public int dueKm;
        public int repeatMonths;
        public int repeatKm;
        public boolean done;
        public long doneAt;

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("title", title);
            o.put("note", note);
            o.put("due_date", dueDate == 0 ? "" : DAY.format(new Date(dueDate)));
            o.put("due_km", dueKm);
            o.put("repeat_months", repeatMonths);
            o.put("repeat_km", repeatKm);
            o.put("done", done);
            o.put("done_at", doneAt);
            return o;
        }

        static Item fromJson(JSONObject o) {
            Item i = new Item();
            i.id = o.optLong("id", System.currentTimeMillis());
            i.title = o.optString("title", "");
            i.note = o.optString("note", "");
            String d = o.optString("due_date", "");
            try {
                i.dueDate = d.isEmpty() ? 0 : DAY.parse(d).getTime();
            } catch (Exception e) {
                i.dueDate = 0;
            }
            i.dueKm = o.optInt("due_km", 0);
            i.repeatMonths = o.optInt("repeat_months", 0);
            i.repeatKm = o.optInt("repeat_km", 0);
            i.done = o.optBoolean("done", false);
            i.doneAt = o.optLong("done_at", 0);
            return i;
        }

        /** Días hasta la fecha (negativo = vencido); Integer.MAX_VALUE sin fecha. */
        public int daysLeft() {
            if (dueDate == 0) return Integer.MAX_VALUE;
            return (int) Math.floor((dueDate - startOfToday()) / 86400000.0);
        }

        /** Km que faltan (negativo = pasado); Integer.MAX_VALUE sin km u odómetro. */
        public int kmLeft() {
            if (dueKm <= 0 || Double.isNaN(CarState.odometerKm)) return Integer.MAX_VALUE;
            return (int) Math.round(dueKm - CarState.odometerKm);
        }

        /** 2 = vencido, 1 = pronto (≤ 30 días o ≤ 1000 km), 0 = al día. */
        public int urgency() {
            if (done) return 0;
            int d = daysLeft(), k = kmLeft();
            if (d < 0 || (k != Integer.MAX_VALUE && k < 0)) return 2;
            if (d <= 30 || (k != Integer.MAX_VALUE && k <= 1000)) return 1;
            return 0;
        }

        /** Para ordenar: lo más cercano primero (los km se convierten a días a ~40 km por día). */
        int sortKey() {
            int d = daysLeft();
            int k = kmLeft();
            int byKm = k == Integer.MAX_VALUE ? Integer.MAX_VALUE : k / 40;
            return Math.min(d, byKm);
        }

        /** "Vence en 12 días · faltan 800 km" / "Venció hace 3 días". */
        public String status() {
            StringBuilder b = new StringBuilder();
            int d = daysLeft();
            if (dueDate != 0) {
                if (d < 0) b.append("Venció hace ").append(-d).append(d == -1 ? " día" : " días");
                else if (d == 0) b.append("Vence hoy");
                else if (d == 1) b.append("Vence mañana");
                else if (d <= 60) b.append("Vence en ").append(d).append(" días");
                else b.append("Vence el ").append(SHOW.format(new Date(dueDate)));
            }
            if (dueKm > 0) {
                if (b.length() > 0) b.append(" · ");
                int k = kmLeft();
                if (k == Integer.MAX_VALUE) b.append("a los ").append(km(dueKm)).append(" km");
                else if (k < 0) b.append("pasado por ").append(km(-k)).append(" km");
                else b.append("faltan ").append(km(k)).append(" km");
            }
            if (b.length() == 0) b.append("Sin fecha");
            return b.toString();
        }
    }

    static final SimpleDateFormat DAY = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    static final SimpleDateFormat SHOW = new SimpleDateFormat("d MMM yyyy", new Locale("es", "CL"));

    public static String km(long v) {
        return String.format(new Locale("es", "CL"), "%,d", v).replace(',', '.');
    }

    static long startOfToday() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    // ------------------------------------------------------------------ archivo

    private static File file(Context c) {
        File dir = new File(Environment.getExternalStorageDirectory(), "K4811");
        if (dir.isDirectory() || dir.mkdirs()) return new File(dir, "mantencion.json");
        return new File(c.getFilesDir(), "mantencion.json");
    }

    public static synchronized List<Item> load(Context c) {
        List<Item> out = new ArrayList<>();
        File f = file(c);
        if (!f.isFile()) return out;
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int n = 0;
            while (n < buf.length) {
                int r = in.read(buf, n, buf.length - n);
                if (r < 0) break;
                n += r;
            }
            JSONArray a = new JSONObject(new String(buf, 0, n, "UTF-8")).optJSONArray("items");
            if (a != null) for (int i = 0; i < a.length(); i++) out.add(Item.fromJson(a.getJSONObject(i)));
        } catch (Exception ignored) {
        }
        return out;
    }

    public static synchronized void save(Context c, List<Item> items) {
        File f = file(c);
        File tmp = new File(f.getPath() + ".tmp");
        try {
            JSONArray a = new JSONArray();
            for (Item i : items) a.put(i.toJson());
            JSONObject root = new JSONObject();
            root.put("version", 1);
            root.put("items", a);
            try (OutputStream out = new FileOutputStream(tmp)) {
                out.write(root.toString(2).getBytes("UTF-8"));
            }
            if (!tmp.renameTo(f)) {
                f.delete();
                tmp.renameTo(f);
            }
        } catch (Exception ignored) {
        }
    }

    /** Pendientes, lo más urgente primero. */
    public static List<Item> pending(Context c) {
        List<Item> out = new ArrayList<>();
        for (Item i : load(c)) if (!i.done) out.add(i);
        Collections.sort(out, (a, b) -> Integer.compare(a.sortKey(), b.sortKey()));
        return out;
    }

    /** Marca hecho y, si se repite, crea el siguiente desde hoy / desde el odómetro actual. */
    public static void complete(Context c, long id) {
        List<Item> all = load(c);
        Item next = null;
        for (Item i : all) {
            if (i.id != id) continue;
            i.done = true;
            i.doneAt = System.currentTimeMillis();
            if (i.repeatMonths > 0 || i.repeatKm > 0) {
                next = new Item();
                next.id = System.currentTimeMillis();
                next.title = i.title;
                next.note = i.note;
                next.repeatMonths = i.repeatMonths;
                next.repeatKm = i.repeatKm;
                if (i.repeatMonths > 0) {
                    Calendar cal = Calendar.getInstance();
                    cal.setTimeInMillis(i.dueDate != 0 && i.dueDate > startOfToday() ? i.dueDate : startOfToday());
                    cal.add(Calendar.MONTH, i.repeatMonths);
                    next.dueDate = cal.getTimeInMillis();
                }
                if (i.repeatKm > 0) {
                    double base = Double.isNaN(CarState.odometerKm) ? i.dueKm : CarState.odometerKm;
                    next.dueKm = (int) Math.round(base + i.repeatKm);
                }
            }
        }
        if (next != null) all.add(next);
        save(c, all);
    }

    public static void delete(Context c, long id) {
        List<Item> all = load(c);
        for (int k = all.size() - 1; k >= 0; k--) if (all.get(k).id == id) all.remove(k);
        save(c, all);
    }

    public static void upsert(Context c, Item item) {
        List<Item> all = load(c);
        boolean found = false;
        for (int k = 0; k < all.size(); k++) {
            if (all.get(k).id == item.id) {
                all.set(k, item);
                found = true;
            }
        }
        if (!found) all.add(item);
        save(c, all);
    }

    /** Sugerencias rápidas: título, meses y km para repetir. */
    public static final Object[][] PRESETS = {
        {"Revisión técnica", 12, 0},
        {"Permiso de circulación", 12, 0},
        {"SOAP", 12, 0},
        {"Cambio de aceite y filtro", 6, 5000},
        {"Rotación de neumáticos", 0, 10000},
        {"Pastillas de freno", 0, 30000},
        {"Líquido de frenos", 24, 0},
        {"Filtro de aire", 12, 20000},
        {"Bujías", 0, 40000},
        {"Refrigerante", 36, 0},
    };
}
