package com.androidbuilder.ui;

/**
 * Picks a short, human-readable headline from a captured launch-crash report for the crash-repair card —
 * the exception class + message (the first {@code FATAL EXCEPTION}/{@code Caused by}/exception line),
 * trimmed to a card-friendly length. Pure (no Android deps) so it is unit-testable.
 */
final class CrashSummaryPolicy {
    private static final int MAX = 200;

    private CrashSummaryPolicy() {
    }

    static String excerpt(String crash) {
        if (crash == null) {
            return "";
        }
        String best = "";
        for (String raw : crash.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("at ") || line.startsWith("...")) {
                continue; // stack frames carry no headline
            }
            // An exception line looks like "<pkg>.SomeException: message" or "Caused by: ...Exception: ...".
            if (line.contains("Exception") || line.contains("Error") || line.startsWith("Caused by")) {
                return trim(stripPrefix(line));
            }
            if (best.isEmpty()) {
                best = line; // fall back to the first meaningful line
            }
        }
        return trim(stripPrefix(best));
    }

    private static String stripPrefix(String line) {
        // Drop logcat-style "FATAL EXCEPTION: main" framing so the actual exception leads.
        String value = line;
        int marker = value.indexOf("FATAL EXCEPTION");
        if (marker >= 0) {
            int colon = value.indexOf(':', marker);
            if (colon >= 0 && colon + 1 < value.length()) {
                value = value.substring(colon + 1).trim();
            }
        }
        return value.isEmpty() ? line : value;
    }

    private static String trim(String value) {
        String v = value == null ? "" : value.trim();
        return v.length() <= MAX ? v : v.substring(0, MAX - 1).trim() + "…";
    }
}
