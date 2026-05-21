package com.androidbuilder;

import android.app.Application;

import com.androidbuilder.data.AppRepository;

public class AndroidBuilderApp extends Application {
    private AppRepository repository;

    @Override
    public void onCreate() {
        super.onCreate();
        repository = new AppRepository(this);
    }

    public AppRepository repository() {
        return repository;
    }
}
