package com.ghmanager.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;

import java.util.List;
import java.util.Locale;

/** Library of ready-made prompts: tap one to copy it (with your repository filled in) or share it. */
public class PromptsActivity extends BaseRepoActivity {
    private LinearLayout content;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);
        bindHeader(getString(R.string.pr_title), null);
        btnRefresh.setVisibility(View.GONE);
        content = findViewById(R.id.content);
        render();
    }

    private void render() {
        boolean ar = Locale.getDefault().getLanguage().equals("ar");
        List<Prompts.Item> items = Prompts.all(ar);
        int[] titles = {R.string.pr_g_files, R.string.pr_g_folders, R.string.pr_g_fix, R.string.pr_g_release};
        content.removeAllViews();
        content.addView(Ui.body(this, getString(R.string.pr_hint), 13, R.color.text_secondary));
        for (int g = 0; g < titles.length; g++) {
            boolean header = false;
            for (final Prompts.Item it : items) {
                if (it.group != g) continue;
                if (!header) {
                    content.addView(Ui.sectionTitle(this, getString(titles[g])));
                    header = true;
                }
                final String text = Prompts.fill(it.text, owner, repo, branch);
                content.addView(Ui.rowView(this, content,
                        new Row(R.drawable.ic_clipboard, true, it.title, firstLine(text), false, true),
                        v -> open(it.title, text)));
            }
        }
    }

    private static String firstLine(String t) {
        int i = t.indexOf('\n');
        String s = i > 0 ? t.substring(0, i) : t;
        return s.length() > 90 ? s.substring(0, 90) + "…" : s;
    }

    private void open(final String title, final String text) {
        new Dlg(this)
                .setTitle(title)
                .setMessage(text)
                .setPositiveButton(R.string.copy_text, (d, w) -> copy(title, text))
                .setNeutralButton(R.string.share, (d, w) -> shareText(text))
                .setNegativeButton(R.string.close, null)
                .show();
    }
}
