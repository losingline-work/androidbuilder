package com.androidbuilder.termux;

import android.app.AppOpsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.net.Uri;
import android.provider.Settings;
import android.util.Base64;
import android.widget.Toast;

import java.nio.charset.StandardCharsets;

public class TermuxBridge {
    public static final String RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND";
    private static final String TERMUX_PACKAGE = "com.termux";
    private static final String TERMUX_SERVICE = "com.termux.app.RunCommandService";

    private final Context context;

    public TermuxBridge(Context context) {
        this.context = context;
    }

    public void build(long projectId, long jobId, String callbackUrl, String token) {
        Intent intent = new Intent();
        intent.setClassName(TERMUX_PACKAGE, TERMUX_SERVICE);
        intent.setAction("com.termux.RUN_COMMAND");
        intent.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/home/androidbuilder/build.sh");
        intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{
                String.valueOf(projectId),
                String.valueOf(jobId),
                callbackUrl,
                token
        });
        intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home/androidbuilder");
        intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
        intent.setComponent(new ComponentName(TERMUX_PACKAGE, TERMUX_SERVICE));
        try {
            context.startService(intent);
        } catch (Exception error) {
            Toast.makeText(context, context.getString(com.androidbuilder.R.string.termux_start_failed), Toast.LENGTH_LONG).show();
        }
    }

    public boolean isTermuxInstalled() {
        try {
            context.getPackageManager().getPackageInfo(TERMUX_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException error) {
            return false;
        }
    }

    public boolean hasRunCommandPermission() {
        return context.checkSelfPermission(RUN_COMMAND_PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    public boolean canTermuxDrawOverlays() {
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(TERMUX_PACKAGE, 0);
            AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            int mode;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                mode = appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, info.uid, TERMUX_PACKAGE);
            } else {
                mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, info.uid, TERMUX_PACKAGE);
            }
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception error) {
            return false;
        }
    }

    public Intent termuxOverlaySettingsIntent() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
        intent.setData(Uri.parse("package:" + TERMUX_PACKAGE));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    public void setup(String setupScript, String buildScript) {
        String setup64 = Base64.encodeToString(setupScript.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        String build64 = Base64.encodeToString(buildScript.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        String command = "set -e; " +
                "mkdir -p \"$HOME/androidbuilder\"; " +
                "printf '%s' '" + setup64 + "' | base64 -d > \"$HOME/androidbuilder/setup-termux.sh\"; " +
                "printf '%s' '" + build64 + "' | base64 -d > \"$HOME/androidbuilder/build.sh\"; " +
                "chmod +x \"$HOME/androidbuilder/setup-termux.sh\" \"$HOME/androidbuilder/build.sh\"; " +
                "\"$HOME/androidbuilder/setup-termux.sh\"";
        Intent intent = new Intent();
        intent.setClassName(TERMUX_PACKAGE, TERMUX_SERVICE);
        intent.setAction("com.termux.RUN_COMMAND");
        intent.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash");
        intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{"-lc", command});
        intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home");
        intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", false);
        intent.setComponent(new ComponentName(TERMUX_PACKAGE, TERMUX_SERVICE));
        try {
            context.startService(intent);
        } catch (SecurityException error) {
            Toast.makeText(context, context.getString(com.androidbuilder.R.string.termux_run_command_permission_missing), Toast.LENGTH_LONG).show();
        } catch (Exception error) {
            Toast.makeText(context, context.getString(com.androidbuilder.R.string.termux_setup_start_failed, error.getMessage()), Toast.LENGTH_LONG).show();
        }
    }
}
