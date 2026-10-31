package com.papyper.homedesk;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;

/** Daily on-screen time as columns, each stacked by app colour. Oldest day on the left, today on the right. */
final class UsageChart extends View {

    private LocalDate[] days = new LocalDate[0];
    private int[] colors = new int[0];
    private long[][] values = new long[0][0];   // [day][app] milliseconds

    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint value = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axis = new Paint();
    private final RectF rect = new RectF();

    UsageChart(Context context) {
        super(context);
        TypedArray a = context.obtainStyledAttributes(new int[]{android.R.attr.textColorSecondary, android.R.attr.textColorPrimary});
        int secondary = a.getColor(0, 0xFF888888), primary = a.getColor(1, 0xFF000000);
        a.recycle();
        label.setColor(secondary);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTextSize(Icons.dp(context, 11));
        value.setColor(primary);
        value.setTextAlign(Paint.Align.CENTER);
        value.setTextSize(Icons.dp(context, 11));
        axis.setColor(secondary);
        axis.setAlpha(80);
        axis.setStrokeWidth(Icons.dp(context, 1));
    }

    void setData(LocalDate[] days, int[] colors, long[][] values) {
        this.days = days;
        this.colors = colors;
        this.values = values;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), Icons.dp(getContext(), 200));
    }

    @Override
    protected void onDraw(Canvas c) {
        int n = days.length;
        if (n == 0)
            return;
        boolean few = n <= 7;
        float top = few ? value.getTextSize() * 1.8f : value.getTextSize() * 0.5f;
        float labelHeight = label.getTextSize() * 2f;
        float baseline = getHeight() - labelHeight;
        float slot = (float) getWidth() / n;
        float colWidth = slot * (few ? 0.55f : 0.7f);

        long max = 0;
        long[] totals = new long[n];
        for (int d = 0; d < n; d++) {
            for (long v : values[d])
                totals[d] += v;
            max = Math.max(max, totals[d]);
        }
        c.drawLine(0, baseline, getWidth(), baseline, axis);
        LocalDate today = UsageStore.today();

        for (int d = 0; d < n; d++) {
            float cx = slot * d + slot / 2;
            float y = baseline;
            if (max > 0) {
                for (int a = 0; a < values[d].length; a++) {
                    if (values[d][a] <= 0)
                        continue;
                    float h = (baseline - top) * values[d][a] / max;
                    bar.setColor(colors[a]);
                    rect.set(cx - colWidth / 2, y - h, cx + colWidth / 2, y);
                    c.drawRect(rect, bar);
                    y -= h;
                }
            }
            if (few && totals[d] > 0)
                c.drawText(UsageStore.duration(totals[d]), cx, y - value.getTextSize() * 0.5f, value);

            String text = null;
            if (few)
                text = days[d].equals(today) ? "Today" : days[d].getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.getDefault());
            else if ((n - 1 - d) % 7 == 0)  // every 7th day, ending today
                text = days[d].equals(today) ? "Today" : days[d].format(DateTimeFormatter.ofPattern("d/M"));
            if (text != null)
                c.drawText(text, cx, baseline + label.getTextSize() * 1.4f, label);
        }
    }
}
