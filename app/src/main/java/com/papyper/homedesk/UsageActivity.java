package com.papyper.homedesk;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Usage: on-screen time per app for today / 7 / 30 days. EXTRA_APP_ID limits it to one app. */
public class UsageActivity extends Activity {

    static final String EXTRA_APP_ID = "app_id";
    private static final int MENU_CLEAR = 1;
    private static final int[] PERIODS = {1, 7, 30};
    private static final String[] PERIOD_LABELS = {"Today", "7 days", "30 days"};

    private UsageStore usage;
    private List<SiteStore.Site> apps;
    private boolean single;
    private int period = 7;

    private TextView total, detail;
    private UsageChart chart;
    private LinearLayout list;
    private TextView listHeader;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        usage = new UsageStore(this);
        apps = new SiteStore(this).load();
        long only = getIntent().getLongExtra(EXTRA_APP_ID, 0);
        if (only != 0) {
            List<SiteStore.Site> one = new ArrayList<>();
            for (SiteStore.Site a : apps)
                if (a.id == only)
                    one.add(a);
            apps = one;
            single = !one.isEmpty();
        }
        if (savedInstanceState != null)
            period = savedInstanceState.getInt("period", 7);
        setTitle(single ? apps.get(0).name + " usage" : "Usage");
        if (getActionBar() != null)
            getActionBar().setDisplayHomeAsUpEnabled(true);

