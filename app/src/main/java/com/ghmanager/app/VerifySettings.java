package com.ghmanager.app;

import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class VerifySettings {
    private VerifySettings() {
    }

    static List<View> rows(final BaseRepoActivity a, ViewGroup parent, final Runnable rerender) {
        List<View> out = new ArrayList<>();
        final boolean totp = TotpVault.enabled(a);
        out.add(Ui.rowView(a, parent, new Row(R.drawable.ic_shield, true, a.getString(R.string.vs_totp),
                a.getString(totp ? R.string.vs_totp_on : R.string.vs_totp_off), false, true), v -> {
            if (totp) totpMenu(a, rerender);
            else guarded(a, () -> setup(a, rerender));
        }));
        out.add(Ui.rowView(a, parent, new Row(R.drawable.ic_filter, true, a.getString(R.string.vs_ops),
                a.getString(R.string.vs_ops_sub, Verify.ops(a).size(), Verify.ALL.length), false, true),
                v -> guarded(a, () -> pickOps(a, rerender))));
        out.add(Ui.rowView(a, parent, new Row(R.drawable.ic_timer, true, a.getString(R.string.vs_trust),
                trustLabel(a, Verify.trustSeconds(a)), false, true), v -> guarded(a, () -> pickTrust(a, rerender))));
        return out;
    }

    static void guarded(final BaseRepoActivity a, final Runnable ok) {
        a.io.execute(() -> {
            try {
                Verify.requireFresh();
            } catch (Exception e) {
                a.post(() -> a.toast(R.string.verify_failed));
                return;
            }
            a.post(ok);
        });
    }

    static String trustLabel(BaseRepoActivity a, int seconds) {
        if (seconds <= 0) return a.getString(R.string.vs_trust_every);
        return a.getString(R.string.vs_trust_min, seconds / 60);
    }

    static int opLabel(String cat) {
        switch (cat) {
            case Verify.DELETE:
                return R.string.vs_op_delete;
            case Verify.FORCE:
                return R.string.vs_op_force;
            case Verify.SECRETS:
                return R.string.vs_op_secrets;
            case Verify.COLLAB:
                return R.string.vs_op_collab;
            case Verify.PROTECTION:
                return R.string.vs_op_protection;
            case Verify.MERGE:
                return R.string.vs_op_merge;
            case Verify.ACTIONS:
                return R.string.vs_op_actions;
            default:
                return R.string.vs_op_repo;
        }
    }

    private static void pickOps(final BaseRepoActivity a, final Runnable rerender) {
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPaddingRelative(Ui.dp(a, 24), Ui.dp(a, 4), Ui.dp(a, 24), Ui.dp(a, 4));
        Set<String> cur = Verify.ops(a);
        final List<CheckBox> boxes = new ArrayList<>();
        for (String cat : Verify.ALL) {
            CheckBox cb = Ui.check(a, opLabel(cat), cur.contains(cat));
            cb.setTag(cat);
            boxes.add(cb);
            col.addView(cb);
        }
        new Dlg(a).setTitle(R.string.vs_ops).setView(col)
                .setPositiveButton(R.string.save, (d, w) -> {
                    Set<String> picked = new HashSet<>();
                    for (CheckBox cb : boxes) if (cb.isChecked()) picked.add((String) cb.getTag());
                    if (picked.isEmpty()) {
                        Dlg.stay(d);
                        a.toast(R.string.vs_ops_none);
                        return;
                    }
                    Verify.setOps(a, picked);
                    AuditLog.event(a, "verify_settings");
                    rerender.run();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private static void pickTrust(final BaseRepoActivity a, final Runnable rerender) {
        String[] labels = new String[Verify.TRUST_OPTIONS.length];
        for (int i = 0; i < labels.length; i++) labels[i] = trustLabel(a, Verify.TRUST_OPTIONS[i]);
        a.choose(a.getString(R.string.vs_trust), labels, (d, which) -> {
            Verify.setTrustSeconds(a, Verify.TRUST_OPTIONS[which]);
            AuditLog.event(a, "verify_settings");
            rerender.run();
        });
    }

    private static void totpMenu(final BaseRepoActivity a, final Runnable rerender) {
        String[] items = {a.getString(R.string.vs_regen_codes, RecoveryCodes.remaining(a)),
                a.getString(R.string.vs_totp_disable)};
        a.choose(a.getString(R.string.vs_totp), items, (d, which) -> {
            if (which == 0) {
                guarded(a, () -> {
                    List<String> codes = RecoveryCodes.generate(a);
                    AuditLog.event(a, "recovery_regen");
                    showCodes(a, codes);
                    rerender.run();
                });
            } else {
                guarded(a, () -> a.confirm(a.getString(R.string.vs_totp_disable),
                        a.getString(R.string.vs_totp_disable_msg), R.string.vs_totp_disable_do, () -> {
                            TotpVault.wipe(a);
                            RecoveryCodes.clear(a);
                            AuditLog.event(a, "totp_off");
                            rerender.run();
                        }));
            }
        });
    }

    private static byte[] hmac(byte[] key, byte[] msg) throws Exception {
        Mac m = Mac.getInstance("HmacSHA1");
        m.init(new SecretKeySpec(key, "HmacSHA1"));
        return m.doFinal(msg);
    }

    private static void setup(final BaseRepoActivity a, final Runnable rerender) {
        final byte[] secret = Totp.newSecret();
        final String b32 = Totp.base32(secret);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPaddingRelative(Ui.dp(a, 12), Ui.dp(a, 2), Ui.dp(a, 12), Ui.dp(a, 4));
        col.addView(Ui.body(a, a.getString(R.string.vs_setup_msg), 14, R.color.text_secondary));
        TextView key = Ui.noteCard(a, Totp.group(b32), R.color.text_primary);
        key.setTypeface(Typeface.MONOSPACE);
        key.setTextSize(18);
        col.addView(key);
        LinearLayout chips = new LinearLayout(a);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPaddingRelative(Ui.dp(a, 14), 0, Ui.dp(a, 14), Ui.dp(a, 8));
        TextView copy = Ui.chip(a, a.getString(R.string.vs_copy_key), false);
        copy.setOnClickListener(v -> a.copy("2FA", b32));
        TextView open = Ui.chip(a, a.getString(R.string.vs_open_app), false);
        open.setOnClickListener(v -> {
            Accounts.Acc acc = Accounts.active(a);
            String name = acc == null || acc.login.isEmpty() ? "account" : acc.login;
            try {
                a.startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse(Totp.uri(name, a.getString(R.string.app_name), secret))));
            } catch (Exception e) {
                a.toast(R.string.vs_no_app);
            }
        });
        chips.addView(copy);
        chips.addView(open);
        col.addView(chips);
        final EditText code = Ui.edit(a, a.getString(R.string.vs_setup_code), null);
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        code.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
        col.addView(code);
        new Dlg(a).setTitle(R.string.vs_setup_title).setView(col)
                .setPositiveButton(R.string.vs_activate, (d, w) -> {
                    boolean ok = false;
                    try {
                        ok = Totp.match(msg -> hmac(secret, msg), code.getText().toString().trim(),
                                System.currentTimeMillis(), -1L) >= 0;
                    } catch (Exception ignored) {
                    }
                    if (!ok) {
                        Dlg.stay(d);
                        Fx.shake(code);
                        a.toast(R.string.vs_setup_bad);
                        return;
                    }
                    if (!TotpVault.save(a, secret)) {
                        a.toast(R.string.vs_save_failed);
                        return;
                    }
                    List<String> codes = RecoveryCodes.generate(a);
                    AuditLog.event(a, "totp_on");
                    rerender.run();
                    showCodes(a, codes);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private static void showCodes(final BaseRepoActivity a, List<String> codes) {
        final StringBuilder sb = new StringBuilder();
        for (String c : codes) sb.append(c).append('\n');
        final String text = sb.toString().trim();
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPaddingRelative(Ui.dp(a, 12), Ui.dp(a, 2), Ui.dp(a, 12), Ui.dp(a, 4));
        col.addView(Ui.body(a, a.getString(R.string.vs_codes_msg), 14, R.color.text_secondary));
        TextView list = Ui.noteCard(a, text, R.color.text_primary);
        list.setTypeface(Typeface.MONOSPACE);
        list.setTextSize(17);
        col.addView(list);
        new Dlg(a).setTitle(R.string.vs_codes_title).setView(col)
                .setNeutralButton(R.string.vs_copy_codes, (d, w) -> {
                    Dlg.stay(d);
                    a.copy("recovery", text);
                })
                .setPositiveButton(R.string.vs_codes_done, null)
                .show();
    }
}
