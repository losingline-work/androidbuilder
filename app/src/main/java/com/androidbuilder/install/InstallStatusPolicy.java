package com.androidbuilder.install;

import android.content.pm.PackageInstaller;

public final class InstallStatusPolicy {
    public enum Result {
        SUCCESS,
        PENDING_USER_ACTION,
        FAILURE
    }

    private InstallStatusPolicy() {
    }

    public static Result resultFor(int status) {
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            return Result.PENDING_USER_ACTION;
        }
        if (status == PackageInstaller.STATUS_SUCCESS) {
            return Result.SUCCESS;
        }
        return Result.FAILURE;
    }
}
