package com.ghmanager.app;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/** Full management of the GitHub accounts: profile, access, e-mails, SSH keys, organizations, sign-out. */
public class AccountActivity extends BaseRepoActivity {
    private LinearLayout content;
    private JSONObject user;
    private JSONObject rate;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);
        bindHeader(getString(R.string.acc_title), null);
        content = findViewById(R.id.content);
        btnRefresh.setOnClickListener(v -> load());
        render();
        load();
    }

    private void load() {
        loading(true);
        io.execute(() -> {
            try {
                final JSONObject u = api.getUserMeta();
                JSONObject r = null;
                try {
                    r = api.rateLimit();
                } catch (Exception ignored) {
                }
                final JSONObject fr = r;
                post(() -> {
                    loading(false);
                    user = u;
                    rate = fr;
                    Accounts.Acc a = Accounts.active(this);
                    if (a != null) Accounts.updateProfile(this, a.id, u);
                    render();
                });
            } catch (Exception e) {
                fail(e);
            }
        });
    }

    // ------------------------------------------------------------------ rendering

    private View side(View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
        v.setLayoutParams(lp);
        return v;
    }

    private void addRow(Row row, View.OnClickListener l) {
        content.addView(Ui.rowView(this, content, row, l));
    }

    private void render() {
        content.removeAllViews();
        if (user != null) content.addView(profileCard(user));

        // ---- accounts on this device
        content.addView(Ui.sectionTitle(this, getString(R.string.acc_accounts)));
        Accounts.Acc cur = Accounts.active(this);
        List<Accounts.Acc> list = Accounts.all(this);
        for (final Accounts.Acc acc : list) {
            final boolean isActive = cur != null && acc.id.equals(cur.id);
            View row = AccountSheet.row(this, acc, isActive, v -> accountMenu(acc, isActive));
            content.addView(side(row));
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) row.getLayoutParams();
            lp.setMargins(Ui.dp(this, 14), 0, Ui.dp(this, 14), Ui.dp(this, 8));
        }
        View add = AccountSheet.action(this, R.drawable.ic_add, R.string.acc_add, v -> {
            Intent i = new Intent(this, LoginActivity.class);
            i.putExtra("add", true);
            startActivity(i);
        });
        content.addView(side(add));
        ((LinearLayout.LayoutParams) add.getLayoutParams()).setMargins(Ui.dp(this, 14), 0, Ui.dp(this, 14), Ui.dp(this, 8));

        if (user != null) {
            // ---- access
            content.addView(Ui.sectionTitle(this, getString(R.string.acc_access)));
            boolean oauth = cur != null && "oauth".equals(cur.type);
            addRow(new Row(R.drawable.ic_lock, true, getString(R.string.acc_token_type),
                    getString(oauth ? R.string.acc_type_oauth : R.string.acc_type_token), false, false), null);
            String scopes = user.optString("_scopes");
            addRow(new Row(R.drawable.ic_shield, true, getString(R.string.acc_scopes),
                    scopes.isEmpty() ? getString(R.string.acc_scopes_none) : scopes, false, false), null);
            if (rate != null && rate.has("limit")) {
                long reset = rate.optLong("reset") * 1000L;
                String left = Fmt.duration(Math.max(0, reset - System.currentTimeMillis()));
                addRow(new Row(R.drawable.ic_timer, true, getString(R.string.acc_rate),
                        getString(R.string.acc_rate_val, rate.optInt("remaining"), rate.optInt("limit"), left),
                        false, false), null);
            }

            // ---- profile tools
            content.addView(Ui.sectionTitle(this, getString(R.string.acc_profile)));
            addRow(new Row(R.drawable.ic_edit, true, getString(R.string.acc_edit), null, false, true),
                    v -> editProfile());
            addRow(new Row(R.drawable.ic_clipboard, true, getString(R.string.acc_emails), null, false, true),
                    v -> showEmails());
            addRow(new Row(R.drawable.ic_lock, true, getString(R.string.acc_keys), null, false, true),
                    v -> showKeys());
            addRow(new Row(R.drawable.ic_repo, true, getString(R.string.acc_orgs), null, false, true),
                    v -> showOrgs());
            final String url = Fmt.s(user, "html_url");
            addRow(new Row(R.drawable.ic_open, true, getString(R.string.acc_open), null, false, true),
                    v -> openUrl(url));
            addRow(new Row(R.drawable.ic_copy, true, getString(R.string.copy_link), null, false, false),
                    v -> copy("profile", url));
        }

        // ---- sign out
        content.addView(Ui.sectionTitle(this, getString(R.string.set_account)));
        final int bad = Ui.color(this, R.color.bad);
        if (cur != null) {
            final String id = cur.id;
            addRow(new Row(R.drawable.ic_logout, true, getString(R.string.acc_signout), null, false, false).tint(bad),
                    v -> confirm(getString(R.string.acc_signout), getString(R.string.acc_signout_msg),
                            R.string.logout, () -> Accounts.signOut(this, id)));
        }
        if (list.size() > 1) {
            addRow(new Row(R.drawable.ic_logout, true, getString(R.string.acc_signout_all), null, false, false).tint(bad),
                    v -> confirm(getString(R.string.acc_signout_all), getString(R.string.acc_signout_all_msg),
                            R.string.logout, () -> Accounts.signOutAll(this)));
        }
    }

    private View profileCard(JSONObject u) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setBackgroundResource(R.drawable.bg_card);
        int p = Ui.dp(this, 20);
        card.setPadding(p, p, p, p);
        Ui.block(this, card);

        ImageView img = new ImageView(this);
        card.addView(img, new LinearLayout.LayoutParams(Ui.dp(this, 84), Ui.dp(this, 84)));
        Avatar.load(img, Fmt.s(u, "avatar_url"), 18);

        String name = Fmt.s(u, "name");
        String login = Fmt.s(u, "login");
        TextView t = new TextView(this);
        t.setText(name.isEmpty() ? login : name);
        t.setTextColor(Ui.color(this, R.color.text_primary));
        t.setTextSize(22);
        t.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.topMargin = Ui.dp(this, 12);
        card.addView(t, tl);

        TextView lg = new TextView(this);
        lg.setText("@" + login);
        lg.setTextColor(Ui.color(this, R.color.text_secondary));
        lg.setTextSize(14);
        card.addView(lg);

        String bio = Fmt.s(u, "bio");
        if (!bio.isEmpty()) {
            TextView b = new TextView(this);
            b.setText(bio);
            b.setTextColor(Ui.color(this, R.color.text_primary));
            b.setTextSize(14);
            b.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bl.topMargin = Ui.dp(this, 10);
            card.addView(b, bl);
        }

        LinearLayout stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        stats.setWeightSum(3f);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.topMargin = Ui.dp(this, 16);
        card.addView(stats, sl);
        int repos = u.optInt("public_repos") + u.optInt("total_private_repos");
        stats.addView(stat(u.optInt("followers"), R.string.acc_followers));
        stats.addView(stat(u.optInt("following"), R.string.acc_following));
        stats.addView(stat(repos, R.string.acc_repos));

        StringBuilder meta = new StringBuilder();
        append(meta, Fmt.s(u, "company"));
        append(meta, Fmt.s(u, "location"));
        append(meta, Fmt.s(u, "blog"));
        JSONObject plan = u.optJSONObject("plan");
        if (plan != null && !plan.optString("name").isEmpty()) {
            append(meta, getString(R.string.acc_private_plan, plan.optString("name")));
        }
        String created = Fmt.s(u, "created_at");
        if (!created.isEmpty()) append(meta, getString(R.string.acc_since, Fmt.date(created)));
        if (meta.length() > 0) {
            TextView m = new TextView(this);
            m.setText(meta.toString());
            m.setTextColor(Ui.color(this, R.color.text_secondary));
            m.setTextSize(13);
            m.setGravity(Gravity.CENTER);
            m.setLineSpacing(0, 1.2f);
            LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ml.topMargin = Ui.dp(this, 14);
            card.addView(m, ml);
        }
        return card;
    }

    private static void append(StringBuilder sb, String s) {
        if (s == null || s.isEmpty()) return;
        if (sb.length() > 0) sb.append('\n');
        sb.append(s);
    }

    private View stat(int n, int labelRes) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER);
        c.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView num = new TextView(this);
        num.setText(String.valueOf(n));
        num.setTextColor(Ui.color(this, R.color.text_primary));
        num.setTextSize(18);
        num.setTypeface(Typeface.DEFAULT_BOLD);
        c.addView(num);
        TextView lb = new TextView(this);
        lb.setText(labelRes);
        lb.setTextColor(Ui.color(this, R.color.text_secondary));
        lb.setTextSize(12);
        c.addView(lb);
        return c;
    }

    // ------------------------------------------------------------------ actions

    private void accountMenu(final Accounts.Acc acc, boolean isActive) {
        final String[] items = isActive
                ? new String[]{getString(R.string.acc_signout)}
                : new String[]{getString(R.string.acc_switch), getString(R.string.acc_signout)};
        choose(acc.title(), items, (d, which) -> {
            boolean signOut = isActive || which == 1;
            if (!signOut) {
                Accounts.switchTo(this, acc.id);
                return;
            }
            confirm(getString(R.string.acc_signout), getString(R.string.acc_signout_msg), R.string.logout,
                    () -> Accounts.signOut(this, acc.id));
        });
    }

    private void editProfile() {
        if (user == null) return;
        LinearLayout box = Ui.box(this);
        final EditText name = Ui.edit(this, getString(R.string.acc_name), Fmt.s(user, "name"));
        final EditText bio = Ui.editMulti(this, getString(R.string.acc_bio), Fmt.s(user, "bio"), 2);
        final EditText company = Ui.edit(this, getString(R.string.acc_company), Fmt.s(user, "company"));
        final EditText loc = Ui.edit(this, getString(R.string.acc_location), Fmt.s(user, "location"));
        final EditText blog = Ui.edit(this, getString(R.string.acc_blog), Fmt.s(user, "blog"));
        blog.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        box.addView(name);
        box.addView(bio);
        box.addView(company);
        box.addView(loc);
        box.addView(blog);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(box);
        new Dlg(this)
                .setTitle(R.string.acc_edit)
                .setView(sv)
                .setPositiveButton(R.string.save, (d, w) -> {
                    final JSONObject patch = new JSONObject();
                    try {
                        patch.put("name", name.getText().toString().trim());
                        patch.put("bio", bio.getText().toString().trim());
                        patch.put("company", company.getText().toString().trim());
                        patch.put("location", loc.getText().toString().trim());
                        patch.put("blog", blog.getText().toString().trim());
                    } catch (Exception ignored) {
                    }
                    loading(true);
                    bg(() -> {
                        api.updateProfile(patch);
                        post(() -> {
                            toast(R.string.acc_saved);
                            load();
                        });
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showEmails() {
        loading(true);
        bg(() -> {
            JSONArray arr = api.listEmails();
            final StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject e = arr.optJSONObject(i);
                if (e == null) continue;
                if (sb.length() > 0) sb.append('\n');
                sb.append(e.optString("email"));
                if (e.optBoolean("primary")) sb.append("  ★");
                if (!e.optBoolean("verified")) sb.append("  (!)");
            }
            final String text = sb.length() == 0 ? getString(R.string.acc_none) : sb.toString();
            post(() -> {
                loading(false);
                info(getString(R.string.acc_emails), text);
            });
        });
    }

    private void showOrgs() {
        loading(true);
        bg(() -> {
            JSONArray arr = api.listOrgs();
            final StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                if (sb.length() > 0) sb.append('\n');
                sb.append(o.optString("login"));
            }
            final String text = sb.length() == 0 ? getString(R.string.acc_none) : sb.toString();
            post(() -> {
                loading(false);
                info(getString(R.string.acc_orgs), text);
            });
        });
    }

    private void showKeys() {
        loading(true);
        bg(() -> {
            final JSONArray arr = api.listSshKeys();
            post(() -> {
                loading(false);
                final String[] items = new String[arr.length() + 1];
                items[0] = getString(R.string.acc_key_add);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject k = arr.optJSONObject(i);
                    items[i + 1] = k == null ? "?" : k.optString("title");
                }
                choose(getString(R.string.acc_keys), items, (d, which) -> {
                    if (which == 0) {
                        addKey();
                        return;
                    }
                    final JSONObject k = arr.optJSONObject(which - 1);
                    if (k == null) return;
                    confirm(k.optString("title"), getString(R.string.acc_key_delete_msg), R.string.delete, () -> {
                        loading(true);
                        bg(() -> {
                            api.deleteSshKey(k.optLong("id"));
                            post(() -> {
                                loading(false);
                                toast(R.string.done_ok);
                            });
                        });
                    });
                });
            });
        });
    }

    private void addKey() {
        LinearLayout box = Ui.box(this);
        final EditText title = Ui.edit(this, getString(R.string.acc_key_title), null);
        final EditText key = Ui.editMulti(this, getString(R.string.acc_key_body), null, 3);
        key.setTextDirection(View.TEXT_DIRECTION_LTR);
        box.addView(title);
        box.addView(key);
        new Dlg(this)
                .setTitle(R.string.acc_key_add)
                .setView(box)
                .setPositiveButton(R.string.save, (d, w) -> {
                    final String t = title.getText().toString().trim();
                    final String k = key.getText().toString().trim();
                    if (t.isEmpty() || k.isEmpty()) return;
                    loading(true);
                    bg(() -> {
                        api.addSshKey(t, k);
                        post(() -> {
                            loading(false);
                            toast(R.string.acc_saved);
                        });
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}
