package com.ghmanager.app;

public class Row {
    public final int icon;
    public final boolean accent;
    public final String title;
    public final String sub;
    public final boolean lock;
    public final boolean chevron;
    /** Optional pill shown at the end of the row. */
    public String badge;
    public int badgeColor;
    /** Optional icon color (0 = default). */
    public int iconColor;
    /** True on screens in select mode: the row shows a tick circle in front of its icon. */
    public boolean selectable;
    public boolean checked;

    public Row(int icon, boolean accent, String title, String sub, boolean lock, boolean chevron) {
        this.icon = icon;
        this.accent = accent;
        this.title = title;
        this.sub = sub;
        this.lock = lock;
        this.chevron = chevron;
    }

    public Row badge(String text, int color) {
        this.badge = text;
        this.badgeColor = color;
        return this;
    }

    public Row select(boolean selectable, boolean checked) {
        this.selectable = selectable;
        this.checked = checked;
        return this;
    }

    public Row tint(int color) {
        this.iconColor = color;
        return this;
    }
}
