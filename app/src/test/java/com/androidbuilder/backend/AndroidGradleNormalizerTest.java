package com.androidbuilder.backend;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AndroidGradleNormalizerTest {
    @Test
    public void groovyNormalizerDoesNotInjectKotlinOptions() {
        String build = "plugins { id 'com.android.application' }\n" +
                "android { namespace 'com.example.app'; compileSdk 34\n" +
                "    defaultConfig { applicationId 'com.example.app' }\n" +
                "}\n";

        String normalized = AndroidGradleNormalizer.normalizeJvmTargets(build, false);

        assertTrue(normalized.contains("compileOptions"));
        assertTrue(normalized.contains("sourceCompatibility JavaVersion.VERSION_1_8"));
        assertTrue(normalized.contains("targetCompatibility JavaVersion.VERSION_1_8"));
        assertFalse(normalized.contains("kotlinOptions"));
    }

    @Test
    public void normalizerRemovesExistingKotlinOptions() {
        String build = "android {\n" +
                "    compileSdk 34\n" +
                "    kotlinOptions {\n" +
                "        jvmTarget = '17'\n" +
                "    }\n" +
                "}\n";

        String normalized = AndroidGradleNormalizer.normalizeJvmTargets(build, false);

        assertFalse(normalized.contains("kotlinOptions"));
        assertFalse(normalized.contains("jvmTarget"));
    }

    @Test
    public void normalizerDowngradesJava17CompatibilityToJava8() {
        String build = "android {\n" +
                "    compileOptions {\n" +
                "        sourceCompatibility JavaVersion.VERSION_17\n" +
                "        targetCompatibility JavaVersion.VERSION_17\n" +
                "    }\n" +
                "}\n";

        String normalized = AndroidGradleNormalizer.normalizeJvmTargets(build, false);

        assertTrue(normalized.contains("sourceCompatibility JavaVersion.VERSION_1_8"));
        assertTrue(normalized.contains("targetCompatibility JavaVersion.VERSION_1_8"));
        assertFalse(normalized.contains("VERSION_17"));
    }

    @Test
    public void rootBuildWithEmptyPluginsGetsAndroidApplicationPluginVersion() {
        String normalized = AndroidGradleNormalizer.ensureRootAndroidApplicationPlugin("plugins {}\n");

        assertTrue(normalized.contains("id 'com.android.application' version '8.7.3' apply false"));
    }

    @Test
    public void rootBuildWithUnversionedAndroidPluginGetsVersion() {
        String normalized = AndroidGradleNormalizer.ensureRootAndroidApplicationPlugin(
                "plugins {\n" +
                        "    id 'com.android.application'\n" +
                        "}\n");

        assertTrue(normalized.contains("id 'com.android.application' version '8.7.3' apply false"));
    }

    @Test
    public void gradlePropertiesEnableAndroidXWhenAndroidXDependenciesExist() {
        String normalized = AndroidGradleNormalizer.normalizeGradleProperties(
                "android.useAndroidX=false\norg.gradle.daemon=true\n",
                true,
                "/runtime/bin/aapt2");

        assertTrue(normalized.contains("android.useAndroidX=true"));
        assertFalse(normalized.contains("android.useAndroidX=false"));
        assertTrue(normalized.contains("org.gradle.daemon=false"));
        assertTrue(normalized.contains("android.aapt2FromMavenOverride=/runtime/bin/aapt2"));
    }

    @Test
    public void settingsWithoutPluginManagementGetsAndroidPluginRepositories() {
        String normalized = AndroidGradleNormalizer.ensureSettingsPluginManagement("include ':app'\n");

        assertTrue(normalized.contains("pluginManagement"));
        assertTrue(normalized.contains("google()"));
        assertTrue(normalized.contains("gradlePluginPortal()"));
        assertTrue(normalized.contains("include ':app'"));
    }
}
