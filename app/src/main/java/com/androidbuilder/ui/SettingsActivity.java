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
import android.widget.AutoCompleteTextView;
import android.widget.EditText;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import com.androidbuilder.BuildConfig;
import com.androidbuilder.R;
import com.androidbuilder.agent.OpenAiClient;
import com.androidbuilder.backend.BuildBackendSettings;
import com.androidbuilder.backend.RuntimeInstaller;
import com.androidbuilder.termux.TermuxBridge;
import com.androidbuilder.util.AppSettings;
import com.androidbuilder.util.FileUtils;
import com.google.android.material.appbar.MaterialToolbar;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class SettingsActivity extends BaseActivity {
    private static final int REQUEST_TERMUX_RUN_COMMAND = 8001;
    private static final int REQUEST_OFFLINE_MAVEN_ZIP = 8002;
    private String[] providerLabels;
    private String[] languageLabels;
    private String[] backendLabels;
    private String[] dependencyModeLabels;
    private String[] deepseekModelLabels;
    private String[] minimaxModelLabels;

    private EditText apiKey;
    private EditText endpoint;
    private EditText model;
    private AutoCompleteTextView deepseekModelSpinner;
    private AutoCompleteTextView minimaxModelSpinner;
    private AutoCompleteTextView providerSpinner;
    private AutoCompleteTextView languageSpinner;
    private AutoCompleteTextView backendSpinner;
    private AutoCompleteTextView dependencyModeSpinner;
    private View termuxSection;
    private View deepseekModelLayout;
    private View minimaxModelLayout;
    private View modelInputLayout;
    private EditText runtimeBootstrapUrlInput;
    private TextView runtimeStatusText;
    private TextView offlineMavenStatusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        applySystemBarPadding();
        MaterialToolbar toolbar = findViewById(R.id.settingsToolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        apiKey = findViewById(R.id.apiKeyInput);
        endpoint = findViewById(R.id.endpointInput);
        model = findViewById(R.id.modelInput);
        deepseekModelSpinner = findViewById(R.id.deepseekModelSpinner);
        minimaxModelSpinner = findViewById(R.id.minimaxModelSpinner);
        deepseekModelLayout = findViewById(R.id.deepseekModelLayout);
        minimaxModelLayout = findViewById(R.id.minimaxModelLayout);
        modelInputLayout = findViewById(R.id.modelInputLayout);
        providerSpinner = findViewById(R.id.providerSpinner);
        languageSpinner = findViewById(R.id.languageSpinner);
        backendSpinner = findViewById(R.id.backendSpinner);
        dependencyModeSpinner = findViewById(R.id.dependencyModeSpinner);
        termuxSection = findViewById(R.id.termuxSection);
        runtimeBootstrapUrlInput = findViewById(R.id.runtimeBootstrapUrlInput);
        runtimeStatusText = findViewById(R.id.runtimeStatusText);
        offlineMavenStatusText = findViewById(R.id.offlineMavenStatusText);
        configureSpinners();
        SharedPreferences prefs = getSharedPreferences(OpenAiClient.PREFS, MODE_PRIVATE);
        apiKey.setText(prefs.getString(OpenAiClient.KEY_API_KEY, ""));
        String provider = prefs.getString(OpenAiClient.KEY_PROVIDER, OpenAiClient.PROVIDER_OPENAI);
        select(providerSpinner, providerLabels, providerIndex(provider));
        endpoint.setText(prefs.getString(OpenAiClient.KEY_ENDPOINT, OpenAiClient.defaultEndpoint(provider)));
        model.setText(prefs.getString(OpenAiClient.KEY_MODEL, OpenAiClient.defaultModel(provider)));
        select(deepseekModelSpinner, deepseekModelLabels, deepseekModelIndex(prefs.getString(OpenAiClient.KEY_MODEL, OpenAiClient.defaultModel(provider))));
        select(minimaxModelSpinner, minimaxModelLabels, minimaxModelIndex(prefs.getString(OpenAiClient.KEY_MODEL, OpenAiClient.defaultModel(provider))));
        select(languageSpinner, languageLabels, languageIndex(AppSettings.language(this)));
        select(backendSpinner, backendLabels, backendIndex(BuildBackendSettings.selected(this)));
        select(dependencyModeSpinner, dependencyModeLabels, dependencyModeIndex(BuildBackendSettings.dependencyMode(this)));
        runtimeBootstrapUrlInput.setText(BuildBackendSettings.prefs(this).getString(BuildBackendSettings.KEY_BOOTSTRAP_URL, ""));
        findViewById(R.id.saveSettingsButton).setOnClickListener(v -> save());
        findViewById(R.id.importOfflineMavenButton).setOnClickListener(v -> importOfflineMaven());
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
        ((TextView) findViewById(R.id.appVersionText)).setText(getString(R.string.app_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE));
        updateTermuxSectionVisibility();
        updateModelInputVisibility();
        refreshOfflineMavenStatus();
        refreshRuntimeStatus();
    }

    private void configureSpinners() {
        providerLabels = new String[]{getString(R.string.provider_openai), getString(R.string.provider_deepseek), getString(R.string.provider_minimax), getString(R.string.provider_custom)};
        languageLabels = new String[]{getString(R.string.language_system), "English", "中文"};
        backendLabels = new String[]{getString(R.string.backend_embedded), getString(R.string.backend_external_termux)};
        dependencyModeLabels = new String[]{getString(R.string.dependency_mode_offline_safe), getString(R.string.dependency_mode_local_cache), getString(R.string.dependency_mode_online)};
        deepseekModelLabels = new String[]{getString(R.string.deepseek_model_v4_flash), getString(R.string.deepseek_model_v4_pro)};
        minimaxModelLabels = new String[]{getString(R.string.minimax_model_m2), getString(R.string.minimax_model_m1), getString(R.string.minimax_model_text_01)};
        configureDropdown(providerSpinner, providerLabels);
        configureDropdown(languageSpinner, languageLabels);
        configureDropdown(backendSpinner, backendLabels);
        configureDropdown(dependencyModeSpinner, dependencyModeLabels);
        configureDropdown(deepseekModelSpinner, deepseekModelLabels);
        configureDropdown(minimaxModelSpinner, minimaxModelLabels);
        providerSpinner.setOnItemClickListener((parent, view, position, id) -> {
            String provider = providerAt(position);
            if (!OpenAiClient.PROVIDER_CUSTOM.equals(provider)) {
                endpoint.setText(OpenAiClient.defaultEndpoint(provider));
                if (!OpenAiClient.PROVIDER_DEEPSEEK.equals(provider) && !OpenAiClient.PROVIDER_MINIMAX.equals(provider)) {
                    model.setText(OpenAiClient.defaultModel(provider));
                }
            }
            updateModelInputVisibility();
        });
        backendSpinner.setOnItemClickListener((parent, view, position, id) -> updateTermuxSectionVisibility());
    }

    private void configureDropdown(AutoCompleteTextView view, String[] labels) {
        view.setAdapter(new ArrayAdapter<>(this, R.layout.row_dropdown_item, labels));
        view.setThreshold(0);
    }

    private void updateModelInputVisibility() {
        String provider = providerAt(selectedIndex(providerSpinner, providerLabels));
        boolean isDeepSeek = OpenAiClient.PROVIDER_DEEPSEEK.equals(provider);
        boolean isMiniMax = OpenAiClient.PROVIDER_MINIMAX.equals(provider);
        deepseekModelLayout.setVisibility(isDeepSeek ? View.VISIBLE : View.GONE);
        minimaxModelLayout.setVisibility(isMiniMax ? View.VISIBLE : View.GONE);
        modelInputLayout.setVisibility((isDeepSeek || isMiniMax) ? View.GONE : View.VISIBLE);
    }

    private void save() {
        String provider = providerAt(selectedIndex(providerSpinner, providerLabels));
        String selectedModel;
        if (OpenAiClient.PROVIDER_DEEPSEEK.equals(provider)) {
            selectedModel = deepseekModelAt(selectedIndex(deepseekModelSpinner, deepseekModelLabels));
        } else if (OpenAiClient.PROVIDER_MINIMAX.equals(provider)) {
            selectedModel = minimaxModelAt(selectedIndex(minimaxModelSpinner, minimaxModelLabels));
        } else {
            selectedModel = OpenAiClient.normalizedModel(provider, model.getText().toString());
        }
        getSharedPreferences(OpenAiClient.PREFS, MODE_PRIVATE)
                .edit()
                .putString(OpenAiClient.KEY_PROVIDER, provider)
                .putString(OpenAiClient.KEY_API_KEY, apiKey.getText().toString().trim())
                .putString(OpenAiClient.KEY_ENDPOINT, endpoint.getText().toString().trim())
                .putString(OpenAiClient.KEY_MODEL, selectedModel)
                .apply();
        AppSettings.prefs(this).edit().putString(AppSettings.KEY_LANGUAGE, languageAt(selectedIndex(languageSpinner, languageLabels))).apply();
        BuildBackendSettings.setSelected(this, backendAt(selectedIndex(backendSpinner, backendLabels)));
        BuildBackendSettings.setDependencyMode(this, dependencyModeAt(selectedIndex(dependencyModeSpinner, dependencyModeLabels)));
        BuildBackendSettings.prefs(this).edit().putString(BuildBackendSettings.KEY_BOOTSTRAP_URL, runtimeBootstrapUrlInput.getText().toString().trim()).apply();
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
        recreate();
    }

    private void updateTermuxSectionVisibility() {
        if (termuxSection != null) {
            termuxSection.setVisibility(BuildBackendSettings.EXTERNAL_TERMUX.equals(backendAt(selectedIndex(backendSpinner, backendLabels))) ? View.VISIBLE : View.GONE);
        }
    }

    private void select(AutoCompleteTextView view, String[] labels, int index) {
        if (labels.length == 0) {
            return;
        }
        int safeIndex = Math.max(0, Math.min(index, labels.length - 1));
        view.setText(labels[safeIndex], false);
    }

    private int selectedIndex(AutoCompleteTextView view, String[] labels) {
        String value = view.getText() == null ? "" : view.getText().toString();
        for (int index = 0; index < labels.length; index++) {
            if (labels[index].equals(value)) {
                return index;
            }
        }
        return 0;
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
        findViewById(R.id.importOfflineMavenButton).setEnabled(!busy);
    }

    private void setRuntimeStatus(String value) {
        runtimeStatusText.setText(value == null ? "" : value);
    }

    private void importOfflineMaven() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        startActivityForResult(intent, REQUEST_OFFLINE_MAVEN_ZIP);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_OFFLINE_MAVEN_ZIP && resultCode == RESULT_OK && data != null && data.getData() != null) {
            installOfflineMaven(data.getData());
        }
    }

    private void installOfflineMaven(Uri uri) {
        setRuntimeBusy(true);
        new Thread(() -> {
            try {
                java.io.File target = BuildBackendSettings.offlineMavenDir(this);
                FileUtils.deleteRecursively(target);
                unzipOfflineMaven(uri, target);
                runOnUiThread(() -> {
                    setRuntimeBusy(false);
                    refreshOfflineMavenStatus();
                    Toast.makeText(this, R.string.offline_maven_imported, Toast.LENGTH_LONG).show();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    setRuntimeBusy(false);
                    Toast.makeText(this, getString(R.string.offline_maven_import_failed, error.getMessage()), Toast.LENGTH_LONG).show();
                    refreshOfflineMavenStatus();
                });
            }
        }, "offline-maven-import").start();
    }

    private void unzipOfflineMaven(Uri uri, java.io.File targetDir) throws Exception {
        String rootPath = targetDir.getCanonicalPath();
        InputStream raw = getContentResolver().openInputStream(uri);
        if (raw == null) {
            throw new java.io.IOException("Cannot open selected zip.");
        }
        try (InputStream in = raw; java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(in)) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                java.io.File target = new java.io.File(targetDir, entry.getName());
                String targetPath = target.getCanonicalPath();
                if (!targetPath.equals(rootPath) && !targetPath.startsWith(rootPath + java.io.File.separator)) {
                    throw new IllegalArgumentException("Unsafe zip entry: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    target.mkdirs();
                } else {
                    java.io.File parent = target.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs()) {
                        throw new java.io.IOException("Cannot create directory: " + parent);
                    }
                    try (java.io.FileOutputStream out = new java.io.FileOutputStream(target)) {
                        FileUtils.copy(zip, out);
                    }
                }
                zip.closeEntry();
            }
        }
    }

    private void refreshOfflineMavenStatus() {
        java.io.File dir = BuildBackendSettings.offlineMavenDir(this);
        int artifacts = countFiles(dir, ".pom", ".aar", ".jar");
        offlineMavenStatusText.setText(artifacts == 0
                ? getString(R.string.offline_maven_empty)
                : getString(R.string.offline_maven_ready, artifacts, dir.getAbsolutePath()));
    }

    private int countFiles(java.io.File dir, String... suffixes) {
        if (dir == null || !dir.exists()) {
            return 0;
        }
        if (dir.isFile()) {
            String name = dir.getName().toLowerCase(java.util.Locale.ROOT);
            for (String suffix : suffixes) {
                if (name.endsWith(suffix)) {
                    return 1;
                }
            }
            return 0;
        }
        int count = 0;
        java.io.File[] children = dir.listFiles();
        if (children != null) {
            for (java.io.File child : children) {
                count += countFiles(child, suffixes);
            }
        }
        return count;
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
        if (OpenAiClient.PROVIDER_MINIMAX.equals(provider)) {
            return 2;
        }
        if (OpenAiClient.PROVIDER_CUSTOM.equals(provider)) {
            return 3;
        }
        return 0;
    }

    private String providerAt(int position) {
        if (position == 1) {
            return OpenAiClient.PROVIDER_DEEPSEEK;
        }
        if (position == 2) {
            return OpenAiClient.PROVIDER_MINIMAX;
        }
        if (position == 3) {
            return OpenAiClient.PROVIDER_CUSTOM;
        }
        return OpenAiClient.PROVIDER_OPENAI;
    }

    private int deepseekModelIndex(String model) {
        if (OpenAiClient.DEEPSEEK_MODEL_PRO.equals(model)) {
            return 1;
        }
        return 0;
    }

    private String deepseekModelAt(int position) {
        if (position == 1) {
            return OpenAiClient.DEEPSEEK_MODEL_PRO;
        }
        return OpenAiClient.DEEPSEEK_MODEL_FLASH;
    }

    private int minimaxModelIndex(String model) {
        if (OpenAiClient.MINIMAX_MODEL_M1.equals(model)) {
            return 1;
        }
        if (OpenAiClient.MINIMAX_MODEL_TEXT_01.equals(model)) {
            return 2;
        }
        return 0;
    }

    private String minimaxModelAt(int position) {
        if (position == 1) {
            return OpenAiClient.MINIMAX_MODEL_M1;
        }
        if (position == 2) {
            return OpenAiClient.MINIMAX_MODEL_TEXT_01;
        }
        return OpenAiClient.MINIMAX_MODEL_M2;
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

    private int dependencyModeIndex(String mode) {
        if (BuildBackendSettings.DEPENDENCY_LOCAL_CACHE.equals(mode)) {
            return 1;
        }
        if (BuildBackendSettings.DEPENDENCY_ONLINE.equals(mode)) {
            return 2;
        }
        return 0;
    }

    private String dependencyModeAt(int position) {
        if (position == 1) {
            return BuildBackendSettings.DEPENDENCY_LOCAL_CACHE;
        }
        if (position == 2) {
            return BuildBackendSettings.DEPENDENCY_ONLINE;
        }
        return BuildBackendSettings.DEPENDENCY_OFFLINE_SAFE;
    }
}
