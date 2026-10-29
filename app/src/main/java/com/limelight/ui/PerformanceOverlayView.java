package com.limelight.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.widget.TextView;
import com.limelight.R;
import com.limelight.diagnostics.TrendHistory;
import java.util.Locale;

/** Non-interactive overlay: touches continue to the video behind it. */
public final class PerformanceOverlayView extends TextView {
    private final TrendHistory history = new TrendHistory(5);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int[] colors = {0xff63d9ff, 0xffffcf66, 0xffff7885, 0xffb2f284, 0xffcf9cff};
    private final float density;
    private long lastUpdateMs;

    public PerformanceOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        setTextSize(10);
        setTextColor(Color.WHITE);
        setBackgroundColor(0xc0151b23);
        setPadding((int)(8*density), (int)(6*density), (int)(8*density), (int)(112*density));
        setMaxWidth((int)(390*density));
        setClickable(false);
        setFocusable(false);
        paint.setStrokeWidth(1.3f*density);
    }

    public void update(String text, float rtt, float jitter, float frameGap, float inputAge, float submit) {
        long now = android.os.SystemClock.uptimeMillis();
        if (lastUpdateMs != 0) {
            int missing = (int)Math.min(59, (now - lastUpdateMs) / 1000 - 1);
            for (int i = 0; i < missing; i++) {
                history.add(Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN);
            }
        }
        lastUpdateMs = now;
        history.add(rtt, jitter, frameGap, inputAge, submit);
        setText(text);
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = Math.min(MeasureSpec.getSize(widthSpec), (int)(390*density));
        int availableHeight = MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED
                ? Integer.MAX_VALUE : MeasureSpec.getSize(heightSpec);
        // Keep the plots separate from the text even on landscape phones/large font settings.
        for (int sp = 10; sp >= 6; sp--) {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
            super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), heightSpec);
            if (getLayout() == null || getLayout().getHeight() + getPaddingTop() + getPaddingBottom() <= availableHeight) break;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float top = getHeight() - 106*density;
        drawChart(canvas, top, 0, 3, getContext().getString(R.string.diag_chart_network));
        drawChart(canvas, top + 52*density, 3, 5, getContext().getString(R.string.diag_chart_input));
    }

    private void drawChart(Canvas canvas, float top, int first, int end, String title) {
        float left = getPaddingLeft();
        float right = getWidth() - getPaddingRight();
        float scale = history.scale(first, end);
        paint.setTextSize(8*density);
        paint.setColor(Color.LTGRAY);
        canvas.drawText(title + String.format(Locale.getDefault(), "  0–%.0f ms", scale), left, top + 9*density, paint);
        float bottom = top + 43*density;
        float height = 27*density;
        paint.setColor(0xff68717b);
        canvas.drawLine(left, bottom, right, bottom, paint);
        for (int s = first; s < end; s++) {
            paint.setColor(colors[s]);
            boolean previousValid = false;
            float px = 0, py = 0;
            for (int i = 0; i < history.size(); i++) {
                float v = history.get(s, i);
                if (Float.isNaN(v) || Float.isInfinite(v)) { previousValid = false; continue; }
                float x = right - (history.size()-1-i) * (right-left)/(TrendHistory.CAPACITY-1);
                float y = bottom - Math.min(v/scale, 1)*height;
                if (previousValid) canvas.drawLine(px, py, x, y, paint);
                else canvas.drawCircle(x, y, density, paint);
                px = x; py = y; previousValid = true;
            }
        }
    }
}
