package com.limelight.ui;

import android.app.AlertDialog;
import android.app.Dialog;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.graphics.drawable.ColorDrawable;
import android.widget.GridLayout;
import com.limelight.diagnostics.PanelGeometry;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import com.limelight.R;
import com.limelight.diagnostics.DiagnosticCharts;
import com.limelight.diagnostics.TrendHistory;
import java.util.ArrayList;
import java.util.Locale;

/** Passive HUD body plus explicit controls. Opening a dialog releases remote touches via focus loss. */
public final class PerformanceOverlayView extends LinearLayout {
    private static final int[] HISTORY_SECONDS = {30, 60, 180, 300};
    private static final int[] REFRESH_MS = {100, 250, 500, 1000, 2000};
    private final SharedPreferences prefs;
    private final TrendHistory history = new TrendHistory(DiagnosticCharts.SERIES, 3000);
    private final ArrayList<Chart> detailCharts = new ArrayList<>();
    private final TextView hudText;
    private final LinearLayout body;
    private final Chart hudNetwork, hudInput;
    private final float density;
    private Dialog dashboard;
    private AlertDialog settings, optionPicker;
    private GridLayout dashboardGrid;
    private int dashboardColumns;
    private View activityRoot;
    private final OnLayoutChangeListener rootLayoutListener = (v, l, t, r, b, ol, ot, or, ob) -> {
        if (r-l != or-ol || b-t != ob-ot) {
            post(() -> { restoreHudPosition(); restoreDashboardBounds(); });
        }
    };
    private TextView detailText;
    private Button pause;
    private boolean paused;
    private boolean compact;
    private int historySeconds, chartMask, opacity;
    private volatile int refreshMs;
    private long lastUpdateMs;
    private String latestText = "";
    private float[] latest;

