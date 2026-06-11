package com.androidbuilder.ui;

import android.content.Context;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.androidbuilder.util.AppSettings;
import com.androidbuilder.util.LocaleUtils;

public abstract class BaseActivity extends AppCompatActivity {
    private String languageAtCreate;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleUtils.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        languageAtCreate = AppSettings.language(this);
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        String currentLanguage = AppSettings.language(this);
        if (languageAtCreate != null && !languageAtCreate.equals(currentLanguage)) {
            languageAtCreate = currentLanguage;
            recreate();
        }
    }

}
