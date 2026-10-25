package com.nexora.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import com.google.android.material.color.MaterialColors;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One lightweight chart view for every visual in the app (replaces a charting library):
 * progress ring, horizontal bars (funnel / per drive), vertical bars and a line chart.
 * Colours come from the theme so light and dark modes both work.
 */
public class ChartView extends View {
    private enum Mode { RING, HBARS, VBARS, LINE }

    private Mode mode = Mode.RING;
    private final List<String> labels = new ArrayList<>();
    private final List<Integer> values = new ArrayList<>();
    private int ringValue;
    private String ringCaption;
    private Integer ringColor;
    private float progress = 1f;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float density;

    public ChartView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    /** Progress ring 0–100 with the value in the centre. */
    public void setRing(int value, String caption, @Nullable Integer color) {
        mode = Mode.RING;
        ringValue = Math.max(0, Math.min(100, value));
        ringCaption = caption;
        ringColor = color;
        setContentDescription((caption == null ? "" : caption + ": ") + ringValue + " percent");
        animateIn();
    }

    public void setHorizontalBars(List<String> l, List<Integer> v) { setSeries(Mode.HBARS, l, v); }
    public void setVerticalBars(List<String> l, List<Integer> v) { setSeries(Mode.VBARS, l, v); }
    public void setLine(List<String> l, List<Integer> v) { setSeries(Mode.LINE, l, v); }

    private void setSeries(Mode m, List<String> l, List<Integer> v) {
        mode = m;
        labels.clear();
        labels.addAll(l);
        values.clear();
        values.addAll(v);
        StringBuilder sb = new StringBuilder(m == Mode.LINE ? "Line chart. " : "Bar chart. ");
        for (int i = 0; i < l.size(); i++) sb.append(l.get(i)).append(": ").append(v.get(i)).append(". ");
        if (l.isEmpty()) sb.append("No data yet.");
        setContentDescription(sb.toString());
        requestLayout();
        animateIn();
    }

    private void animateIn() {
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(600);
        a.addUpdateListener(x -> { progress = (float) x.getAnimatedValue(); invalidate(); });
        a.start();
    }

    private float dp(float v) { return v * density; }

