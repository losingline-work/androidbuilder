package com.androidbuilder.install;

import android.content.pm.PackageInstaller;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class InstallStatusPolicyTest {
    @Test
    public void treatsFailureCallbackAsFailureEvenWhenAnOlderVersionIsInstalled() {
        assertEquals(
                InstallStatusPolicy.Result.FAILURE,
                InstallStatusPolicy.resultFor(PackageInstaller.STATUS_FAILURE));
    }

    @Test
    public void reportsSuccessOnlyForSuccessStatus() {
        assertEquals(
                InstallStatusPolicy.Result.SUCCESS,
                InstallStatusPolicy.resultFor(PackageInstaller.STATUS_SUCCESS));
    }

    @Test
    public void keepsPendingUserActionSeparate() {
        assertEquals(
                InstallStatusPolicy.Result.PENDING_USER_ACTION,
                InstallStatusPolicy.resultFor(PackageInstaller.STATUS_PENDING_USER_ACTION));
    }
}
