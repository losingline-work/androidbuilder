package com.androidbuilder;

import android.app.Application;

import com.androidbuilder.data.AppRepository;
import com.androidbuilder.util.AppForegroundTracker;
import com.google.android.material.color.DynamicColors;

public class AndroidBuilderApp extends Application {
    private AppRepository repository;

    @Override
    public void onCreate() {
        super.onCreate();
        DynamicColors.applyToActivitiesIfAvailable(this);
        AppForegroundTracker.register(this);
        repository = new AppRepository(this);
        // Out-of-the-box offline libraries: extract the APK-bundled offline-maven cache on first run so the
        // common libraries resolve with no network. No-op when no bundle is shipped in this build.
        new Thread(() -> com.androidbuilder.backend.OfflineMavenInstaller.installBundledIfPresent(this),
                "offline-maven-bundle").start();
    }

    public AppRepository repository() {
        return repository;
    }
}
