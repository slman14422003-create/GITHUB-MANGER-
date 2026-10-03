package com.ghmanager.app;

public class Row {
    public final int icon;
    public final boolean accent;
    public final String title;
    public final String sub;
    public final boolean lock;
    public final boolean chevron;

    public Row(int icon, boolean accent, String title, String sub, boolean lock, boolean chevron) {
        this.icon = icon;
        this.accent = accent;
        this.title = title;
        this.sub = sub;
        this.lock = lock;
        this.chevron = chevron;
    }
}
