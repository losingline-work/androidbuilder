package com.androidbuilder.backend;

final class BuildTimeoutPolicy {
    static final long IDLE_TIMEOUT_MS = 10L * 60L * 1000L;
    static final long TOTAL_TIMEOUT_MS = 30L * 60L * 1000L;

    private BuildTimeoutPolicy() {
    }

    static boolean exceededIdleTimeout(long nowMs, long lastOutputAtMs) {
        return nowMs - lastOutputAtMs > IDLE_TIMEOUT_MS;
    }

    static boolean exceededTotalTimeout(long nowMs, long startedAtMs) {
        return nowMs - startedAtMs > TOTAL_TIMEOUT_MS;
    }

    static String timeoutMessage(long nowMs, long startedAtMs, long lastOutputAtMs) {
        long elapsedMinutes = Math.max(1L, (nowMs - startedAtMs) / 60000L);
        long idleMinutes = Math.max(1L, (nowMs - lastOutputAtMs) / 60000L);
        return "Build timed out after " + elapsedMinutes + " minute(s). " +
                "Last build output was " + idleMinutes + " minute(s) ago. " +
                "Gradle dependency downloads or toolchain execution may be stalled; check network access to Google Maven/Maven Central and retry.";
    }
}
