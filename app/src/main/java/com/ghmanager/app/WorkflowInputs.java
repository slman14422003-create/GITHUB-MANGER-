package com.ghmanager.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Minimal reader for the {@code workflow_dispatch} section of a workflow file: tells which manual
 * inputs exist so the app only sends inputs the workflow declares (GitHub rejects unknown ones).
 */
public final class WorkflowInputs {
    private WorkflowInputs() {
    }

    /** One declared manual input: name, type (string/boolean/choice/number/environment), default, options. */
    public static final class Input {
        public String name = "";
        public String type = "string";
        public String def = "";
        public String description = "";
        public boolean required = false;
        public final List<String> options = new ArrayList<>();
    }

    private static final Pattern TAGLIKE = Pattern.compile("(?i)(^|[_-])(tag|version|ver)([_-]|$)|tag_?name|version_?(name|number)");

    public static final class Result {
        public boolean dispatch;
        /** Input name -> default value ("" when none). */
        public final Map<String, String> inputs = new LinkedHashMap<>();
        /** Same inputs with their type, options, description. */
        public final Map<String, Input> details = new LinkedHashMap<>();

        /** The declared input that carries the release tag / version number, or null. */
        public String tagInput() {
            String[] preferred = {"tag", "tag_name", "release_tag", "version", "release_version", "new_version", "version_name"};
            for (String p : preferred) {
                Input in = details.get(p);
                if (in != null && !"boolean".equals(in.type)) return p;
                if (in == null && inputs.containsKey(p)) return p;
            }
            for (Input in : details.values()) {
                // a tag is text: never pick a checkbox or a choice list such as publish_release
                if (isTagLike(in.name) && !"boolean".equals(in.type) && !"choice".equals(in.type)) return in.name;
            }
            return null;
        }

        public boolean has(String name) {
            return inputs.containsKey(name);
        }
    }

    public static boolean isTagLike(String name) {
        return name != null && TAGLIKE.matcher(name).find();
    }

    private static int indent(String line) {
        int n = 0;
        while (n < line.length() && (line.charAt(n) == ' ' || line.charAt(n) == '\t')) n++;
        return n;
    }

    private static boolean skip(String line) {
        String t = line.trim();
        return t.isEmpty() || t.startsWith("#");
    }

    private static String unquote(String v) {
        String s = v.trim();
        int hash = s.indexOf(" #");
        if (hash >= 0 && !s.startsWith("'") && !s.startsWith("\"")) s = s.substring(0, hash).trim();
        if (s.length() >= 2 && ((s.startsWith("'") && s.endsWith("'")) || (s.startsWith("\"") && s.endsWith("\"")))) {
            s = s.substring(1, s.length() - 1);
        }
        return s;
    }

    public static Result parse(String yaml) {
        Result r = new Result();
        if (yaml == null) return r;
        String[] lines = yaml.split("\r?\n");
        int dispIdx = -1;
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            if (skip(l)) continue;
            String t = l.trim();
            if (t.contains("workflow_dispatch")) {
                r.dispatch = true;
                if (t.startsWith("workflow_dispatch:")) dispIdx = i;
            }
        }
        if (dispIdx < 0) return r;
        int dispIndent = indent(lines[dispIdx]);
        int inputsIdx = -1;
        for (int i = dispIdx + 1; i < lines.length; i++) {
            if (skip(lines[i])) continue;
            if (indent(lines[i]) <= dispIndent) break;
            if (lines[i].trim().startsWith("inputs:")) {
                inputsIdx = i;
                break;
            }
        }
        if (inputsIdx < 0) return r;
        int inputsIndent = indent(lines[inputsIdx]);
        int nameIndent = -1;
        Input cur = null;
        boolean inOptions = false;
        int optionsIndent = -1;
        for (int i = inputsIdx + 1; i < lines.length; i++) {
            String l = lines[i];
            if (skip(l)) continue;
            int ind = indent(l);
            if (ind <= inputsIndent) break;
            if (nameIndent < 0) nameIndent = ind;
            String t = l.trim();
            if (ind == nameIndent) {
                inOptions = false;
                int colon = t.indexOf(':');
                if (colon > 0) {
                    cur = new Input();
                    cur.name = unquote(t.substring(0, colon));
                    r.inputs.put(cur.name, "");
                    r.details.put(cur.name, cur);
                }
            } else if (cur != null) {
                if (inOptions && t.startsWith("-") && ind > optionsIndent) {
                    cur.options.add(unquote(t.substring(1)));
                    continue;
                }
                if (inOptions && ind <= optionsIndent) inOptions = false;
                if (t.startsWith("default:")) {
                    cur.def = unquote(t.substring("default:".length()));
                    r.inputs.put(cur.name, cur.def);
                } else if (t.startsWith("type:")) {
                    cur.type = unquote(t.substring("type:".length())).toLowerCase();
                } else if (t.startsWith("description:")) {
                    cur.description = unquote(t.substring("description:".length()));
                } else if (t.startsWith("required:")) {
                    cur.required = "true".equalsIgnoreCase(unquote(t.substring("required:".length())));
                } else if (t.startsWith("options:")) {
                    inOptions = true;
                    optionsIndent = ind;
                    String rest = t.substring("options:".length()).trim();
                    if (rest.startsWith("[") && rest.endsWith("]")) {
                        inOptions = false;
                        for (String o : rest.substring(1, rest.length() - 1).split(",")) {
                            if (!o.trim().isEmpty()) cur.options.add(unquote(o));
                        }
                    }
                }
            }
        }
        return r;
    }
}
