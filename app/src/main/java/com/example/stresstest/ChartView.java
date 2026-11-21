package com.example.stresstest;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import java.util.Locale;

/** Line chart of the last 120 one-second samples (2 minutes) for 3 series. */
public class ChartView extends View {

    public static final int MAX_POINTS = 120;
    private static final int SERIES = 3;
    private static final int[] COLORS = {0xFFFF5252, 0xFF40C4FF, 0xFF69F0AE};
    private static final String[] X_LABELS = {"-2:00", "-1:30", "-1:00", "-0:30", "now"};

    private final float[][] data = new float[SERIES][MAX_POINTS];
    private int count = 0;

    private final Paint bgPaint = new Paint();
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint yText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint xText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float density;

    public ChartView(Context context) {
        this(context, null);
    }

    public ChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        bgPaint.setColor(0xFF141B22);
        gridPaint.setColor(0xFF2A3541);
        gridPaint.setStrokeWidth(1f * density);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(2.2f * density);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        yText.setColor(0xFF8FA3B5);
        yText.setTextSize(10f * density);
        yText.setTextAlign(Paint.Align.RIGHT);
        xText.setColor(0xFF8FA3B5);
        xText.setTextSize(10f * density);
        xText.setTextAlign(Paint.Align.CENTER);
        for (int s = 0; s < SERIES; s++) {
            for (int k = 0; k < MAX_POINTS; k++) data[s][k] = Float.NaN;
        }
    }

    /** Adds one sample for each series (NaN = no data). Newest goes on the right. */
    public void push(float cpu, float gpu, float battery) {
        float[] v = {cpu, gpu, battery};
        for (int s = 0; s < SERIES; s++) {
            System.arraycopy(data[s], 1, data[s], 0, MAX_POINTS - 1);
            data[s][MAX_POINTS - 1] = v[s];
        }
        if (count < MAX_POINTS) count++;
        invalidate();
    }

    /** {min, avg, max} of the stored samples for a series, or null if no data. */
    public float[] stats(int series) {
        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE, sum = 0;
        int n = 0;
        for (int k = MAX_POINTS - count; k < MAX_POINTS; k++) {
            float v = data[series][k];
            if (Float.isNaN(v)) continue;
            if (v < min) min = v;
            if (v > max) max = v;
            sum += v;
            n++;
        }
        if (n == 0) return null;
        return new float[]{min, sum / n, max};
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth();
        float h = getHeight();
        c.drawRect(0, 0, w, h, bgPaint);

        float left = 40f * density;
        float top = 10f * density;
        float right = w - 10f * density;
        float bottom = h - 22f * density;

        // Work out the visible temperature range.
        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        for (int s = 0; s < SERIES; s++) {
            for (int k = MAX_POINTS - count; k < MAX_POINTS; k++) {
                float v = data[s][k];
                if (Float.isNaN(v)) continue;
                if (v < min) min = v;
                if (v > max) max = v;
            }
        }
        float lo, hi;
        if (min > max) {
            lo = 20;
            hi = 60;
        } else {
            float rawLo = (float) Math.floor(min - 2);
            float rawHi = (float) Math.ceil(max + 2);
            float span = Math.max(8f, rawHi - rawLo);
            span = (float) (Math.ceil(span / 4f) * 4f); // whole-degree grid steps
            float mid = (rawLo + rawHi) / 2f;
            lo = (float) Math.floor(mid - span / 2f);
            hi = lo + span;
        }

        // Grid + axis labels.
        for (int i = 0; i <= 4; i++) {
            float val = lo + (hi - lo) * i / 4f;
            float y = bottom - (val - lo) / (hi - lo) * (bottom - top);
            c.drawLine(left, y, right, y, gridPaint);
            c.drawText(String.format(Locale.US, "%.0f°", val), left - 4f * density, y + 4f * density, yText);
        }
        for (int i = 0; i <= 4; i++) {
            float x = left + (right - left) * i / 4f;
            c.drawLine(x, top, x, bottom, gridPaint);
            c.drawText(X_LABELS[i], x, h - 6f * density, xText);
        }

        // Lines (a gap in the data breaks the line).
        for (int s = 0; s < SERIES; s++) {
            linePaint.setColor(COLORS[s]);
            dotPaint.setColor(COLORS[s]);
            path.reset();
            boolean pen = false;
            float lastX = 0, lastY = 0;
            boolean hasLast = false;
            for (int k = MAX_POINTS - count; k < MAX_POINTS; k++) {
                float v = data[s][k];
                if (Float.isNaN(v)) {
                    pen = false;
                    hasLast = false;
                    continue;
                }
                float x = left + (right - left) * k / (MAX_POINTS - 1f);
                float y = bottom - (v - lo) / (hi - lo) * (bottom - top);
                if (!pen) {
                    path.moveTo(x, y);
                    pen = true;
                } else {
                    path.lineTo(x, y);
                }
                lastX = x;
                lastY = y;
                hasLast = true;
            }
            c.drawPath(path, linePaint);
            if (hasLast) c.drawCircle(lastX, lastY, 3.5f * density, dotPaint);
        }
    }
}