        int pad = Icons.dp(this, 16);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, Icons.dp(this, 8), pad, pad * 2);

        RadioGroup tabs = new RadioGroup(this);
        tabs.setOrientation(RadioGroup.HORIZONTAL);
        for (int i = 0; i < PERIODS.length; i++) {
            RadioButton b = new RadioButton(this);
            b.setId(View.generateViewId());
            b.setText(PERIOD_LABELS[i]);
            b.setTag(PERIODS[i]);
            tabs.addView(b, new RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1));
            if (PERIODS[i] == period)
                b.setChecked(true);
        }
        tabs.setOnCheckedChangeListener((g, id) -> {
            period = (int) g.findViewById(id).getTag();
            refresh();
        });
        content.addView(tabs);

        total = new TextView(this);
        total.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
        total.setPadding(0, Icons.dp(this, 16), 0, 0);
        content.addView(total);
        detail = new TextView(this);
        detail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        detail.setAlpha(0.7f);
        content.addView(detail);

        chart = new UsageChart(this);
        LinearLayout.LayoutParams chartLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        chartLp.topMargin = Icons.dp(this, 20);
        content.addView(chart, chartLp);

        listHeader = new TextView(this);
        listHeader.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        listHeader.setAlpha(0.7f);
        listHeader.setPadding(0, Icons.dp(this, 24), 0, Icons.dp(this, 4));
        content.addView(listHeader);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        content.addView(list);

        TextView note = new TextView(this);
        note.setText("Only time an app is on screen counts. Kept on this phone for 90 days.");
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        note.setAlpha(0.55f);
        note.setPadding(0, Icons.dp(this, 24), 0, 0);
        content.addView(note);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        setContentView(scroll);
        refresh();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("period", period);
    }

    private void refresh() {
        LocalDate today = UsageStore.today();

        // headline
        long sum = 0;
        int opens = 0;
        for (SiteStore.Site a : apps) {
            sum += usage.time(a.id, period);
            opens += usage.opens(a.id, period);
        }
        total.setText(UsageStore.duration(sum));
        StringBuilder d = new StringBuilder(period == 1 ? "Today" : "Last " + period + " days");
        d.append(" · opened ").append(opens).append(opens == 1 ? " time" : " times");
        if (period > 1)
            d.append(" · ").append(UsageStore.duration(sum / period)).append(" a day");
        if (single)
            d.append("\nLast used ").append(UsageStore.ago(usage.lastUsed(apps.get(0).id)));
        detail.setText(d);

        // chart: 7 days for Today/7 days, 30 days otherwise
        int days = period == 30 ? 30 : 7;
        LocalDate[] dates = new LocalDate[days];
        long[][] values = new long[days][apps.size()];
        int[] colors = new int[apps.size()];
        for (int a = 0; a < apps.size(); a++)
            colors[a] = Icons.color(apps.get(a));
        for (int i = 0; i < days; i++) {
            dates[i] = today.minusDays(days - 1 - i);
            for (int a = 0; a < apps.size(); a++)
                values[i][a] = usage.time(apps.get(a).id, dates[i]);
        }
        chart.setData(dates, colors, values);

        // ranking
        list.removeAllViews();
        listHeader.setVisibility(single ? View.GONE : View.VISIBLE);
        if (single)
            return;
        listHeader.setText("Apps · " + (period == 1 ? "today" : "last " + period + " days"));
        List<SiteStore.Site> ranked = new ArrayList<>(apps);
        ranked.sort((x, y) -> Long.compare(usage.time(y.id, period), usage.time(x.id, period)));
        long top = ranked.isEmpty() ? 0 : usage.time(ranked.get(0).id, period);
        for (SiteStore.Site a : ranked)
            list.addView(row(a, top));
    }

    private View row(SiteStore.Site app, long top) {
        long time = usage.time(app.id, period);
        int opens = usage.opens(app.id, period);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Icons.dp(this, 10), 0, Icons.dp(this, 10));
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        row.setBackgroundResource(tv.resourceId);
        row.setOnClickListener(v -> startActivity(new android.content.Intent(this, UsageActivity.class)
                .putExtra(EXTRA_APP_ID, app.id)));

        ImageView icon = new ImageView(this);
        icon.setImageBitmap(Icons.bitmap(app, Icons.dp(this, 36), true));
        int size = Icons.dp(this, 36);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(size, size);
        iconLp.setMarginEnd(Icons.dp(this, 14));
        row.addView(icon, iconLp);

        LinearLayout middle = new LinearLayout(this);
        middle.setOrientation(LinearLayout.VERTICAL);
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        TextView name = new TextView(this);
        name.setText(app.name);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        name.setSingleLine();
        name.setEllipsize(TextUtils.TruncateAt.END);
        line.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView t = new TextView(this);
        t.setText(UsageStore.duration(time));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        line.addView(t);
        middle.addView(line);

        // share bar: this app's time relative to the most-used app
        LinearLayout track = new LinearLayout(this);
        track.setOrientation(LinearLayout.HORIZONTAL);
        View fill = new View(this);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(Icons.color(app));
        shape.setCornerRadius(Icons.dp(this, 3));
        fill.setBackground(shape);
        float share = top > 0 ? (float) time / top : 0;
        track.addView(fill, new LinearLayout.LayoutParams(0, Icons.dp(this, 6), share));
        track.addView(new View(this), new LinearLayout.LayoutParams(0, Icons.dp(this, 6), 1 - share));
        LinearLayout.LayoutParams trackLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        trackLp.topMargin = Icons.dp(this, 6);
        middle.addView(track, trackLp);

        TextView sub = new TextView(this);
        sub.setText("Opened " + opens + (opens == 1 ? " time" : " times") + " · last used " + UsageStore.ago(usage.lastUsed(app.id)));
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        sub.setAlpha(0.6f);
        sub.setPadding(0, Icons.dp(this, 4), 0, 0);
        middle.addView(sub);
        row.addView(middle, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        return row;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if (!single)
            menu.add(0, MENU_CLEAR, 0, "Clear usage history");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        if (item.getItemId() == MENU_CLEAR) {
            new AlertDialog.Builder(this)
                    .setTitle("Clear usage history?")
                    .setMessage("Deletes the recorded time for all apps. This can't be undone.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Clear", (d, w) -> {
                        usage.clearAll();
                        refresh();
                    })
                    .show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
