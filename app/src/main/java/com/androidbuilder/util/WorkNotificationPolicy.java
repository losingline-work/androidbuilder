package com.androidbuilder.util;

public final class WorkNotificationPolicy {
    private WorkNotificationPolicy() {
    }

    public static boolean shouldShowNotification(boolean appForeground) {
        return !appForeground;
    }
}