    private int attr(int attr) { return MaterialColors.getColor(this, attr); }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        if (mode == Mode.HBARS && MeasureSpec.getMode(heightSpec) != MeasureSpec.EXACTLY) {
            int rows = Math.max(1, labels.size());
            int h = (int) (rows * dp(44)) + getPaddingTop() + getPaddingBottom();
            setMeasuredDimension(MeasureSpec.getSize(widthSpec), h);
            return;
        }
        super.onMeasure(widthSpec, heightSpec);
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        int primary = attr(androidx.appcompat.R.attr.colorPrimary);
        int track = attr(com.google.android.material.R.attr.colorSurfaceContainerHigh);
        int onSurface = attr(com.google.android.material.R.attr.colorOnSurface);
        int muted = attr(com.google.android.material.R.attr.colorOnSurfaceVariant);
        switch (mode) {
            case RING: drawRing(c, ringColor == null ? primary : ringColor, track, onSurface, muted); break;
            case HBARS: drawHBars(c, primary, track, onSurface, muted); break;
            case VBARS: drawVBars(c, primary, track, muted); break;
            default: drawLine(c, primary, track, muted); break;
        }
    }

    private void drawRing(Canvas c, int color, int track, int onSurface, int muted) {
        float size = Math.min(getWidth(), getHeight());
        float sw = size * 0.1f;
        float cx = getWidth() / 2f, cy = getHeight() / 2f, r = size / 2f - sw / 2f;
        rect.set(cx - r, cy - r, cx + r, cy + r);
        stroke.setStrokeWidth(sw);
        stroke.setColor(track);
        c.drawArc(rect, 0, 360, false, stroke);
        stroke.setColor(color);
        c.drawArc(rect, -90, 360f * ringValue / 100f * progress, false, stroke);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(onSurface);
        text.setFakeBoldText(true);
        text.setTextSize(size * 0.26f);
        float y = ringCaption == null ? cy - (text.descent() + text.ascent()) / 2 : cy + size * 0.04f;
        c.drawText(Math.round(ringValue * progress) + "%", cx, y, text);
        if (ringCaption != null) {
            text.setFakeBoldText(false);
            text.setColor(muted);
            text.setTextSize(size * 0.11f);
            c.drawText(ringCaption, cx, y + size * 0.16f, text);
        }
    }

    private void drawHBars(Canvas c, int primary, int track, int onSurface, int muted) {
        if (empty(c, muted)) return;
        int max = Math.max(1, max());
        float rowH = (getHeight() - getPaddingTop() - getPaddingBottom()) / (float) labels.size();
        float barH = dp(10);
        for (int i = 0; i < labels.size(); i++) {
            float top = getPaddingTop() + i * rowH;
            text.setTextAlign(Paint.Align.LEFT);
            text.setFakeBoldText(false);
            text.setTextSize(dp(12.5f));
            text.setColor(onSurface);
            String label = ellipsize(labels.get(i), getWidth() - dp(48));
            c.drawText(label, 0, top + dp(16), text);
            text.setTextAlign(Paint.Align.RIGHT);
            text.setFakeBoldText(true);
            c.drawText(String.valueOf(values.get(i)), getWidth(), top + dp(16), text);
            float barTop = top + dp(24);
            rect.set(0, barTop, getWidth(), barTop + barH);
            fill.setColor(track);
            c.drawRoundRect(rect, barH / 2, barH / 2, fill);
            float w = getWidth() * values.get(i) / (float) max * progress;
            if (w > 0) {
                rect.set(0, barTop, Math.max(w, barH), barTop + barH);
                fill.setColor(primary);
                c.drawRoundRect(rect, barH / 2, barH / 2, fill);
            }
        }
    }

    private void drawVBars(Canvas c, int primary, int track, int muted) {
        if (empty(c, muted)) return;
        int max = Math.max(1, max());
        float labelH = dp(36), top = dp(20);
        float chartH = getHeight() - labelH - top;
        float slot = getWidth() / (float) labels.size();
        float barW = Math.min(dp(36), slot * 0.55f);
        text.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < labels.size(); i++) {
            float cx = slot * i + slot / 2;
            float h = chartH * values.get(i) / (float) max * progress;
            rect.set(cx - barW / 2, top, cx + barW / 2, top + chartH);
            fill.setColor(track);
            c.drawRoundRect(rect, dp(6), dp(6), fill);
            rect.set(cx - barW / 2, top + chartH - h, cx + barW / 2, top + chartH);
            fill.setColor(primary);
            if (h > 0) c.drawRoundRect(rect, dp(6), dp(6), fill);
            text.setFakeBoldText(true);
            text.setTextSize(dp(12));
            text.setColor(muted);
            c.drawText(String.valueOf(values.get(i)), cx, top + chartH - h - dp(6), text);
            text.setFakeBoldText(false);
            c.drawText(ellipsize(labels.get(i), slot - dp(4)), cx, getHeight() - dp(12), text);
        }
    }

    private void drawLine(Canvas c, int primary, int track, int muted) {
        if (empty(c, muted)) return;
        int max = Math.max(1, max());
        float labelH = dp(28), top = dp(20), left = dp(8), right = getWidth() - dp(8);
        float chartH = getHeight() - labelH - top;
        float step = labels.size() > 1 ? (right - left) / (labels.size() - 1) : 0;
        stroke.setStrokeWidth(dp(1));
        stroke.setColor(track);
        for (int g = 0; g <= 2; g++) {
            float y = top + chartH * g / 2f;
            c.drawLine(left, y, right, y, stroke);
        }
        Path path = new Path();
        Path area = new Path();
        float[] xs = new float[labels.size()], ys = new float[labels.size()];
        for (int i = 0; i < labels.size(); i++) {
            xs[i] = left + step * i;
            ys[i] = top + chartH - chartH * values.get(i) / (float) max * progress;
            if (i == 0) { path.moveTo(xs[i], ys[i]); area.moveTo(xs[i], top + chartH); }
            else path.lineTo(xs[i], ys[i]);
            area.lineTo(xs[i], ys[i]);
        }
        area.lineTo(xs[xs.length - 1], top + chartH);
        area.close();
        fill.setColor(primary);
        fill.setAlpha(36);
        c.drawPath(area, fill);
        fill.setAlpha(255);
        stroke.setColor(primary);
        stroke.setStrokeWidth(dp(3));
        c.drawPath(path, stroke);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(dp(11.5f));
        for (int i = 0; i < xs.length; i++) {
            c.drawCircle(xs[i], ys[i], dp(4.5f), fill);
            text.setColor(muted);
            text.setFakeBoldText(true);
            c.drawText(String.valueOf(values.get(i)), xs[i], ys[i] - dp(9), text);
            text.setFakeBoldText(false);
            float lx = Math.max(dp(16), Math.min(getWidth() - dp(16), xs[i]));
            c.drawText(labels.get(i), lx, getHeight() - dp(8), text);
        }
    }

    private boolean empty(Canvas c, int muted) {
        if (!labels.isEmpty()) return false;
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(muted);
        text.setTextSize(dp(13));
        text.setFakeBoldText(false);
        c.drawText("No data yet", getWidth() / 2f, Math.max(dp(24), getHeight() / 2f), text);
        return true;
    }

    private int max() {
        int m = 0;
        for (int v : values) m = Math.max(m, v);
        return m;
    }

    private String ellipsize(String s, float width) {
        if (text.measureText(s) <= width) return s;
        String t = s;
        while (t.length() > 1 && text.measureText(t + "…") > width) t = t.substring(0, t.length() - 1);
        return t + "…";
    }

    /** Short week label for weekly charts, e.g. "12 Oct". */
    public static String weekLabel(long millis) {
        return new java.text.SimpleDateFormat("d MMM", Locale.getDefault()).format(new java.util.Date(millis));
    }
}
