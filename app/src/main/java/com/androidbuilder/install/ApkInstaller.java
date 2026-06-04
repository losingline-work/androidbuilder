package com.androidbuilder.install;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;

import com.androidbuilder.R;
import com.androidbuilder.util.FileUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;

public class ApkInstaller {
    public static final String ACTION_INSTALL_STATUS = "com.androidbuilder.INSTALL_STATUS";
    public static final String EXTRA_EXPECTED_PACKAGE_NAME = "com.androidbuilder.EXTRA_EXPECTED_PACKAGE_NAME";

    private final Context context;

    public ApkInstaller(Context context) {
        this.context = context;
    }

    public boolean canRequestInstalls() {
        return context.getPackageManager().canRequestPackageInstalls();
    }

    public void openInstallSettings() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
        intent.setData(Uri.parse("package:" + context.getPackageName()));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    public void install(File apk) throws Exception {
        if (!apk.exists()) {
            throw new IllegalArgumentException("APK not found: " + apk);
        }
        if (!canRequestInstalls()) {
            openInstallSettings();
            Toast.makeText(context, R.string.install_permission_required, Toast.LENGTH_LONG).show();
            return;
        }
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        int sessionId = installer.createSession(params);
        PackageInstaller.Session session = installer.openSession(sessionId);
        try (OutputStream out = session.openWrite("app", 0, apk.length());
             FileInputStream in = new FileInputStream(apk)) {
            FileUtils.copy(in, out);
            session.fsync(out);
        }
        Intent intent = new Intent(ACTION_INSTALL_STATUS).setPackage(context.getPackageName());
        String packageName = packageName(apk);
        if (packageName != null) {
            intent.putExtra(EXTRA_EXPECTED_PACKAGE_NAME, packageName);
        }
        PendingIntent pendingIntent = PendingIntent.getBroadcast(context, sessionId, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        IntentSender sender = pendingIntent.getIntentSender();
        session.commit(sender);
        session.close();
    }

    private String packageName(File apk) {
        PackageInfo info = context.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0);
        return info == null ? null : info.packageName;
    }
}
