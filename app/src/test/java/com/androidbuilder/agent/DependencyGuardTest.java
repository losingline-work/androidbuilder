package com.androidbuilder.agent;

import com.androidbuilder.backend.BuildBackendSettings;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class DependencyGuardTest {
    @Test
    public void offlineSafeBlocksMavenDependencies() {
        DependencyGuard guard = new DependencyGuard(BuildBackendSettings.DEPENDENCY_OFFLINE_SAFE, new File("missing"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                guard.validateContent("app/build.gradle", "dependencies { implementation \"androidx.appcompat:appcompat:1.7.0\" }"));

        assertEquals("Dependency policy blocked Maven dependency: androidx.appcompat:appcompat:1.7.0", error.getMessage());
    }

    @Test
    public void offlineSafeAllowsBuildToolingDependencies() {
        DependencyGuard guard = new DependencyGuard(BuildBackendSettings.DEPENDENCY_OFFLINE_SAFE, new File("missing"));

        guard.validateContent("build.gradle", "buildscript { dependencies { classpath \"com.android.tools.build:gradle:8.7.3\" } }");
    }

    @Test
    public void blocksKotlinGradleConfiguration() {
        DependencyGuard guard = new DependencyGuard(BuildBackendSettings.DEPENDENCY_ONLINE, new File("missing"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                guard.validateContent("app/build.gradle", "android { kotlinOptions { jvmTarget = '17' } }"));

        assertEquals("Dependency policy blocked Kotlin Gradle configuration. Use Java source files and compileOptions only.", error.getMessage());
    }

    @Test
    public void blocksKotlinGradlePlugin() {
        DependencyGuard guard = new DependencyGuard(BuildBackendSettings.DEPENDENCY_ONLINE, new File("missing"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                guard.validateContent("app/build.gradle", "plugins { id 'org.jetbrains.kotlin.android' }"));

        assertEquals("Dependency policy blocked Kotlin Gradle configuration. Use Java source files and compileOptions only.", error.getMessage());
    }

    @Test
    public void onlineBlocksUnapprovedGradlePlugin() {
        DependencyGuard guard = new DependencyGuard(BuildBackendSettings.DEPENDENCY_ONLINE, new File("missing"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                guard.validateContent("app/build.gradle", "plugins { id 'com.google.devtools.ksp' }"));

        assertEquals("Dependency policy blocked Gradle plugin: com.google.devtools.ksp", error.getMessage());
    }

    @Test
    public void offlineSafeBlocksAndroidxCoreImport() {
        DependencyGuard guard = new DependencyGuard(BuildBackendSettings.DEPENDENCY_OFFLINE_SAFE, new File("missing"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                guard.validateContent("app/src/main/java/com/example/MainActivity.kt", "import androidx.core.view.isVisible\n"));

        assertEquals("Dependency policy blocked source import: androidx.core", error.getMessage());
    }

    @Test
    public void offlineSafeBlocksBinding() {
        DependencyGuard guard = new DependencyGuard(BuildBackendSettings.DEPENDENCY_OFFLINE_SAFE, new File("missing"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                guard.validateContent("app/build.gradle", "android { buildFeatures { viewBinding true } }"));

        assertEquals("Dependency policy blocked dataBinding/viewBinding/Compose. Use findViewById and plain XML.", error.getMessage());
    }

    @Test
    public void onlineAllowsApprovedMavenDependencies() {
        DependencyGuard guard = new DependencyGuard(BuildBackendSettings.DEPENDENCY_ONLINE, new File("missing"));

        guard.validateContent("app/build.gradle", "dependencies { implementation \"androidx.appcompat:appcompat:1.7.0\"; implementation \"com.google.android.material:material:1.12.0\" }");
    }

    @Test
    public void onlineBlocksUnapprovedMavenDependencies() {
        DependencyGuard guard = new DependencyGuard(BuildBackendSettings.DEPENDENCY_ONLINE, new File("missing"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                guard.validateContent("app/build.gradle", "dependencies { implementation \"com.squareup.retrofit2:retrofit:2.11.0\" }"));

        assertEquals("Dependency policy blocked unapproved online Maven dependency: com.squareup.retrofit2:retrofit:2.11.0", error.getMessage());
    }

    @Test
    public void onlineBlocksUnapprovedVersions() {
        DependencyGuard guard = new DependencyGuard(BuildBackendSettings.DEPENDENCY_ONLINE, new File("missing"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                guard.validateContent("app/build.gradle", "dependencies { implementation \"androidx.appcompat:appcompat:1.6.1\" }"));

        assertEquals("Dependency policy blocked unapproved online Maven dependency: androidx.appcompat:appcompat:1.6.1", error.getMessage());
    }

    @Test
    public void onlineBlocksBindingBuildFeatures() {
        DependencyGuard guard = new DependencyGuard(BuildBackendSettings.DEPENDENCY_ONLINE, new File("missing"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                guard.validateContent("app/build.gradle", "android { buildFeatures { dataBinding true } }"));

        assertEquals("Dependency policy blocked dataBinding/viewBinding. Use findViewById and plain XML.", error.getMessage());
    }
}
