package com.ghmanager.app;

import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;

import java.util.List;

public class BuildStatsActivity extends BaseRepoActivity {
    private static final int LAST = 50;
    private long workflowId;
    private String workflowName;
    private LinearLayout content;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);
        workflowId = getIntent().getLongExtra("workflowId", 0);
        workflowName = getIntent().getStringExtra("workflowName");
        String sub = workflowId > 0 && workflowName != null && !workflowName.isEmpty()
                ? workflowName : repo + " · " + getString(R.string.bst_all);
        bindHeader(getString(R.string.bst_title), sub);
        content = findViewById(R.id.content);
        btnRefresh.setOnClickListener(v -> load());
        load();
    }

    private void load() {
        loading(true);
        io.execute(() -> {
            try {
                JSONArray runs = api.listRuns(owner, repo, workflowId, null, null, 1, LAST)
                        .optJSONArray("workflow_runs");
                final List<RunStats.Sample> samples = RunStats.fromRuns(runs, LAST);
                post(() -> {
                    loading(false);
                    showStatus(null);
                    render(samples);
                });
            } catch (Exception e) {
                fail(e);
            }
        });
    }

    private void render(List<RunStats.Sample> samples) {
        content.removeAllViews();
        if (samples.isEmpty()) {
            content.addView(Ui.body(this, getString(R.string.bst_empty), 14, R.color.text_secondary));
            return;
        }
        RunStats st = RunStats.of(samples);
        int rateColor = st.rate >= 80 ? R.color.ok : st.rate >= 50 ? R.color.warn : R.color.bad;
        LinearLayout r1 = tileRow();
        r1.addView(tile(String.format(java.util.Locale.US, "%.0f%%", st.rate), getString(R.string.bst_rate), rateColor));
        r1.addView(tile(String.valueOf(st.total), getString(R.string.bst_runs), R.color.text_primary));
        r1.addView(tile(String.valueOf(st.failed), getString(R.string.bst_failed), st.failed > 0 ? R.color.bad : R.color.text_primary));
        content.addView(r1);
        LinearLayout r2 = tileRow();
        r2.addView(tile(Fmt.duration(st.avg), getString(R.string.bst_avg), R.color.text_primary));
        r2.addView(tile(Fmt.duration(st.median), getString(R.string.bst_median), R.color.text_primary));
        r2.addView(tile(Fmt.duration(st.p90), getString(R.string.bst_p90), R.color.text_primary));
        content.addView(r2);

        content.addView(Ui.sectionTitle(this, getString(R.string.bst_last_n, samples.size())));
        RunChartView chart = new RunChartView(this);
        chart.setData(samples);
        chart.setContentDescription(getString(R.string.bst_desc, Math.round(st.rate), Fmt.duration(st.avg),
                Fmt.duration(st.median), Fmt.duration(st.p90)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this, 230));
        lp.setMargins(Ui.dp(this, 14), Ui.dp(this, 4), Ui.dp(this, 14), Ui.dp(this, 4));
        content.addView(chart, lp);
        content.addView(Ui.body(this, getString(R.string.bst_legend), 12, R.color.text_secondary));
    }

    private LinearLayout tileRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(Ui.dp(this, 14), Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 4));
        return row;
    }

    private LinearLayout tile(String value, String caption, int colorRes) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        int ph = Ui.dp(this, 10);
        tile.setPadding(ph, Ui.dp(this, 10), ph, Ui.dp(this, 10));
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setCornerRadius(Ui.radius(this, R.dimen.radius_s));
        g.setColor(Ui.color(this, R.color.surface));
        tile.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginEnd(Ui.dp(this, 8));
        tile.setLayoutParams(lp);
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(Ui.color(this, colorRes));
        v.setTextSize(18);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        tile.addView(v);
        TextView c = new TextView(this);
        c.setText(caption);
        c.setTextColor(Ui.color(this, R.color.text_secondary));
        c.setTextSize(11);
        tile.addView(c);
        return tile;
    }
}
