package com.androidbuilder.agent;

public class HermesRepairShard {
    public final String focusPath;
    public final String logExcerpt;
    public final boolean exclusive;

    public HermesRepairShard(String focusPath, String logExcerpt, boolean exclusive) {
        this.focusPath = clean(focusPath);
        this.logExcerpt = clean(logExcerpt);
        this.exclusive = exclusive;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
