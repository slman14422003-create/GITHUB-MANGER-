package com.ghmanager.app;

import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.List;

public class WidgetConfigActivity extends BaseRepoActivity {
    private int widgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
    private final List<PinnedWorkflows.Item> items = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setResult(RESULT_CANCELED);
        widgetId = getIntent().getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish();
            return;
        }
        setContentView(R.layout.activity_list);
        bindHeader(getString(R.string.wdg_pick), null);
        initList();
        btnRefresh.setVisibility(android.view.View.GONE);
        listView.setOnItemClickListener((p, v, pos, id) -> {
            if (pos >= 0 && pos < items.size()) pick(items.get(pos));
        });
        items.addAll(PinnedWorkflows.all(this));
        List<Row> rows = new ArrayList<>();
        for (PinnedWorkflows.Item it : items) {
            rows.add(new Row(R.drawable.ic_play, true, it.name, it.full(), false, true));
        }
        adapter.setRows(rows);
        showEmpty(items.isEmpty(), R.string.wdg_pick_empty);
    }

    private void pick(PinnedWorkflows.Item it) {
        BuildWidgetProvider.saveConfig(this, widgetId, it);
        AppWidgetManager mgr = AppWidgetManager.getInstance(this);
        BuildWidgetProvider.render(this, mgr, widgetId);
        WidgetRefreshJob.schedule(this);
        WidgetRefreshJob.runOnce(this);
        setResult(RESULT_OK, new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId));
        finish();
    }
}
