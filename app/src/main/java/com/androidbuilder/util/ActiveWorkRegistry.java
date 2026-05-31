package com.androidbuilder.util;

import android.content.Context;

import com.androidbuilder.service.WorkForegroundService;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class ActiveWorkRegistry {
    private static final Set<Long> ACTIVE_PROJECTS = new HashSet<>();
    private static final Map<Long, String> ACTIVE_MESSAGES = new HashMap<>();

    private ActiveWorkRegistry() {
    }

    public static synchronized void begin(long projectId) {
        ACTIVE_PROJECTS.add(projectId);
    }

    public static synchronized void end(long projectId) {
        ACTIVE_PROJECTS.remove(projectId);
    }

    public static synchronized void begin(Context context, long projectId, String message) {
        ACTIVE_PROJECTS.add(projectId);
        ACTIVE_MESSAGES.put(projectId, message);
        if (WorkNotificationPolicy.shouldShowNotification(AppForegroundTracker.isForeground())) {
            WorkForegroundService.begin(context.getApplicationContext(), projectId, message);
        }
    }

    public static synchronized void end(Context context, long projectId) {
        ACTIVE_PROJECTS.remove(projectId);
        ACTIVE_MESSAGES.remove(projectId);
        WorkForegroundService.end(context.getApplicationContext(), projectId);
    }

    public static synchronized boolean isActive(long projectId) {
        return ACTIVE_PROJECTS.contains(projectId);
    }

    public static synchronized void onAppBackgrounded(Context context) {
        for (Long projectId : ACTIVE_PROJECTS) {
            WorkForegroundService.begin(context.getApplicationContext(), projectId, ACTIVE_MESSAGES.get(projectId));
        }
    }

    public static synchronized void onAppForegrounded(Context context) {
        for (Long projectId : ACTIVE_PROJECTS) {
            WorkForegroundService.end(context.getApplicationContext(), projectId);
        }
    }
}
