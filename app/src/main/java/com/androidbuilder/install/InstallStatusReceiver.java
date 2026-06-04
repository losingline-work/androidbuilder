package com.androidbuilder.install;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

import com.androidbuilder.R;

public class InstallStatusReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        InstallStatusPolicy.Result result = InstallStatusPolicy.resultFor(status);
        if (result == InstallStatusPolicy.Result.SUCCESS) {
            Toast.makeText(context, R.string.install_success, Toast.LENGTH_LONG).show();
        } else if (result == InstallStatusPolicy.Result.PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(confirm);
            }
        } else {
            Toast.makeText(context, context.getString(R.string.install_failed, message == null ? String.valueOf(status) : message), Toast.LENGTH_LONG).show();
        }
    }
}
