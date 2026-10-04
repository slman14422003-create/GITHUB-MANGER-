package com.ghmanager.app;

import android.os.Bundle;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Collaborators of a repository and their pending invitations: invite, change permission, remove. */
public class CollaboratorsActivity extends BaseRepoActivity {
    private static final String[] PERMS = {"pull", "triage", "push", "maintain", "admin"};
    private final List<JSONObject> people = new ArrayList<>();
    private final List<JSONObject> invites = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list);
        bindHeader(getString(R.string.col_title), repo);
        initList();
        btnRefresh.setOnClickListener(v -> load());
        action(btnA1, R.drawable.ic_add, R.string.col_add, v -> inviteDialog());
        listView.setOnItemClickListener((p, v, pos, id) -> {
            if (pos >= 0 && pos < people.size()) personMenu(people.get(pos));
            else if (pos >= people.size() && pos < people.size() + invites.size()) {
                inviteMenu(invites.get(pos - people.size()));
            }
        });
        load();
    }

    private String[] permLabels() {
        return new String[]{getString(R.string.col_p_pull), getString(R.string.col_p_triage),
                getString(R.string.col_p_push), getString(R.string.col_p_maintain), getString(R.string.col_p_admin)};
    }

    private void load() {
        loading(true);
        io.execute(() -> {
            try {
                JSONArray a = api.collaborators(owner, repo);
                JSONArray inv = null;
                try {
                    inv = api.invitations(owner, repo);
                } catch (Exception ignored) {
                    // invitations need admin access; the list itself still works
                }
                final List<JSONObject> p = new ArrayList<>();
                for (int i = 0; i < a.length(); i++) p.add(a.getJSONObject(i));
                final List<JSONObject> iv = new ArrayList<>();
                for (int i = 0; inv != null && i < inv.length(); i++) iv.add(inv.getJSONObject(i));
                post(() -> {
                    loading(false);
                    showStatus(null);
                    people.clear();
                    people.addAll(p);
                    invites.clear();
                    invites.addAll(iv);
                    render();
                });
            } catch (Exception e) {
                fail(e);
            }
        });
    }

    private void render() {
        List<Row> rows = new ArrayList<>();
        for (JSONObject o : people) {
            String login = o.optString("login");
            String role = o.optString("role_name", "");
            Row r = new Row(R.drawable.ic_star, false, login, role, false, true);
            if (login.equalsIgnoreCase(owner)) r.badge(getString(R.string.col_owner), Ui.color(this, R.color.info));
            rows.add(r);
        }
        for (JSONObject o : invites) {
            JSONObject invitee = o.optJSONObject("invitee");
            String login = invitee == null ? "" : invitee.optString("login");
            Row r = new Row(R.drawable.ic_clock, false, login, o.optString("permissions"), false, true);
            r.badge(getString(R.string.col_pending), Ui.color(this, R.color.warn));
            rows.add(r);
        }
        adapter.setRows(rows);
        showEmpty(rows.isEmpty(), R.string.col_none);
    }

    private void personMenu(final JSONObject o) {
        final String login = o.optString("login");
        if (login.equalsIgnoreCase(owner)) return;
        String[] opts = {getString(R.string.col_change), getString(R.string.col_remove)};
        choose(login, opts, (d, which) -> {
            if (which == 0) {
                choose(login, permLabels(), (d2, idx) -> bg(() -> {
                    api.addCollaborator(owner, repo, login, PERMS[idx]);
                    post(() -> {
                        toast(R.string.col_updated);
                        load();
                    });
                }));
            } else {
                confirm(getString(R.string.col_remove), getString(R.string.col_remove_msg, login), R.string.delete, () ->
                        bg(() -> {
                            api.removeCollaborator(owner, repo, login);
                            post(this::load);
                        }));
            }
        });
    }

    private void inviteMenu(final JSONObject o) {
        final long id = o.optLong("id");
        JSONObject invitee = o.optJSONObject("invitee");
        final String login = invitee == null ? "" : invitee.optString("login");
        choose(login, new String[]{getString(R.string.col_cancel_invite)}, (d, which) ->
                bg(() -> {
                    api.deleteInvitation(owner, repo, id);
                    post(this::load);
                }));
    }

    private void inviteDialog() {
        LinearLayout box = Ui.box(this);
        final EditText user = Ui.edit(this, getString(R.string.col_user), null);
        final Spinner perm = Ui.spinner(this, Arrays.asList(permLabels()), 2);
        box.addView(user);
        box.addView(Ui.label(this, getString(R.string.col_perm)));
        box.addView(perm);
        new Dlg(this)
                .setTitle(R.string.col_add)
                .setView(box)
                .setPositiveButton(R.string.send, (d, w) -> {
                    final String u = user.getText().toString().trim();
                    if (u.isEmpty()) return;
                    final String p = PERMS[perm.getSelectedItemPosition()];
                    bg(() -> {
                        api.addCollaborator(owner, repo, u, p);
                        post(() -> {
                            toast(R.string.col_invited);
                            load();
                        });
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}
