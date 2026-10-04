package com.ghmanager.app;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal reader for the {@code workflow_dispatch} section of a workflow file: tells which manual
 * inputs exist so the app only sends inputs the workflow declares (GitHub rejects unknown ones).
 */
public final class WorkflowInputs {
    private WorkflowInputs() {
    }

    public static final class Result {
        public boolean dispatch;
        /** Input name -> default value ("" when none). */
        public final Map<String, String> inputs = new LinkedHashMap<>();

        public boolean has(String name) {
            return inputs.containsKey(name);
        }
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
        String current = null;
        for (int i = inputsIdx + 1; i < lines.length; i++) {
            String l = lines[i];
            if (skip(l)) continue;
            int ind = indent(l);
            if (ind <= inputsIndent) break;
            if (nameIndent < 0) nameIndent = ind;
            String t = l.trim();
            if (ind == nameIndent) {
                int colon = t.indexOf(':');
                if (colon > 0) {
                    current = unquote(t.substring(0, colon));
                    r.inputs.put(current, "");
                }
            } else if (current != null && t.startsWith("default:")) {
                r.inputs.put(current, unquote(t.substring("default:".length())));
            }
        }
        return r;
    }
}
