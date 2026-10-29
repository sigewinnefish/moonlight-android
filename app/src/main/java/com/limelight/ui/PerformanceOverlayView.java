package com.limelight.ui;

import android.app.AlertDialog;
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
    private AlertDialog dashboard, settings;
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
        chartMask = prefs.getInt("charts", (1 << DiagnosticCharts.GROUPS.length) - 1);
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

    private void showDashboard() {
        if (dashboard != null && dashboard.isShowing()) return;
        LinearLayout content = new LinearLayout(getContext());
        content.setOrientation(VERTICAL);
        content.setPadding(dp(12), dp(4), dp(12), dp(4));
        content.setBackgroundColor(0xff151b23);
        LinearLayout tools = new LinearLayout(getContext());
        pause = button(tools, paused ? R.string.diag_resume : R.string.diag_pause, v -> togglePause());
        button(tools, R.string.diag_reset, v -> {
            history.clear();
            lastUpdateMs = 0;
            for (Chart chart : detailCharts) chart.invalidate();
            hudNetwork.invalidate(); hudInput.invalidate();
        });
        button(tools, R.string.diag_settings, v -> showSettings());
        content.addView(tools, new LayoutParams(-1, dp(42)));
        ScrollView scroll = new ScrollView(getContext());
        LinearLayout rows = new LinearLayout(getContext());
        rows.setOrientation(VERTICAL);
        TextView help = label(12);
        help.setText(R.string.diag_dashboard_help);
        rows.addView(help);
        detailText = label(12);
        detailText.setText(latestText);
        rows.addView(detailText);
        detailCharts.clear();
        for (int group = 0; group < DiagnosticCharts.GROUPS.length; group++) {
            if ((chartMask & (1 << group)) == 0) continue;
            Chart chart = new Chart(getContext(), group, false);
            detailCharts.add(chart);
            rows.addView(chart, new LayoutParams(-1, dp(164)));
        }
        scroll.addView(rows);
        content.addView(scroll, new LayoutParams(-1, 0, 1));
        dashboard = new AlertDialog.Builder(getContext()).setTitle(R.string.diag_dashboard_title)
                .setView(content).setPositiveButton(R.string.diag_close, (d, w) -> {}).create();
        dashboard.setOnDismissListener(d -> {
            detailCharts.clear(); detailText = null; pause = null;
            // Closing a frozen dashboard must not leave live HUD sampling paused invisibly.
            paused = false;
        });
        dashboard.show();
        if (dashboard.getWindow() != null) {
            dashboard.getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT);
        }
    }

    private Spinner choice(LinearLayout parent, int title, String[] items, int selected) {
        TextView text = label(14); text.setText(title); parent.addView(text);
        Spinner spinner = new Spinner(getContext());
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
                    prefs.edit().clear().apply(); applySettings();
                })
                .setPositiveButton(R.string.diag_apply, (d, w) -> {
                    int mask = 0;
                    for (int i = 0; i < enabled.length; i++) if (enabled[i].isChecked()) mask |= 1 << i;
                    prefs.edit().putInt("history", HISTORY_SECONDS[duration.getSelectedItemPosition()])
                            .putInt("refresh_v2", REFRESH_MS[refresh.getSelectedItemPosition()])
                            .putInt("charts", mask).putInt("opacity", transparency.getProgress() + 30)
                            .putBoolean("compact", brief.isChecked()).apply();
                    applySettings();
                }).create();
        settings.show();
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
    protected void onDetachedFromWindow() {
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
            labels = getResources().getStringArray(R.array.diag_series_names);
            paint.setStrokeWidth(dp(1));
        }

        private String number(float value) {
            if (Float.isNaN(value) || Float.isInfinite(value)) return "—";
            return String.format(Locale.getDefault(), value >= 100 ? "%.0f" : value >= 10 ? "%.1f" : "%.2f", value);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int[] series = DiagnosticCharts.GROUPS[group];
            long end = history.size() == 0 ? SystemClock.uptimeMillis() : history.time(history.size() - 1);
            long start = end - historySeconds * 1000L;
            float scale = DiagnosticCharts.upperBound(DiagnosticCharts.maximum(history, series, start));
            float left = dp(small ? 34 : 48), right = getWidth() - dp(12);
            float top = dp(small ? 26 : 62), bottom = getHeight() - dp(small ? 13 : 26);
            String unit = group == 4 ? "FPS" : group == 6 ? "Mbps" : group == 7 ? "%" : group == 9 ? getContext().getString(R.string.diag_unit_count) : "ms";
            paint.setColor(0xff1c2733);
            canvas.drawRoundRect(dp(1), dp(3), getWidth()-dp(1), getHeight()-dp(3), dp(7), dp(7), paint);
            paint.setTextSize(dp(small ? 9 : 12)); paint.setColor(0xffe8eef5);
            canvas.drawText(titles[group], dp(9), dp(small ? 12 : 20), paint);
            paint.setColor(0xff9aabba); paint.setTextSize(dp(small ? 7 : 10));
            canvas.drawText(unit, right-paint.measureText(unit), dp(small ? 12 : 20), paint);
            for (int j = 0; j < series.length; j++) {
                float value = history.size() == 0 ? Float.NaN : history.get(series[j], history.size()-1);
                float x = dp(10) + j * (getWidth()-dp(20)) / series.length;
                float y = dp(small ? 22 : 42);
                paint.setColor(colors[j]);
                canvas.drawCircle(x+dp(2), y-dp(3), dp(small ? 1.5f : 2.5f), paint);
                paint.setTextSize(dp(small ? 7 : 10));
                canvas.drawText(labels[series[j]] + " " + number(value), x+dp(8), y, paint);
            }
            paint.setStrokeWidth(dp(1));
            for (int i = 0; i <= 2; i++) {
                float y = bottom - (bottom-top)*i/2;
                paint.setColor(0xff34414f);
                canvas.drawLine(left, y, right, y, paint);
                paint.setColor(0xffa6b4c1); paint.setTextSize(dp(small ? 7 : 10));
                String tick = number(scale*i/2);
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
                    float y = bottom-Math.max(0, Math.min(value/scale, 1))*(bottom-top);
                    if (valid) canvas.drawLine(px, py, x, y, paint);
                    else canvas.drawCircle(x, y, density, paint);
                    px = x; py = y; valid = true;
                }
            }
            paint.setColor(0xff9aabba); paint.setTextSize(dp(small ? 7 : 10));
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
