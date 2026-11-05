package com.k4811.launcher;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.AnimationSet;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LayoutAnimationController;
import android.view.animation.OvershootInterpolator;
import android.view.animation.ScaleAnimation;
import android.view.animation.TranslateAnimation;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.k4811.launcher.home.AppCatalog;
import com.k4811.common.ui.IconView;
import com.k4811.common.ui.Touch;
import com.k4811.common.ui.Ui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Every installed app in a 5-column grid that scrolls down until the last one (no pages). The
 * icons come in with a short cascade the first time, sink a little under the finger and the app
 * opens zooming out of its icon. Back returns to the home screen.
 */
public class AppsActivity extends Activity {

    /** Rows visible at once: two and a half, so the cut row shows there is more below. */
    private static final float VISIBLE_ROWS = 2.5f;
    private static final float PRESSED_SCALE = 0.93f;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final List<AppCatalog.Entry> apps = new ArrayList<>();

    private GridView grid;
    private TextView countView;
    private TextView empty;
    private AppsAdapter adapter;
    private int rowHeight;
    private int loadGen;
    /** The cascade only plays when the drawer opens, not when coming back from an app. */
    private boolean animatedIn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Ui.applyDesignDensity(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_apps);
        Ui.applyFont(findViewById(R.id.apps_root));

        ((TextView) findViewById(R.id.apps_title)).setTypeface(Ui.semiBold(this));
        countView = (TextView) findViewById(R.id.apps_count);
        empty = (TextView) findViewById(R.id.apps_empty);

        IconView back = (IconView) findViewById(R.id.apps_back);
        back.set(IconView.Icon.CHEVRON_LEFT, IconView.Style.NONE);
        back.setOnClickListener(v -> finish());

