package com.androidbuilder.backend;

import android.content.Context;

import com.androidbuilder.embeddedruntime.EmbeddedRuntime;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class RuntimeInstaller {
    public interface Callback {
        void onSuccess(String status);
        void onError(Exception error);
    }

    private static final String[] ASSET_BOOTSTRAP_CANDIDATES = {
            "runtime/bootstrap-aarch64.zip",
            "runtime/bootstrap-arm64.zip"
    };

    private final Context context;
    private final EmbeddedRuntime runtime;

    public RuntimeInstaller(Context context) {
        this.context = context.getApplicationContext();
        this.runtime = new EmbeddedRuntime(this.context);
    }

    public String status() throws Exception {
        runtime.initializeLayout();
        return runtime.statusText();
    }

    public void installBundledAsync(Callback callback) {
        new Thread(() -> {
            try (InputStream in = openBundledBootstrap()) {
                runtime.installBootstrap(new BufferedInputStream(in));
                callback.onSuccess(runtime.statusText());
            } catch (Exception error) {
                callback.onError(error);
            }
        }, "runtime-install-bundled").start();
    }

    public void installFromUrlAsync(String url, Callback callback) {
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(20000);
                connection.setReadTimeout(120000);
                connection.setInstanceFollowRedirects(true);
                int code = connection.getResponseCode();
                if (code < 200 || code >= 300) {
                    throw new IllegalStateException("Download failed: HTTP " + code);
                }
                try (InputStream in = new BufferedInputStream(connection.getInputStream())) {
                    runtime.installBootstrap(in);
                }
                callback.onSuccess(runtime.statusText());
            } catch (Exception error) {
                callback.onError(error);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }, "runtime-install-url").start();
    }

    private InputStream openBundledBootstrap() throws Exception {
        Exception lastError = null;
        for (String asset : ASSET_BOOTSTRAP_CANDIDATES) {
            try {
                return context.getAssets().open(asset);
            } catch (Exception error) {
                lastError = error;
            }
        }
        throw new IllegalStateException("Bundled bootstrap zip not found. Expected one of: runtime/bootstrap-aarch64.zip, runtime/bootstrap-arm64.zip", lastError);
    }
}
