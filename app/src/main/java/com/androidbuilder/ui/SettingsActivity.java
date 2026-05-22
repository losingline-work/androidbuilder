package com.androidbuilder.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import com.androidbuilder.R;
import com.androidbuilder.agent.OpenAiClient;
import com.androidbuilder.backend.BuildBackendSettings;
import com.androidbuilder.backend.RuntimeInstaller;
import com.androidbuilder.termux.TermuxBridge;
import com.androidbuilder.util.AppSettings;
import com.androidbuilder.util.FileUtils;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class SettingsActivity extends BaseActivity {
    private static final int REQUEST_TERMUX_RUN_COMMAND = 8001;
    private EditText apiKey;
    private EditText endpoint;
    private EditText model;
    private Spinner providerSpinner;
    private Spinner languageSpinner;
    private Spinner backendSpinner;
    private View termuxSection;
    private EditText runtimeBootstrapUrlInput;
    private TextView runtimeStatusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        applySystemBarPadding();
        findViewById(R.id.backButton).setOnClickListener(v -> finish());
        apiKey = findViewById(R.id.apiKeyInput);
        endpoint = findViewById(R.id.endpointInput);
        model = findViewById(R.id.modelInput);
        providerSpinner = findViewById(R.id.providerSpinner);
        languageSpinner = findViewById(R.id.languageSpinner);
        backendSpinner = findViewById(R.id.backendSpinner);
        termuxSection = findViewById(R.id.termuxSection);
        runtimeBootstrapUrlInput = findViewById(R.id.runtimeBootstrapUrlInput);
        runtimeStatusText = findViewById(R.id.runtimeStatusText);
        configureSpinners();
        SharedPreferences prefs = getSharedPreferences(OpenAiClient.PREFS, MODE_PRIVATE);
        apiKey.setText(prefs.getString(OpenAiClient.KEY_API_KEY, ""));
        String provider = prefs.getString(OpenAiClient.KEY_PROVIDER, OpenAiClient.PROVIDER_OPENAI);
        providerSpinner.setSelection(providerIndex(provider));
        endpoint.setText(prefs.getString(OpenAiClient.KEY_ENDPOINT, OpenAiClient.defaultEndpoint(provider)));
        model.setText(prefs.getString(OpenAiClient.KEY_MODEL, OpenAiClient.defaultModel(provider)));
        languageSpinner.setSelection(languageIndex(AppSettings.language(this)));
        backendSpinner.setSelection(backendIndex(BuildBackendSettings.selected(this)));
        runtimeBootstrapUrlInput.setText(BuildBackendSettings.prefs(this).getString(BuildBackendSettings.KEY_BOOTSTRAP_URL, ""));
        findViewById(R.id.saveSettingsButton).setOnClickListener(v -> save());
        findViewById(R.id.refreshRuntimeStatusButton).setOnClickListener(v -> refreshRuntimeStatus());
        findViewById(R.id.installBundledRuntimeButton).setOnClickListener(v -> installBundledRuntime());
        findViewById(R.id.downloadRuntimeButton).setOnClickListener(v -> downloadRuntime());
        findViewById(R.id.requestTermuxPermissionButton).setOnClickListener(v -> requestTermuxPermission());
        findViewById(R.id.runTermuxSetupButton).setOnClickListener(v -> runTermuxSetup());
        findViewById(R.id.copyAdbGrantButton).setOnClickListener(v -> copyText(getString(R.string.adb_grant_clip_label), getString(R.string.adb_grant_command)));
        findViewById(R.id.copyTermuxBasicsButton).setOnClickListener(v -> copyText(getString(R.string.termux_basics_clip_label), getString(R.string.termux_basics_commands)));
        findViewById(R.id.copyBuildScriptButton).setOnClickListener(v -> copyAsset("termux/build.sh", "build.sh"));
        findViewById(R.id.copySetupScriptButton).setOnClickListener(v -> copyAsset("termux/setup-termux.sh", "setup-termux.sh"));
        findViewById(R.id.thirdPartyNoticesButton).setOnClickListener(v -> startActivity(new Intent(this, ThirdPartyNoticesActivity.class)));
        ((TextView) findViewById(R.id.termuxHelp)).setText(R.string.termux_help);
        updateTermuxSectionVisibility();
        refreshRuntimeStatus();
    }

    private void configureSpinners() {
        providerSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{getString(R.string.provider_openai), getString(R.string.provider_deepseek), getString(R.string.provider_custom)}));
        languageSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{getString(R.string.language_system), "English", "中文"}));
        backendSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{getString(R.string.backend_embedded), getString(R.string.backend_external_termux)}));
        providerSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                String provider = providerAt(position);
                if (!OpenAiClient.PROVIDER_CUSTOM.equals(provider)) {
                    endpoint.setText(OpenAiClient.defaultEndpoint(provider));
                    model.setText(OpenAiClient.defaultModel(provider));
                }
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
        backendSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                updateTermuxSectionVisibility();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
    }

    private void save() {
        String provider = providerAt(providerSpinner.getSelectedItemPosition());
        getSharedPreferences(OpenAiClient.PREFS, MODE_PRIVATE)
                .edit()
                .putString(OpenAiClient.KEY_PROVIDER, provider)
                .putString(OpenAiClient.KEY_API_KEY, apiKey.getText().toString().trim())
                .putString(OpenAiClient.KEY_ENDPOINT, endpoint.getText().toString().trim())
                .putString(OpenAiClient.KEY_MODEL, model.getText().toString().trim())
                .apply();
        AppSettings.prefs(this).edit().putString(AppSettings.KEY_LANGUAGE, languageAt(languageSpinner.getSelectedItemPosition())).apply();
        BuildBackendSettings.setSelected(this, backendAt(backendSpinner.getSelectedItemPosition()));
        BuildBackendSettings.prefs(this).edit().putString(BuildBackendSettings.KEY_BOOTSTRAP_URL, runtimeBootstrapUrlInput.getText().toString().trim()).apply();
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
        recreate();
    }

    private void updateTermuxSectionVisibility() {
        if (termuxSection != null) {
            termuxSection.setVisibility(BuildBackendSettings.EXTERNAL_TERMUX.equals(backendAt(backendSpinner.getSelectedItemPosition())) ? View.VISIBLE : View.GONE);
        }
    }

    private void copyAsset(String asset, String label) {
        try {
            copyText(label, readAsset(asset));
        } catch (Exception error) {
            Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void copyText(String label, String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text));
        Toast.makeText(this, getString(R.string.copied, label), Toast.LENGTH_SHORT).show();
    }

    private void refreshRuntimeStatus() {
        setRuntimeStatus(getString(R.string.runtime_status_checking));
        new Thread(() -> {
            try {
                String status = new RuntimeInstaller(this).status();
                runOnUiThread(() -> setRuntimeStatus(status));
            } catch (Exception error) {
                runOnUiThread(() -> setRuntimeStatus(error.getMessage()));
            }
        }, "runtime-status").start();
    }

    private void installBundledRuntime() {
        setRuntimeBusy(true);
        setRuntimeStatus(getString(R.string.runtime_installing));
        new RuntimeInstaller(this).installBundledAsync(new RuntimeInstaller.Callback() {
            @Override
            public void onSuccess(String status) {
                runOnUiThread(() -> {
                    setRuntimeBusy(false);
                    setRuntimeStatus(status);
                    Toast.makeText(SettingsActivity.this, R.string.runtime_installed, Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onError(Exception error) {
                runOnUiThread(() -> {
                    setRuntimeBusy(false);
                    setRuntimeStatus(error.getMessage());
                    Toast.makeText(SettingsActivity.this, getString(R.string.runtime_install_failed, error.getMessage()), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void downloadRuntime() {
        String url = runtimeBootstrapUrlInput.getText().toString().trim();
        if (url.isEmpty()) {
            Toast.makeText(this, R.string.runtime_url_required, Toast.LENGTH_SHORT).show();
            return;
        }
        BuildBackendSettings.prefs(this).edit().putString(BuildBackendSettings.KEY_BOOTSTRAP_URL, url).apply();
        setRuntimeBusy(true);
        setRuntimeStatus(getString(R.string.runtime_downloading));
        new RuntimeInstaller(this).installFromUrlAsync(url, new RuntimeInstaller.Callback() {
            @Override
            public void onSuccess(String status) {
                runOnUiThread(() -> {
                    setRuntimeBusy(false);
                    setRuntimeStatus(status);
                    Toast.makeText(SettingsActivity.this, R.string.runtime_installed, Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onError(Exception error) {
                runOnUiThread(() -> {
                    setRuntimeBusy(false);
                    setRuntimeStatus(error.getMessage());
                    Toast.makeText(SettingsActivity.this, getString(R.string.runtime_install_failed, error.getMessage()), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void setRuntimeBusy(boolean busy) {
        findViewById(R.id.installBundledRuntimeButton).setEnabled(!busy);
        findViewById(R.id.downloadRuntimeButton).setEnabled(!busy);
        findViewById(R.id.refreshRuntimeStatusButton).setEnabled(!busy);
    }

    private void setRuntimeStatus(String value) {
        runtimeStatusText.setText(value == null ? "" : value);
    }

    private void runTermuxSetup() {
        try {
            TermuxBridge bridge = new TermuxBridge(this);
            if (!bridge.isTermuxInstalled()) {
                Toast.makeText(this, R.string.termux_not_installed, Toast.LENGTH_LONG).show();
                return;
            }
            if (!bridge.hasRunCommandPermission()) {
                requestTermuxPermission();
                return;
            }
            if (!bridge.canTermuxDrawOverlays()) {
                Toast.makeText(this, R.string.termux_overlay_required, Toast.LENGTH_LONG).show();
                startActivity(bridge.termuxOverlaySettingsIntent());
                return;
            }
            String setup = readAsset("termux/setup-termux.sh");
            String build = readAsset("termux/build.sh");
            bridge.setup(setup, build);
            Toast.makeText(this, R.string.termux_setup_started, Toast.LENGTH_LONG).show();
        } catch (Exception error) {
            Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void requestTermuxPermission() {
        TermuxBridge bridge = new TermuxBridge(this);
        if (!bridge.isTermuxInstalled()) {
            Toast.makeText(this, R.string.termux_not_installed, Toast.LENGTH_LONG).show();
            return;
        }
        if (bridge.hasRunCommandPermission()) {
            Toast.makeText(this, R.string.termux_permission_granted, Toast.LENGTH_SHORT).show();
            return;
        }
        requestPermissions(new String[]{TermuxBridge.RUN_COMMAND_PERMISSION}, REQUEST_TERMUX_RUN_COMMAND);
        Toast.makeText(this, R.string.termux_permission_required, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_TERMUX_RUN_COMMAND) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                runTermuxSetup();
            } else {
                Toast.makeText(this, R.string.termux_permission_denied, Toast.LENGTH_LONG).show();
                openOwnAppSettings();
            }
        }
    }

    private void openOwnAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private String readAsset(String path) throws Exception {
        try (InputStream in = getAssets().open(path); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            FileUtils.copy(in, out);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private int providerIndex(String provider) {
        if (OpenAiClient.PROVIDER_DEEPSEEK.equals(provider)) {
            return 1;
        }
        if (OpenAiClient.PROVIDER_CUSTOM.equals(provider)) {
            return 2;
        }
        return 0;
    }

    private String providerAt(int position) {
        if (position == 1) {
            return OpenAiClient.PROVIDER_DEEPSEEK;
        }
        if (position == 2) {
            return OpenAiClient.PROVIDER_CUSTOM;
        }
        return OpenAiClient.PROVIDER_OPENAI;
    }

    private int languageIndex(String language) {
        if (AppSettings.LANGUAGE_EN.equals(language)) {
            return 1;
        }
        if (AppSettings.LANGUAGE_ZH.equals(language)) {
            return 2;
        }
        return 0;
    }

    private String languageAt(int position) {
        if (position == 1) {
            return AppSettings.LANGUAGE_EN;
        }
        if (position == 2) {
            return AppSettings.LANGUAGE_ZH;
        }
        return AppSettings.LANGUAGE_SYSTEM;
    }

    private int backendIndex(String backend) {
        return BuildBackendSettings.EXTERNAL_TERMUX.equals(backend) ? 1 : 0;
    }

    private String backendAt(int position) {
        return position == 1 ? BuildBackendSettings.EXTERNAL_TERMUX : BuildBackendSettings.EMBEDDED;
    }
}
