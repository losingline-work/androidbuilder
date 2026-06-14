package com.androidbuilder;

/**
 * One-line build identity (version + git SHA + build time) stamped into every job log and the
 * exported project log, so a failure log unambiguously identifies which code produced it.
 */
public final class BuildStamp {
    private BuildStamp() {
    }

    public static String text() {
        return "v" + BuildConfig.VERSION_NAME
                + " (" + BuildConfig.VERSION_CODE + ") · "
                + BuildConfig.GIT_SHA + " · built " + BuildConfig.BUILD_TIME;
    }
}
