package com.androidbuilder.ui;

import android.os.Bundle;
import android.widget.TextView;

import com.androidbuilder.R;

public class ThirdPartyNoticesActivity extends BaseActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_third_party_notices);
        applySystemBarPadding();
        findViewById(R.id.backButton).setOnClickListener(v -> finish());
        ((TextView) findViewById(R.id.noticesText)).setText(R.string.third_party_notices_body);
    }
}
