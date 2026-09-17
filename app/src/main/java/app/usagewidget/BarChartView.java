package app.usagewidget;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/**
 * A small seven-bar week chart, drawn by hand to match the app's dark cards.
 *
 * <p>Fed already-scaled data by {@link #set}: each day is a bar height fraction in [0,1], a short
 * value label, and two honest states — {@code absent} (no sample that day, drawn as a hollow outline)
 * and {@code baseline} (the first-ever sample, no prior day to compare, drawn as a faint tick). These
 * keep "no data" visually distinct from "zero usage", which the app cares about elsewhere too.
 */
public final class BarChartView extends View {
    private String[] days = new String[0];
    private float[] frac = new float[0];
    private String[] valueLabels = new String[0];
    private boolean[] absent = new boolean[0];
    private boolean[] baseline = new boolean[0];

    private final int ink = Color.rgb(239,245,238), muted = Color.rgb(173,187,178);
    private final int barTop = Color.rgb(255,186,122), barBottom = Color.rgb(255,126,107);
    private final int grid = Color.rgb(57,72,62);
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint value = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    public BarChartView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE); line.setStrokeWidth(dp(1));
        label.setColor(muted); label.setTextAlign(Paint.Align.CENTER); label.setTextSize(sp(11));
        value.setColor(ink); value.setTextAlign(Paint.Align.CENTER); value.setTextSize(sp(9));
    }
    private float dp(float n) { return n*density; }
    private float sp(float n) { return n*density; }

    /** All arrays must be length seven, oldest day first. */
    public void set(String[] days, float[] frac, String[] valueLabels, boolean[] absent, boolean[] baseline) {
        this.days=days; this.frac=frac; this.valueLabels=valueLabels; this.absent=absent; this.baseline=baseline;
        invalidate();
    }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int w = resolveSize((int)dp(320), wSpec);
        setMeasuredDimension(w, resolveSize((int)dp(150), hSpec));
    }

    @Override protected void onDraw(Canvas canvas) {
        int n = frac.length;
        if (n == 0) return;
        float left = dp(4), right = getWidth()-dp(4);
        float top = dp(16);                 // room for value labels above the tallest bar
        float labelBand = dp(18);           // day labels below the axis
        float axis = getHeight()-labelBand-dp(4);
        float usable = axis-top;

        line.setColor(grid);
        canvas.drawLine(left, axis, right, axis, line);

        float slot = (right-left)/n;
        float barW = Math.min(slot*0.56f, dp(30));
        for (int i = 0; i < n; i++) {
            float cx = left + slot*i + slot/2f;
            float f = Math.max(0f, Math.min(1f, frac[i]));
            float h = usable*f;
            float bTop = axis-h, bLeft = cx-barW/2f, bRight = cx+barW/2f;
            float r = dp(3);
            if (absent[i]) {
                line.setColor(grid);
                RectF hollow = new RectF(bLeft, axis-dp(6), bRight, axis);
                canvas.drawRoundRect(hollow, r, r, line);
            } else if (baseline[i]) {
                bar.setShader(null); bar.setColor(grid);
                canvas.drawRoundRect(new RectF(bLeft, axis-dp(3), bRight, axis), r, r, bar);
            } else {
                float paintTop = h < dp(3) ? axis-dp(3) : bTop;   // keep a sliver visible for tiny/zero usage
                bar.setShader(new LinearGradient(0, paintTop, 0, axis, barTop, barBottom, Shader.TileMode.CLAMP));
                canvas.drawRoundRect(new RectF(bLeft, paintTop, bRight, axis), r, r, bar);
                bar.setShader(null);
                if (valueLabels[i] != null && !valueLabels[i].isEmpty())
                    canvas.drawText(valueLabels[i], cx, Math.max(top-dp(4), bTop-dp(4)), value);
            }
            label.setColor(absent[i] ? grid : muted);
            canvas.drawText(days[i], cx, getHeight()-dp(5), label);
        }
    }
}