        grid = (GridView) findViewById(R.id.apps_grid);
        // A longer, softer fling than the default: the list glides to a stop.
        grid.setFriction(ViewConfiguration.getScrollFriction() * 0.7f);
        grid.setVelocityScale(1.15f);
        grid.setLayoutAnimation(cascade());
        adapter = new AppsAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, position, id) -> open(view, position));
        // Mantener presionado: ocultar la app del cajón (o desinstalarla si es tuya).
        grid.setOnItemLongClickListener((parent, view, position, id) -> {
            askHide(position);
            return true;
        });
        // Row height follows the area the grid really got.
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = b - t;
            if (h <= 0 || h == ob - ot) return;
            int usable = h - grid.getPaddingTop() - Math.round(Ui.dp(this, 8)) * 2;
            int next = Math.round(usable / VISIBLE_ROWS);
            if (next != rowHeight) {
                rowHeight = next;
                v.post(adapter::notifyDataSetChanged);
            }
        });
        Touch.install(findViewById(R.id.apps_back));
    }

    @Override
    protected void onResume() {
        super.onResume();
        Ui.hideSystemBars(getWindow());
        reload();
    }

    @Override
    protected void onDestroy() {
        loadGen++;
        loader.shutdownNow();
        handler.removeCallbacksAndMessages(null);
        grid.setAdapter(null);
        AppCatalog.recycle(new ArrayList<>(apps));
        apps.clear();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Ui.applyDesignDensity(this);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Ui.hideSystemBars(getWindow());
    }

    private void reload() {
        final int gen = ++loadGen;
        final int iconPx = Math.round(Ui.dp(this, 88));
        loader.execute(() -> {
            List<AppCatalog.Entry> loaded = AppCatalog.load(getApplicationContext(), iconPx);
            handler.post(() -> {
                if (isFinishing() || gen != loadGen) {
                    AppCatalog.recycle(loaded);
                    return;
                }
                List<AppCatalog.Entry> previous = new ArrayList<>(apps);
                apps.clear();
                apps.addAll(loaded);
                show();
                // The grid redraws on the next frames: free the old icons only after that.
                handler.postDelayed(() -> AppCatalog.recycle(previous), 1500);
            });
        });
    }

    private void show() {
        boolean none = apps.isEmpty();
        grid.setVisibility(none ? View.GONE : View.VISIBLE);
        empty.setVisibility(none ? View.VISIBLE : View.GONE);
        countView.setText(none ? "" : getResources().getQuantityString(R.plurals.home_apps_count, apps.size(), apps.size()));
        adapter.notifyDataSetChanged();
        if (!none && !animatedIn) {
            animatedIn = true;
            grid.scheduleLayoutAnimation();
        }
    }

    /** Each icon rises a little, grows from 92% and fades in, one shortly after the other. */
    private LayoutAnimationController cascade() {
        AnimationSet set = new AnimationSet(true);
        set.addAnimation(new AlphaAnimation(0f, 1f));
        set.addAnimation(new TranslateAnimation(0, 0, Ui.dp(this, 36), 0));
        set.addAnimation(new ScaleAnimation(0.92f, 1f, 0.92f, 1f,
            ScaleAnimation.RELATIVE_TO_SELF, 0.5f, ScaleAnimation.RELATIVE_TO_SELF, 0.5f));
        set.setDuration(320);
        set.setInterpolator(new DecelerateInterpolator(1.6f));
        LayoutAnimationController c = new LayoutAnimationController(set, 0.12f);
        c.setOrder(LayoutAnimationController.ORDER_NORMAL);
        return c;
    }

    private void open(View cell, int position) {
        if (position < 0 || position >= apps.size()) return;
        AppCatalog.Entry entry = apps.get(position);
        cell.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        try {
            Intent i = new Intent();
            i.setComponent(new ComponentName(entry.packageName, entry.className));
            // Our own screens (e.g. Ventoinha) have no LAUNCHER category; external apps do.
            if (!getPackageName().equals(entry.packageName)) {
                i.setAction(Intent.ACTION_MAIN);
                i.addCategory(Intent.CATEGORY_LAUNCHER);
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            // The app grows out of its icon.
            Bundle zoom = ActivityOptions.makeScaleUpAnimation(cell, 0, 0, cell.getWidth(), cell.getHeight()).toBundle();
            startActivity(i, zoom);
        } catch (Exception e) {
            Toast.makeText(this, R.string.home_apps_not_found, Toast.LENGTH_SHORT).show();
        }
    }

    private void askHide(int position) {
        if (position < 0 || position >= apps.size()) return;
        AppCatalog.Entry e = apps.get(position);
        boolean system;
        try {
            system = (getPackageManager().getApplicationInfo(e.packageName, 0).flags
                & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
        } catch (Exception ex) {
            system = true;
        }
        java.util.List<String> opts = new java.util.ArrayList<>();
        opts.add("Ocultar «" + e.label + "» del cajón");
        if (!system) opts.add("Desinstalar «" + e.label + "»");
        opts.add("Volver a mostrar todas las apps ocultas");
        new android.app.AlertDialog.Builder(this)
            .setTitle(e.label)
            .setItems(opts.toArray(new String[0]), (d, which) -> {
                String o = opts.get(which);
                if (o.startsWith("Ocultar")) {
                    AppCatalog.setHidden(this, e.packageName, e.className, true);
                    reload();
                } else if (o.startsWith("Desinstalar")) {
                    startActivity(new Intent(Intent.ACTION_DELETE,
                        android.net.Uri.parse("package:" + e.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } else {
                    AppCatalog.showAll(this);
                    reload();
                }
            })
            .setNegativeButton("Cancelar", null)
            .show();
    }

    private final class AppsAdapter extends BaseAdapter {
        private final OvershootInterpolator spring = new OvershootInterpolator(3f);
        private final DecelerateInterpolator press = new DecelerateInterpolator();

        @Override
        public int getCount() {
            return apps.size();
        }

        @Override
        public Object getItem(int position) {
            return apps.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View cell = convertView;
            if (cell == null) {
                cell = getLayoutInflater().inflate(R.layout.app_cell, parent, false);
                Ui.applyFont(cell);
                ((TextView) cell.findViewById(R.id.app_label)).setTypeface(Ui.medium(AppsActivity.this));
                // Sinks under the finger and springs back; the grid still gets the tap and the scroll.
                cell.setOnTouchListener((v, e) -> {
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            v.animate().scaleX(PRESSED_SCALE).scaleY(PRESSED_SCALE)
                                .setDuration(90).setInterpolator(press).start();
                            break;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            v.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(spring).start();
                            break;
                        default:
                            break;
                    }
                    return false;
                });
            }
            if (rowHeight > 0) {
                ViewGroup.LayoutParams lp = cell.getLayoutParams();
                if (lp == null || lp.height != rowHeight) {
                    cell.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight));
                }
            }
            AppCatalog.Entry entry = apps.get(position);
            cell.setContentDescription(entry.label);
            ((TextView) cell.findViewById(R.id.app_label)).setText(entry.label);
            ImageView icon = (ImageView) cell.findViewById(R.id.app_icon);
            if (entry.icon != null) icon.setImageBitmap(entry.icon);
            else icon.setImageDrawable(null);
            return cell;
        }
    }
}
