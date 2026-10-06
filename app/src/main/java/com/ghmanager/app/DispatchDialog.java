package com.ghmanager.app;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "Run workflow" dialog built from the workflow file itself: one real field per declared input
 * (text, checkbox, choice list). Inputs that carry the release tag / version get a "next number"
 * button that fills in the number after the newest release.
 */
public final class DispatchDialog {
    private DispatchDialog() {
    }

    public interface Runner {
        void run(String ref, JSONObject inputs) throws Exception;
    }

    /** Shows the dialog. yaml may be "" (then the plain key=value box is used). */
    public static void show(final Activity a, String title, String branch, String yaml,
                            final String suggestedTag, final Runner runner, final Runnable onError) {
        final WorkflowInputs.Result parsed = WorkflowInputs.parse(yaml);
        final LinearLayout box = Ui.box(a);
        final EditText ref = Ui.edit(a, a.getString(R.string.ref_hint), branch);
        box.setPadding(Ui.dp(a, 2), Ui.dp(a, 4), Ui.dp(a, 2), Ui.dp(a, 8));
        box.addView(Ui.label(a, a.getString(R.string.br_branch)));
        box.addView(ref);

        final Map<String, View> fields = new LinkedHashMap<>();
        final String tagName = parsed.tagInput();
        EditText manual = null;

        if (parsed.details.isEmpty()) {
            manual = Ui.editMulti(a, a.getString(R.string.inputs_hint), null, 3);
            box.addView(manual);
        } else {
            for (final WorkflowInputs.Input in : parsed.details.values()) {
                boolean isTag = in.name.equals(tagName);
                String label = in.name + (in.required ? " *" : "");
                if (isTag) label = "🏷  " + a.getString(R.string.tag_name_short) + "  (" + in.name + ")";
                box.addView(Ui.label(a, label));
                if (!in.description.isEmpty()) {
                    box.addView(Ui.body(a, in.description, 12, R.color.text_hint));
                }
                View field;
                if ("boolean".equals(in.type)) {
                    CheckBox cb = Ui.check(a, R.string.enabled, "true".equalsIgnoreCase(in.def));
                    field = cb;
                } else if ("choice".equals(in.type) && !in.options.isEmpty()) {
                    int def = Math.max(0, in.options.indexOf(in.def));
                    field = Ui.spinner(a, in.options, def);
                } else {
                    String start = in.def;
                    if (isTag && start.isEmpty()) start = suggestedTag == null ? "" : suggestedTag;
                    final EditText e = Ui.edit(a, in.name, start);
                    if ("number".equals(in.type)) {
                        e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
                    }
                    field = e;
                }
                fields.put(in.name, field);
                box.addView(field);
                if (isTag && field instanceof EditText && suggestedTag != null && !suggestedTag.isEmpty()) {
                    final EditText te = (EditText) field;
                    Button next = Ui.button(a, R.string.tag_use_next, false);
                    next.setText(a.getString(R.string.tag_use_next) + "  " + suggestedTag);
                    next.setOnClickListener(v -> te.setText(suggestedTag));
                    box.addView(next);
                }
            }
            if (tagName == null) {
                box.addView(Ui.body(a, a.getString(R.string.tag_none_declared), 12, R.color.warn));
            }
        }

        final EditText fManual = manual;
        ScrollView sv = new ScrollView(a);
        sv.setFillViewport(true);
        sv.setClipToPadding(false);
        sv.setPadding(0, 0, 0, Ui.dp(a, 4));
        int screenH = a.getResources().getDisplayMetrics().heightPixels;
        sv.setLayoutParams(new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(Ui.dp(a, 180), (int) (screenH * 0.58f))));
        sv.addView(box);
        new Dlg(a)
                .setTitle(title)
                .setView(sv)
                .setPositiveButton(R.string.run, (d, w) -> {
                    String r = ref.getText().toString().trim();
                    final String useRef = r.isEmpty() ? branch : r;
                    final JSONObject out = new JSONObject();
                    try {
                        if (fManual != null) {
                            for (String line : fManual.getText().toString().split("\n")) {
                                int i = line.indexOf('=');
                                if (i <= 0) continue;
                                out.put(line.substring(0, i).trim(), line.substring(i + 1).trim());
                            }
                        } else {
                            for (Map.Entry<String, View> e : fields.entrySet()) {
                                View v = e.getValue();
                                String val;
                                if (v instanceof CheckBox) val = String.valueOf(((CheckBox) v).isChecked());
                                else if (v instanceof Spinner) {
                                    Object sel = ((Spinner) v).getSelectedItem();
                                    val = sel == null ? "" : sel.toString();
                                } else val = ((EditText) v).getText().toString().trim();
                                if (val.isEmpty() && !(v instanceof CheckBox)) continue;
                                if (e.getKey().equals(tagName) && !Versions.validTag(val)) {
                                    android.widget.Toast.makeText(a, R.string.tag_invalid,
                                            android.widget.Toast.LENGTH_LONG).show();
                                    if (onError != null) onError.run();
                                    return;
                                }
                                out.put(e.getKey(), val);
                            }
                        }
                    } catch (Exception ignored) {
                    }
                    try {
                        runner.run(useRef, out);
                    } catch (Exception ex) {
                        android.widget.Toast.makeText(a, String.valueOf(ex.getMessage()),
                                android.widget.Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}
