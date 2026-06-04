package com.androidbuilder.agent;

public final class VersionUpgradePolicy {
    private VersionUpgradePolicy() {
    }

    public static String prompt() {
        return "Version upgrade rule: when the latest requirement, approved plan, or task mentions app version, release version, build number, upgrade, publish, beta, or APK iteration, update app/build.gradle in the same change. " +
                "Set versionCode to an integer greater than the current versionCode. Set versionName to the requested version when one is provided; otherwise increment the patch segment of the current versionName. " +
                "Do not downgrade versionCode or versionName. If app/build.gradle is missing versionCode or versionName, add both under defaultConfig.";
    }
}