    public PerformanceOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        prefs = context.getSharedPreferences("stream_diagnostics", Context.MODE_PRIVATE);
        setOrientation(VERTICAL);
        setPadding(dp(5), 0, dp(5), dp(4));
        setClickable(false);
        body = new LinearLayout(context);
        body.setOrientation(VERTICAL);
        LinearLayout header = new LinearLayout(context);
        TextView handle = label(16); handle.setText("⠿"); handle.setGravity(Gravity.CENTER);
        handle.setContentDescription(getContext().getString(R.string.diag_drag));
        header.addView(handle, new LayoutParams(dp(30), -1));
        installDrag(handle, false, false);
        button(header, R.string.diag_fold, v -> {
            boolean visible = body.getVisibility() == VISIBLE;
            body.setVisibility(visible ? GONE : VISIBLE);
            ((Button)v).setText(visible ? R.string.diag_unfold : R.string.diag_fold);
        });
        button(header, R.string.diag_open_dashboard, v -> showDashboard());
        button(header, R.string.diag_settings, v -> showSettings());
        addView(header, new LayoutParams(-1, dp(48)));
        hudText = label(10);
        body.addView(hudText);
        hudNetwork = new Chart(context, 0, true);
        hudInput = new Chart(context, 2, true);
        body.addView(hudNetwork, new LayoutParams(-1, dp(76)));
        body.addView(hudInput, new LayoutParams(-1, dp(76)));
        addView(body);
        loadSettings();
    }

    private int dp(float value) { return Math.round(value * density); }

    private TextView label(int sp) {
        TextView text = new TextView(getContext());
        text.setTextColor(Color.WHITE);
        text.setTextSize(sp);
        return text;
    }

    private Button button(LinearLayout parent, int title, OnClickListener action) {
        Button b = new Button(getContext());
        b.setText(title);
        b.setTextSize(11);
        b.setAllCaps(false);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(3), 0, dp(3), 0);
        b.setOnClickListener(action);
        parent.addView(b, new LayoutParams(0, -1, 1));
        return b;
    }

    private void loadSettings() {
        historySeconds = prefs.getInt("history", 60);
        refreshMs = prefs.getInt("refresh_v2", 250);
        chartMask = prefs.getInt("charts", 1023);
        if (!prefs.contains("touch_charts_v1")) chartMask |= 15 << 10;
        if (!prefs.contains("network_charts_v1")) chartMask |= ((1 << DiagnosticCharts.GROUPS.length)-1) ^ ((1 << 14)-1);
        opacity = Math.max(30, Math.min(100, prefs.getInt("opacity", 80)));
        compact = prefs.getBoolean("compact", true);
        hudText.setMaxLines(compact ? 4 : 10);
        hudText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        setBackgroundColor(Color.argb(opacity * 255 / 100, 21, 27, 35));
    }

    public int getRefreshMs() { return refreshMs; }

    public void update(String text, long now, float[] sample) {
        if (paused) return;
        latestText = text;
        latest = sample;
        if (lastUpdateMs != 0 && now - lastUpdateMs > refreshMs * 2L) {
            // A missing point prevents a straight line from hiding a UI stall or a paused interval.
            float[] missing = new float[DiagnosticCharts.SERIES];
            java.util.Arrays.fill(missing, Float.NaN);
            history.addAt(now - 1, missing);
        }
        lastUpdateMs = now;
        history.addAt(now, sample);
        updateText();
        hudNetwork.invalidate();
        hudInput.invalidate();
        for (Chart chart : detailCharts) chart.invalidate();
    }

    private void updateText() {
        if (compact && latest != null) {
            hudText.setText(getContext().getString(R.string.diag_hud_summary,
                    latest[DiagnosticCharts.RTT], latest[DiagnosticCharts.RENDER_FPS],
                    latest[DiagnosticCharts.INPUT_P95], latest[DiagnosticCharts.INPUT_PEAK],
                    latest[DiagnosticCharts.SUBMIT_PEAK], (int)latest[DiagnosticCharts.ERRORS]));
        }
        else hudText.setText(latestText);
        if (detailText != null) detailText.setText(latestText);
    }

    private void togglePause() {
        paused = !paused;
        if (pause != null) pause.setText(paused ? R.string.diag_resume : R.string.diag_pause);
    }

    private void immersive(Dialog dialog) {
        Window window = dialog.getWindow();
        if (window == null) return;
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            }
        }
    }

    private void showImmersive(Dialog dialog) {
        Window window = dialog.getWindow();
        if (window != null) window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        immersive(dialog); dialog.show();
        if (window != null) window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        immersive(dialog);
    }

    private int screenWidth() {
        return getRootView().getWidth() > 0 ? getRootView().getWidth() : getResources().getDisplayMetrics().widthPixels;
    }

    private int screenHeight() {
        return getRootView().getHeight() > 0 ? getRootView().getHeight() : getResources().getDisplayMetrics().heightPixels;
    }

    private void restoreHudPosition() {
        if (getWidth() == 0 || getHeight() == 0) return;
        int x = prefs.contains("hud_x") ? Math.round(prefs.getFloat("hud_x", 0) * Math.max(0, screenWidth()-getWidth())) : (int)getX();
        int y = prefs.contains("hud_y") ? Math.round(prefs.getFloat("hud_y", 0) * Math.max(0, screenHeight()-getHeight())) : (int)getY();
        int[] rect = PanelGeometry.fit(screenWidth(), screenHeight(), x, y, getWidth(), getHeight(), 1, 1);
        setX(rect[0]); setY(rect[1]);
    }

    private void saveHudPosition() {
        prefs.edit().putFloat("hud_x", getX()/Math.max(1, screenWidth()-getWidth()))
                .putFloat("hud_y", getY()/Math.max(1, screenHeight()-getHeight())).apply();
    }

    private void restoreDashboardBounds() {
        if (dashboard == null || !dashboard.isShowing()) return;
        int w = Math.round(prefs.getFloat("panel_w", .75f)*screenWidth());
        int h = Math.round(prefs.getFloat("panel_h", .78f)*screenHeight());
        int x = Math.round(prefs.getFloat("panel_x", .95f)*Math.max(0, screenWidth()-w));
        int y = Math.round(prefs.getFloat("panel_y", .06f)*Math.max(0, screenHeight()-h));
        setDashboardBounds(x, y, w, h);
    }

    private void setDashboardBounds(int x, int y, int w, int h) {
        if (dashboard == null || dashboard.getWindow() == null) return;
        int[] rect = PanelGeometry.fit(screenWidth(), screenHeight(), x, y, w, h, dp(260), dp(160));
        WindowManager.LayoutParams params = dashboard.getWindow().getAttributes();
        // Window coordinates are physical screen coordinates, independent of text direction.
        params.gravity = Gravity.TOP | Gravity.getAbsoluteGravity(Gravity.START, View.LAYOUT_DIRECTION_LTR);
        params.x = rect[0]; params.y = rect[1]; params.width = rect[2]; params.height = rect[3];
        dashboard.getWindow().setAttributes(params);
    }

    private void saveDashboardBounds() {
        if (dashboard == null || dashboard.getWindow() == null) return;
        WindowManager.LayoutParams p = dashboard.getWindow().getAttributes();
        prefs.edit().putFloat("panel_w", p.width/(float)screenWidth())
                .putFloat("panel_h", p.height/(float)screenHeight())
                .putFloat("panel_x", p.x/(float)Math.max(1, screenWidth()-p.width))
                .putFloat("panel_y", p.y/(float)Math.max(1, screenHeight()-p.height)).apply();
    }

    private void installDrag(View handle, boolean window, boolean resize) {
        handle.setOnTouchListener(new OnTouchListener() {
            private int pointer = -1, x, y, width, height;
            private float rawX, rawY;
            @Override
            public boolean onTouch(View view, MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    pointer = event.getPointerId(0); rawX = event.getRawX(); rawY = event.getRawY();
                    if (window && dashboard != null && dashboard.getWindow() != null) {
                        WindowManager.LayoutParams p = dashboard.getWindow().getAttributes();
                        x = p.x; y = p.y; width = p.width; height = p.height;
                    }
                    else { x = (int)getX(); y = (int)getY(); width = getWidth(); height = getHeight(); }
                    return true;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_MOVE && pointer >= 0) {
                    int index = event.findPointerIndex(pointer);
                    if (index < 0) return true;
                    float dx = event.getRawX()+event.getX(index)-event.getX(0)-rawX;
                    float dy = event.getRawY()+event.getY(index)-event.getY(0)-rawY;
                    if (window) {
                        if (resize) setDashboardBounds(x, y,
                                Math.min(screenWidth()-x, width+Math.round(dx)),
                                Math.min(screenHeight()-y, height+Math.round(dy)));
                        else setDashboardBounds(x+Math.round(dx), y+Math.round(dy), width, height);
                    }
                    else {
                        int[] rect = PanelGeometry.fit(screenWidth(), screenHeight(), x+Math.round(dx), y+Math.round(dy), width, height, 1, 1);
                        setX(rect[0]); setY(rect[1]);
                    }
                    return true;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL ||
                        (event.getActionMasked() == MotionEvent.ACTION_POINTER_UP && event.getPointerId(event.getActionIndex()) == pointer)) {
                    pointer = -1;
                    if (window) saveDashboardBounds(); else saveHudPosition();
                    if (event.getActionMasked() == MotionEvent.ACTION_UP) view.performClick();
                }
                return true;
            }
        });
    }

    private void reflowCharts() {
        if (dashboardGrid == null || dashboardGrid.getWidth() == 0) return;
        int columns = PanelGeometry.columns(dashboardGrid.getWidth()/density, prefs.getInt("columns", 0));
        if (columns == dashboardColumns) return;
        dashboardColumns = columns;
        dashboardGrid.removeAllViews(); dashboardGrid.setColumnCount(columns);
        for (int i = 0; i < detailCharts.size(); i++) {
            GridLayout.LayoutParams p = new GridLayout.LayoutParams(GridLayout.spec(i/columns), GridLayout.spec(i%columns, 1f));
            p.width = 0; p.height = dp(156); p.setMargins(dp(2), dp(1), dp(2), dp(1));
            p.setGravity(Gravity.FILL_HORIZONTAL);
            dashboardGrid.addView(detailCharts.get(i), p);
        }
    }

    private void showDashboard() {
        if (dashboard != null && dashboard.isShowing()) return;
        LinearLayout content = new LinearLayout(getContext());
        content.setOrientation(VERTICAL); content.setPadding(dp(6), dp(2), dp(6), dp(2));
        content.setBackgroundColor(0xf2151b23);
        LinearLayout title = new LinearLayout(getContext());
        TextView heading = label(12); heading.setText(R.string.diag_drag_title); heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setContentDescription(getContext().getString(R.string.diag_floating_help));
        title.addView(heading, new LayoutParams(0, -1, 1)); installDrag(heading, true, false);
        Button close = new Button(getContext()); close.setText("×"); close.setTextSize(16);
        close.setPadding(0, 0, 0, 0); close.setMinWidth(0); close.setMinimumWidth(0);
        close.setContentDescription(getContext().getString(R.string.diag_close));
        close.setOnClickListener(v -> dashboard.dismiss());
        title.addView(close, new LayoutParams(dp(40), -1)); content.addView(title, new LayoutParams(-1, dp(34)));
        LinearLayout tools = new LinearLayout(getContext());
        pause = button(tools, paused ? R.string.diag_resume : R.string.diag_pause, v -> togglePause());
        button(tools, R.string.diag_reset, v -> {
            history.clear(); lastUpdateMs = 0;
            for (Chart chart : detailCharts) chart.invalidate();
            hudNetwork.invalidate(); hudInput.invalidate();
        });
        button(tools, R.string.diag_settings, v -> showSettings());
        button(tools, R.string.diag_detail_text, v -> {
            if (detailText != null) detailText.setVisibility(detailText.getVisibility() == VISIBLE ? GONE : VISIBLE);
        });
        for (int i = 0; i < tools.getChildCount(); i++) ((Button)tools.getChildAt(i)).setTextSize(10);
        content.addView(tools, new LayoutParams(-1, dp(36)));
        ScrollView scroll = new ScrollView(getContext());
        LinearLayout rows = new LinearLayout(getContext()); rows.setOrientation(VERTICAL);
        detailText = label(10); detailText.setText(latestText); detailText.setVisibility(GONE); rows.addView(detailText);
        dashboardGrid = new GridLayout(getContext()); rows.addView(dashboardGrid, new LayoutParams(-1, -2));
        dashboardColumns = 0; detailCharts.clear();
        for (int group = 0; group < DiagnosticCharts.GROUPS.length; group++) {
            if ((chartMask & (1 << group)) != 0) detailCharts.add(new Chart(getContext(), group, false));
        }
        dashboardGrid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r-l != or-ol) v.post(() -> reflowCharts());
        });
        scroll.addView(rows); content.addView(scroll, new LayoutParams(-1, 0, 1));
        TextView resize = label(12); resize.setText(R.string.diag_resize); resize.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        resize.setContentDescription(getContext().getString(R.string.diag_resize));
        content.addView(resize, new LayoutParams(-1, dp(24))); installDrag(resize, true, true);
        dashboard = new Dialog(getContext()) {
            @Override public void onWindowFocusChanged(boolean hasFocus) {
                super.onWindowFocusChanged(hasFocus);
                if (hasFocus) immersive(this);
            }
        };
        dashboard.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dashboard.setContentView(content, new ViewGroup.LayoutParams(-1, -1));
        dashboard.setCanceledOnTouchOutside(false);
        if (dashboard.getWindow() != null) {
            dashboard.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dashboard.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            dashboard.getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
        }
        dashboard.setOnDismissListener(d -> {
            detailCharts.clear(); detailText = null; pause = null; dashboardGrid = null; paused = false;
        });
        showImmersive(dashboard); restoreDashboardBounds();
    }

    private Spinner choice(LinearLayout parent, int title, String[] items, int selected) {
        TextView text = label(14); text.setText(title); parent.addView(text);
        Spinner spinner = new Spinner(getContext(), Spinner.MODE_DIALOG) {
            // Spinner.super.performClick() opens an additional non-immersive popup.
            // Keep its selection model, but provide the standard accessible choice dialog ourselves.
            @android.annotation.SuppressLint("ClickableViewAccessibility")
            @Override public boolean performClick() {
                sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED);
                if (optionPicker != null) optionPicker.dismiss();
                optionPicker = new AlertDialog.Builder(getContext()).setTitle(title)
                        .setSingleChoiceItems(items, getSelectedItemPosition(), (d, which) -> {
                            setSelection(which); d.dismiss();
                        }).create();
                optionPicker.setOnDismissListener(d -> { if (optionPicker == d) optionPicker = null; });
                showImmersive(optionPicker);
                return true;
            }
        };
        ArrayAdapter<String> adapter = new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter); spinner.setSelection(selected);
        parent.addView(spinner, new LayoutParams(-1, dp(48)));
        return spinner;
    }

    private int selected(int[] options, int value) {
        for (int i = 0; i < options.length; i++) if (options[i] == value) return i;
        return 1;
    }

    private void showSettings() {
        if (settings != null && settings.isShowing()) return;
        ScrollView scroll = new ScrollView(getContext());
        LinearLayout panel = new LinearLayout(getContext());
        panel.setOrientation(VERTICAL); panel.setPadding(dp(16), dp(8), dp(16), dp(8));
        panel.setBackgroundColor(0xff151b23);
        Spinner duration = choice(panel, R.string.diag_history_setting,
                new String[]{"30 s", "60 s", "180 s", "300 s"}, selected(HISTORY_SECONDS, historySeconds));
        Spinner columns = choice(panel, R.string.diag_columns,
                getResources().getStringArray(R.array.diag_column_options), Math.max(0, Math.min(3, prefs.getInt("columns", 0))));
        Spinner refresh = choice(panel, R.string.diag_refresh_setting,
                new String[]{"100 ms", "250 ms", "500 ms", "1000 ms", "2000 ms"}, selected(REFRESH_MS, refreshMs));
        CheckBox brief = new CheckBox(getContext()); brief.setText(R.string.diag_compact_setting);
        brief.setTextColor(Color.WHITE); brief.setChecked(compact); panel.addView(brief);
        TextView opacityLabel = label(14); opacityLabel.setText(R.string.diag_opacity_setting); panel.addView(opacityLabel);
        SeekBar transparency = new SeekBar(getContext()); transparency.setMax(70); transparency.setProgress(opacity - 30);
        panel.addView(transparency, new LayoutParams(-1, dp(48)));
        TextView chartLabel = label(14); chartLabel.setText(R.string.diag_chart_selection); panel.addView(chartLabel);
        String[] names = getResources().getStringArray(R.array.diag_chart_names);
        CheckBox[] enabled = new CheckBox[names.length];
        for (int i = 0; i < names.length; i++) {
            enabled[i] = new CheckBox(getContext()); enabled[i].setText(names[i]);
            enabled[i].setTextColor(Color.WHITE); enabled[i].setChecked((chartMask & (1 << i)) != 0);
            panel.addView(enabled[i]);
        }
        scroll.addView(panel);
        settings = new AlertDialog.Builder(getContext()).setTitle(R.string.diag_settings)
                .setView(scroll).setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.diag_defaults, (d, w) -> {
                    prefs.edit().clear().apply(); setX(dp(10)); setY(dp(10)); applySettings();
                })
                .setPositiveButton(R.string.diag_apply, (d, w) -> {
                    int mask = 0;
                    for (int i = 0; i < enabled.length; i++) if (enabled[i].isChecked()) mask |= 1 << i;
                    prefs.edit().putInt("history", HISTORY_SECONDS[duration.getSelectedItemPosition()])
                            .putInt("refresh_v2", REFRESH_MS[refresh.getSelectedItemPosition()])
                            .putInt("charts", mask).putBoolean("touch_charts_v1", true).putBoolean("network_charts_v1", true).putInt("opacity", transparency.getProgress() + 30)
                            .putBoolean("compact", brief.isChecked()).putInt("columns", columns.getSelectedItemPosition()).apply();
                    applySettings();
                }).create();
        showImmersive(settings);
    }

    private void applySettings() {
        loadSettings(); updateText(); hudNetwork.invalidate(); hudInput.invalidate();
        if (dashboard != null && dashboard.isShowing()) {
            boolean wasPaused = paused;
            dashboard.dismiss(); showDashboard();
            paused = wasPaused;
            pause.setText(paused ? R.string.diag_resume : R.string.diag_pause);
        }
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = Math.min(MeasureSpec.getSize(widthSpec), dp(390));
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), heightSpec);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        activityRoot = getRootView(); activityRoot.addOnLayoutChangeListener(rootLayoutListener);
        post(() -> restoreHudPosition());
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        post(() -> restoreHudPosition());
    }

    @Override
    protected void onDetachedFromWindow() {
        if (activityRoot != null) activityRoot.removeOnLayoutChangeListener(rootLayoutListener);
        if (optionPicker != null) optionPicker.dismiss();
        if (settings != null) settings.dismiss();
        if (dashboard != null) dashboard.dismiss();
        super.onDetachedFromWindow();
    }

    private final class Chart extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int group;
        private final boolean small;
        private final int[] colors = {0xff63d9ff, 0xffffcf66, 0xffb2f284};
        private final String[] titles, labels;

        Chart(Context context, int group, boolean small) {
            super(context); this.group = group; this.small = small;
            titles = getResources().getStringArray(R.array.diag_chart_names);
            setContentDescription(titles[group]);
            labels = getResources().getStringArray(R.array.diag_series_names);
            paint.setStrokeWidth(dp(1));
        }

        private String number(float value) {
            return DiagnosticCharts.formatValue(value, Locale.getDefault());
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int[] series = DiagnosticCharts.GROUPS[group];
            long end = history.size() == 0 ? SystemClock.uptimeMillis() : history.time(history.size() - 1);
            long start = end - historySeconds * 1000L;
            float lower = group == 27 ? -100 : 0;
            float scale = group == 27 ? 0 : DiagnosticCharts.upperBound(DiagnosticCharts.maximum(history, series, start));
            float left = dp(small ? 34 : 38), right = getWidth() - dp(9);
            float top = dp(small ? 26 : 38+series.length*11), bottom = getHeight()-dp(small ? 13 : 23);
            String unit = DiagnosticCharts.UNITS[group];
            if (unit.equals("count")) unit = getContext().getString(R.string.diag_unit_count);
            paint.setColor(0xff1c2733);
            canvas.drawRoundRect(dp(1), dp(3), getWidth()-dp(1), getHeight()-dp(3), dp(7), dp(7), paint);
            paint.setTextSize(dp(small ? 9 : 10)); paint.setColor(0xffe8eef5);
            float available = right-dp(38)-dp(9);
            int titleChars = paint.breakText(titles[group], true, Math.max(0, available), null);
            String title = titleChars < titles[group].length() ? titles[group].substring(0, Math.max(0, titleChars-1))+"…" : titles[group];
            canvas.drawText(title, dp(9), dp(small ? 12 : 17), paint);
            paint.setColor(0xff9aabba); paint.setTextSize(dp(small ? 7 : 8));
            canvas.drawText(unit, right-paint.measureText(unit), dp(small ? 12 : 17), paint);
            for (int j = 0; j < series.length; j++) {
                float value = history.size() == 0 ? Float.NaN : history.get(series[j], history.size()-1);
                float x = small ? dp(10)+j*(getWidth()-dp(20))/series.length : dp(10);
                float y = dp(small ? 22 : 32+j*11);
                paint.setColor(colors[j]); canvas.drawCircle(x+dp(2), y-dp(3), dp(small ? 1.5f : 2), paint);
                paint.setTextSize(dp(small ? 7 : 8));
                String valueText = number(value);
                if (small) canvas.drawText(labels[series[j]]+" "+valueText, x+dp(8), y, paint);
                else {
                    float valueX = right-paint.measureText(valueText);
                    String label = labels[series[j]];
                    int chars = paint.breakText(label, true, Math.max(0, valueX-x-dp(16)), null);
                    if (chars < label.length()) label = label.substring(0, Math.max(0, chars-1))+"…";
                    canvas.drawText(label, x+dp(8), y, paint); canvas.drawText(valueText, valueX, y, paint);
                }
            }
            paint.setStrokeWidth(dp(1));
            for (int i = 0; i <= 2; i++) {
                float y = bottom - (bottom-top)*i/2;
                paint.setColor(0xff34414f);
                canvas.drawLine(left, y, right, y, paint);
                paint.setColor(0xffa6b4c1); paint.setTextSize(dp(small ? 7 : 8));
                String tick = number(lower+(scale-lower)*i/2);
                canvas.drawText(tick, left-dp(5)-paint.measureText(tick), y+dp(3), paint);
            }
            paint.setColor(0xff657686);
            canvas.drawLine(left, top, left, bottom, paint);
            canvas.drawLine(left, bottom, right, bottom, paint);
            paint.setStrokeWidth(density * (small ? 1.2f : 1.5f));
            for (int j = 0; j < series.length; j++) {
                paint.setColor(colors[j]); boolean valid = false; float px = 0, py = 0;
                for (int i = 0; i < history.size(); i++) {
                    long time = history.time(i);
                    if (time < start) continue;
                    float value = history.get(series[j], i);
                    if (Float.isNaN(value) || Float.isInfinite(value)) { valid = false; continue; }
                    float x = left + (time-start)*(right-left)/(historySeconds*1000L);
                    float y = bottom-Math.max(0, Math.min((value-lower)/(scale-lower), 1))*(bottom-top);
                    if (valid) canvas.drawLine(px, py, x, y, paint);
                    else canvas.drawCircle(x, y, density, paint);
                    px = x; py = y; valid = true;
                }
            }
            paint.setColor(0xff9aabba); paint.setTextSize(dp(small ? 7 : 8));
            float textY = getHeight()-dp(small ? 4 : 9);
            canvas.drawText("−"+historySeconds+" s", left, textY, paint);
            if (!small) {
                String middle = "−"+historySeconds/2+" s";
                canvas.drawText(middle, (left+right-paint.measureText(middle))/2, textY, paint);
            }
            String state = getContext().getString(paused ? R.string.diag_paused : R.string.diag_now);
            canvas.drawText(state, right-paint.measureText(state), textY, paint);
        }
    }
}
