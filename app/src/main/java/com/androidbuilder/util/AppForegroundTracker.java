package com.androidbuilder.util;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

public final class AppForegroundTracker {
    private static int startedActivities;

    private AppForegroundTracker() {
    }

    public static void register(Application application) {
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityStarted(Activity activity) {
                startedActivities++;
                if (startedActivities == 1) {
                    ActiveWorkRegistry.onAppForegrounded(activity.getApplicationContext());
                }
            }

            @Override
            public void onActivityStopped(Activity activity) {
                startedActivities = Math.max(0, startedActivities - 1);
                if (startedActivities == 0) {
                    ActiveWorkRegistry.onAppBackgrounded(activity.getApplicationContext());
                }
            }

            @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}
            @Override public void onActivityResumed(Activity activity) {}
            @Override public void onActivityPaused(Activity activity) {}
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
            @Override public void onActivityDestroyed(Activity activity) {}
        });
    }

    public static boolean isForeground() {
        return startedActivities > 0;
    }
}
