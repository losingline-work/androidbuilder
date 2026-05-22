package com.androidbuilder;

import android.app.Application;

import com.androidbuilder.data.AppRepository;
import com.google.android.material.color.DynamicColors;

public class AndroidBuilderApp extends Application {
    private AppRepository repository;

    @Override
    public void onCreate() {
        super.onCreate();
        DynamicColors.applyToActivitiesIfAvailable(this);
        repository = new AppRepository(this);
    }

    public AppRepository repository() {
        return repository;
    }
}
