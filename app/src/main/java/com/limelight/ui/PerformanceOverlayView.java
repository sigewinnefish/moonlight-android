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
import com.limelight.diagnostics.HudLayout;
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
import android.widget.PopupWindow;
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
    private final LinearLayout body, hudHeader;
    private final Button hudPassButton;
    private Button detailPassButton;
    private PopupWindow cancelPassPopup;
    private boolean hudPassThrough, detailPassThrough, hudPassBeforeDetail;
    private Runnable inputModeListener;
    private int panelOpacity;
    private boolean hudExpanded = true, hudSpaceLimited;
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
    private TextView detailText, emptyChartsNotice;
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
        hudHeader = header;
        TextView handle = label(16); handle.setText("⠿"); handle.setGravity(Gravity.CENTER);
        handle.setContentDescription(getContext().getString(R.string.diag_drag));
        header.addView(handle, new LayoutParams(dp(30), -1));
        installDrag(handle, false, false);
        button(header, R.string.diag_fold, v -> {
            hudExpanded = !hudExpanded;
            body.setVisibility(hudExpanded ? VISIBLE : GONE);
            ((Button)v).setText(hudExpanded ? R.string.diag_fold : R.string.diag_unfold);
            requestLayout();
        });
        button(header, R.string.diag_open_dashboard, v -> showDashboard());
        button(header, R.string.diag_settings, v -> showSettings());
        hudPassButton = button(header, R.string.diag_passthrough, v -> {
            if (detailPassThrough) { setDetailPassThrough(false); setHudPassThrough(false); }
            else setHudPassThrough(!hudPassThrough);
        });
        addView(header, new LayoutParams(-1, dp(48)));
        hudText = label(10);
        hudText.setIncludeFontPadding(false);
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
        b.setMaxLines(2);
        b.setEllipsize(android.text.TextUtils.TruncateAt.END);
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
        panelOpacity = Math.max(30, Math.min(100, prefs.getInt("panel_opacity", 90)));
        if (dashboard != null && dashboard.getWindow() != null) {
            WindowManager.LayoutParams p = dashboard.getWindow().getAttributes();
            p.alpha = panelOpacity / 100f; dashboard.getWindow().setAttributes(p);
        }
        compact = prefs.getBoolean("compact", true);
        hudText.setMaxLines(compact ? 4 : 10);
        hudText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        setBackgroundColor(Color.argb(opacity * 255 / 100, 21, 27, 35));
    }

    public void setInputModeListener(Runnable listener) { inputModeListener = listener; }

    private void releaseRemoteTouches() {
        if (inputModeListener != null) inputModeListener.run();
    }

    private void setHudPassThrough(boolean enabled) {
        if (hudPassThrough == enabled) return;
        releaseRemoteTouches(); hudPassThrough = enabled;
        for (int i = 0; i < hudHeader.getChildCount(); i++) {
            View control = hudHeader.getChildAt(i);
            if (control == hudPassButton) continue;
            control.setEnabled(!enabled); control.setClickable(!enabled && control instanceof Button);
            control.setLongClickable(false);
        }
        hudPassButton.setText(enabled ? R.string.diag_cancel_passthrough : R.string.diag_passthrough);
    }

    private void setDetailPassThrough(boolean enabled) {
        if (detailPassThrough == enabled) return;
        releaseRemoteTouches(); detailPassThrough = enabled;
        // The small HUD may overlap the diagnostic window. Its old controls must
        // not intercept touches revealed by making the detailed window non-touchable.
        if (enabled) { hudPassBeforeDetail = hudPassThrough; setHudPassThrough(true); }
        else setHudPassThrough(hudPassBeforeDetail);
        if (!enabled) {
            PopupWindow popup = cancelPassPopup; cancelPassPopup = null;
            if (popup != null) popup.dismiss();
        }
        if (dashboard == null || !dashboard.isShowing() || dashboard.getWindow() == null) {
            detailPassThrough = false;
            if (enabled) setHudPassThrough(hudPassBeforeDetail);
            return;
        }
        Window window = dashboard.getWindow();
        int flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        if (enabled) {
            Button cancel = new Button(getContext()); cancel.setText(R.string.diag_cancel_passthrough);
            cancel.setTextSize(10); cancel.setAllCaps(false); cancel.setMaxLines(2);
            cancel.setPadding(dp(2), 0, dp(2), 0); cancel.setMinWidth(0); cancel.setMinimumWidth(0);
            cancel.setOnClickListener(v -> setDetailPassThrough(false));
            cancel.setSystemUiVisibility(window.getDecorView().getSystemUiVisibility());
            PopupWindow popup = new PopupWindow(cancel, Math.max(dp(96), detailPassButton.getWidth()), Math.max(dp(40), Math.max(detailPassButton.getHeight(), cancel.getLineHeight()*2+dp(4))), false);
            popup.setTouchable(true); popup.setOutsideTouchable(false); popup.setSplitTouchEnabled(true);
            popup.setBackgroundDrawable(new ColorDrawable(0xff24394f)); popup.setElevation(dp(4));
            popup.setOnDismissListener(() -> {
                if (cancelPassPopup == popup && detailPassThrough) setDetailPassThrough(false);
            });
            cancelPassPopup = popup;
            try {
                // A separate child window retains exactly one touch target while the
                // parent diagnostic window becomes genuinely non-touchable/non-focusable.
                popup.showAsDropDown(detailPassButton, 0, -detailPassButton.getHeight(), Gravity.START);
                window.addFlags(flags);
            }
            catch (WindowManager.BadTokenException e) {
                detailPassThrough = false; cancelPassPopup = null; popup.dismiss();
                setHudPassThrough(hudPassBeforeDetail);
                window.clearFlags(flags);
            }
        }
        else { window.clearFlags(flags); immersive(dashboard); }
        if (detailPassButton != null) detailPassButton.setText(detailPassThrough ? R.string.diag_cancel_passthrough : R.string.diag_passthrough);
    }

    public boolean cancelPassThrough() {
        boolean active = detailPassThrough || hudPassThrough;
        if (detailPassThrough) setDetailPassThrough(false);
        if (hudPassThrough) setHudPassThrough(false);
        return active;
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
        String text;
        if (compact && latest != null) {
            text = getContext().getString(R.string.diag_hud_summary,
                    latest[DiagnosticCharts.RTT], latest[DiagnosticCharts.RENDER_FPS],
                    latest[DiagnosticCharts.INPUT_P95], latest[DiagnosticCharts.INPUT_PEAK],
                    latest[DiagnosticCharts.SUBMIT_PEAK], (int)latest[DiagnosticCharts.ERRORS]);
        }
        else text = latestText;
        if (hudSpaceLimited) text = getContext().getString(R.string.diag_hud_space_limited, text);
        hudText.setText(text);
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

    private View hudContainer() { return getParent() instanceof View ? (View)getParent() : getRootView(); }

    private int hudViewportWidth() {
        View parent = hudContainer();
        return Math.max(1, parent.getWidth()-parent.getPaddingLeft()-parent.getPaddingRight());
    }

    private int hudViewportHeight() {
        View parent = hudContainer();
        return Math.max(1, parent.getHeight()-parent.getPaddingTop()-parent.getPaddingBottom());
    }

    private void placeHud(float x, float y) {
        View parent = hudContainer();
        int[] rect = PanelGeometry.fit(hudViewportWidth(), hudViewportHeight(),
                Math.round(x)-parent.getPaddingLeft(), Math.round(y)-parent.getPaddingTop(),
                getWidth(), getHeight(), 1, 1);
        setX(rect[0]+parent.getPaddingLeft()); setY(rect[1]+parent.getPaddingTop());
    }

    private void restoreHudPosition() {
        if (getWidth() == 0 || getHeight() == 0) return;
        View parent = hudContainer();
        float x = prefs.contains("hud_x") ? parent.getPaddingLeft()+prefs.getFloat("hud_x", 0)*Math.max(0, hudViewportWidth()-getWidth()) : getX();
        float y = prefs.contains("hud_y") ? parent.getPaddingTop()+prefs.getFloat("hud_y", 0)*Math.max(0, hudViewportHeight()-getHeight()) : getY();
        placeHud(x, y);
    }

    private void saveHudPosition() {
        View parent = hudContainer();
        prefs.edit().putFloat("hud_x", (getX()-parent.getPaddingLeft())/Math.max(1, hudViewportWidth()-getWidth()))
                .putFloat("hud_y", (getY()-parent.getPaddingTop())/Math.max(1, hudViewportHeight()-getHeight())).apply();
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
        if (detailPassThrough && detailPassButton != null) detailPassButton.post(() -> {
            if (cancelPassPopup != null && cancelPassPopup.isShowing() && detailPassButton != null) {
                cancelPassPopup.update(detailPassButton, 0, -detailPassButton.getHeight(), Math.max(dp(96), detailPassButton.getWidth()), Math.max(dp(40), Math.max(detailPassButton.getHeight(), ((TextView)cancelPassPopup.getContentView()).getLineHeight()*2+dp(4))));
            }
        });
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
                if (!window && hudPassThrough) return false;
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
                        placeHud(x+dx, y+dy);
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
        if (detailPassThrough) setDetailPassThrough(false);
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
        title.addView(close, new LayoutParams(dp(40), -1)); content.addView(title, new LayoutParams(-1, dp(34)));
        LinearLayout tools = new LinearLayout(getContext());
        pause = button(tools, paused ? R.string.diag_resume : R.string.diag_pause, v -> togglePause());
        button(tools, R.string.diag_reset, v -> {
            history.clear(); lastUpdateMs = 0;
            for (Chart chart : detailCharts) chart.invalidate();
            hudNetwork.invalidate(); hudInput.invalidate();
        });
        button(tools, R.string.diag_settings, v -> showSettings());
        detailPassButton = button(tools, R.string.diag_passthrough, v -> setDetailPassThrough(!detailPassThrough));
        button(tools, R.string.diag_detail_text, v -> {
            if (detailText != null) detailText.setVisibility(detailText.getVisibility() == VISIBLE ? GONE : VISIBLE);
        });
        for (int i = 0; i < tools.getChildCount(); i++) ((Button)tools.getChildAt(i)).setTextSize(10);
        content.addView(tools, new LayoutParams(-1, dp(36)));
        ScrollView scroll = new ScrollView(getContext());
        LinearLayout rows = new LinearLayout(getContext()); rows.setOrientation(VERTICAL);
        detailText = label(10); detailText.setText(latestText); detailText.setVisibility(GONE); rows.addView(detailText);
        emptyChartsNotice = label(10); emptyChartsNotice.setText(R.string.diag_no_charts); rows.addView(emptyChartsNotice);
        dashboardGrid = new GridLayout(getContext()); rows.addView(dashboardGrid, new LayoutParams(-1, -2));
        dashboardColumns = 0; detailCharts.clear();
        for (int group = 0; group < DiagnosticCharts.GROUPS.length; group++) {
            if ((chartMask & (1 << group)) != 0) detailCharts.add(new Chart(getContext(), group, false));
        }
        emptyChartsNotice.setVisibility(detailCharts.isEmpty() ? VISIBLE : GONE);
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
            WindowManager.LayoutParams p = dashboard.getWindow().getAttributes();
            p.alpha = panelOpacity / 100f; dashboard.getWindow().setAttributes(p);
        }
        Dialog opened = dashboard;
        close.setOnClickListener(v -> opened.dismiss());
        dashboard.setOnDismissListener(d -> {
            // Dialog dismissal callbacks are queued. An older window must not clear
            // references belonging to a newer window opened before this callback ran.
            if (dashboard != opened) return;
            boolean wasPassing = detailPassThrough;
            detailPassThrough = false;
            if (wasPassing) setHudPassThrough(hudPassBeforeDetail);
            if (cancelPassPopup != null) { cancelPassPopup.dismiss(); cancelPassPopup = null; }
            detailPassButton = null;
            detailCharts.clear(); detailText = null; emptyChartsNotice = null; pause = null; dashboardGrid = null; paused = false;
            dashboard = null;
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
        if (detailPassThrough) setDetailPassThrough(false);
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
        bindOpacityLabel(transparency, opacityLabel, R.string.diag_opacity_value);
        TextView panelOpacityLabel = label(14); panelOpacityLabel.setText(R.string.diag_panel_opacity); panel.addView(panelOpacityLabel);
        SeekBar panelTransparency = new SeekBar(getContext()); panelTransparency.setMax(70); panelTransparency.setProgress(panelOpacity-30);
        panel.addView(panelTransparency, new LayoutParams(-1, dp(48)));
        bindOpacityLabel(panelTransparency, panelOpacityLabel, R.string.diag_panel_opacity_value);
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
                    prefs.edit().clear().apply();
                    placeHud(hudContainer().getPaddingLeft()+dp(10), hudContainer().getPaddingTop()+dp(10));
                    applySettings();
                })
                .setPositiveButton(R.string.diag_apply, (d, w) -> {
                    int mask = 0;
                    for (int i = 0; i < enabled.length; i++) if (enabled[i].isChecked()) mask |= 1 << i;
                    prefs.edit().putInt("history", HISTORY_SECONDS[duration.getSelectedItemPosition()])
                            .putInt("refresh_v2", REFRESH_MS[refresh.getSelectedItemPosition()])
                            .putInt("charts", mask).putBoolean("touch_charts_v1", true).putBoolean("network_charts_v1", true).putInt("opacity", transparency.getProgress() + 30)
                            .putBoolean("compact", brief.isChecked()).putInt("panel_opacity", panelTransparency.getProgress()+30)
                            .putInt("columns", columns.getSelectedItemPosition()).apply();
                    applySettings();
                }).create();
        showImmersive(settings);
    }

    private void bindOpacityLabel(SeekBar slider, TextView label, int resource) {
        label.setText(getContext().getString(resource, slider.getProgress()+30));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                label.setText(getContext().getString(resource, progress+30));
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
    }

    private void applySettings() {
        loadSettings(); updateText(); hudNetwork.invalidate(); hudInput.invalidate();
        if (dashboard != null && dashboard.isShowing() && dashboardGrid != null) {
            // Update in place: recreating the dialog races its asynchronous onDismiss
            // callback and also needlessly loses the window geometry and scroll state.
            dashboardGrid.removeAllViews(); detailCharts.clear(); dashboardColumns = 0;
            for (int group = 0; group < DiagnosticCharts.GROUPS.length; group++) {
                if ((chartMask & (1 << group)) != 0) detailCharts.add(new Chart(getContext(), group, false));
            }
            if (emptyChartsNotice != null) emptyChartsNotice.setVisibility(detailCharts.isEmpty() ? VISIBLE : GONE);
            reflowCharts();
            dashboardGrid.post(() -> reflowCharts());
        }
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED ? dp(390) :
                Math.min(MeasureSpec.getSize(widthSpec), dp(390));
        int height = MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED ? dp(2000) : MeasureSpec.getSize(heightSpec);
        int line = Math.max(1, hudText.getLineHeight()+dp(1));
        int header = dp(48);
        for (int i = 0; i < hudHeader.getChildCount(); i++) {
            View child = hudHeader.getChildAt(i);
            if (child instanceof Button) header = Math.max(header, ((Button)child).getLineHeight()*2+dp(4));
        }
        int[] plan = HudLayout.plan(height, getPaddingTop()+getPaddingBottom()+dp(4), header, line,
                compact ? 4 : 10, dp(76), dp(60));
        ViewGroup.LayoutParams headerParams = hudHeader.getLayoutParams(); headerParams.height = plan[0];
        body.setVisibility(hudExpanded && plan[1] > 0 ? VISIBLE : GONE);
        if (hudText.getMaxLines() != Math.max(1, plan[1])) hudText.setMaxLines(Math.max(1, plan[1]));
        boolean limited = plan[2] < 2;
        if (limited != hudSpaceLimited) { hudSpaceLimited = limited; updateText(); }
        hudNetwork.setVisibility(plan[2] >= 1 ? VISIBLE : GONE);
        hudInput.setVisibility(plan[2] >= 2 ? VISIBLE : GONE);
        hudNetwork.getLayoutParams().height = plan[3]; hudInput.getLayoutParams().height = plan[3];
        // Exact width prevents weighted header buttons/graphs shrinking independently.
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightSpec);
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
        // Folding or changing text height should clamp the current location, not jump
        // to a different saved fraction of the now-larger free space.
        post(() -> placeHud(getX(), getY()));
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelPassThrough();
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
            paint.setColor(small ? Color.TRANSPARENT : 0xff1c2733);
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
