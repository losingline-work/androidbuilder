package com.androidbuilder.agent;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OnlineDependencyPolicyTest {
    @Test
    public void approvedExactCoordinatesStayAllowed() {
        assertTrue(OnlineDependencyPolicy.isApproved("androidx.core", "core-ktx", "1.13.1"));
        assertTrue(OnlineDependencyPolicy.isApproved("com.google.android.material", "material", "1.12.0"));
    }

    @Test
    public void trustedGroupsAllowAnyPinnedVersion() {
        assertTrue(OnlineDependencyPolicy.isApproved("androidx.appcompat", "appcompat", "1.6.1"));
        assertTrue(OnlineDependencyPolicy.isApproved("androidx.viewpager2", "viewpager2", "1.1.0"));
        assertTrue(OnlineDependencyPolicy.isApproved("com.google.code.gson", "gson", "2.11.0"));
        assertTrue(OnlineDependencyPolicy.isApproved("com.google.guava", "guava", "33.3.1-android"));
        assertTrue(OnlineDependencyPolicy.isApproved("org.apache.commons", "commons-lang3", "3.17.0"));
    }

    @Test
    public void additionalPureJavaUtilityGroupsAllowed() {
        assertTrue(OnlineDependencyPolicy.isApproved("commons-io", "commons-io", "2.16.1"));
        assertTrue(OnlineDependencyPolicy.isApproved("commons-codec", "commons-codec", "1.17.1"));
        assertTrue(OnlineDependencyPolicy.isApproved("joda-time", "joda-time", "2.13.0"));
        assertTrue(OnlineDependencyPolicy.isApproved("com.google.code.findbugs", "jsr305", "3.0.2"));
    }

    @Test
    public void standardTestDependenciesAreApproved() {
        assertTrue(OnlineDependencyPolicy.isApproved("junit", "junit", "4.13.2"));
        assertTrue(OnlineDependencyPolicy.isApproved("androidx.test.ext", "junit", "1.2.1"));
        assertTrue(OnlineDependencyPolicy.isApproved("androidx.test.espresso", "espresso-core", "3.6.1"));
    }

    @Test
    public void untrustedGroupsRejected() {
        // Untrusted group, not cataloged.
        assertFalse(OnlineDependencyPolicy.isApproved("com.jakewharton.timber", "timber", "5.0.1"));
        // Cataloged artifact but the wrong (non-verified) version is still rejected.
        assertFalse(OnlineDependencyPolicy.isApproved("com.github.bumptech.glide", "glide", "4.15.0"));
        // Different group from the cataloged rxjava3.
        assertFalse(OnlineDependencyPolicy.isApproved("io.reactivex.rxjava2", "rxjava", "2.2.21"));
    }

    @Test
    public void catalogedCapabilityLibrariesApproved() {
        assertTrue(OnlineDependencyPolicy.isApproved("com.github.PhilJay", "MPAndroidChart", "v3.1.0"));
        assertTrue(OnlineDependencyPolicy.isApproved("com.squareup.retrofit2", "retrofit", "2.11.0"));
        assertTrue(OnlineDependencyPolicy.isApproved("io.reactivex.rxjava3", "rxjava", "3.1.9"));
    }

    @Test
    public void dynamicVersionsRejectedEvenForTrustedGroups() {
        assertFalse(OnlineDependencyPolicy.isApproved("androidx.appcompat", "appcompat", "1.+"));
        assertFalse(OnlineDependencyPolicy.isApproved("androidx.appcompat", "appcompat", "latest.release"));
        assertFalse(OnlineDependencyPolicy.isApproved("androidx.appcompat", "appcompat", "1.7.0-SNAPSHOT"));
        assertFalse(OnlineDependencyPolicy.isPinnedVersion("1.+"));
        assertFalse(OnlineDependencyPolicy.isPinnedVersion("[1.0,2.0)"));
        assertTrue(OnlineDependencyPolicy.isPinnedVersion("1.2.3"));
        assertTrue(OnlineDependencyPolicy.isPinnedVersion("33.3.1-android"));
    }

    @Test
    public void kotlinComposeAndProcessorLibrariesRejectedUnderTrustedGroups() {
        assertFalse(OnlineDependencyPolicy.isApproved("androidx.compose.ui", "ui", "1.7.0"));
        assertFalse(OnlineDependencyPolicy.isApproved("androidx.room", "room-runtime", "2.6.1"));
        assertFalse(OnlineDependencyPolicy.isApproved("androidx.room", "room-compiler", "2.6.1"));
        assertFalse(OnlineDependencyPolicy.isApproved("androidx.hilt", "hilt-navigation-fragment", "1.2.0"));
        assertFalse(OnlineDependencyPolicy.isApproved("com.google.dagger", "hilt-android", "2.52"));
        assertFalse(OnlineDependencyPolicy.isApproved("androidx.lifecycle", "lifecycle-compiler", "2.8.7"));
    }

    @Test
    public void promptListsTrustedGroupsAndBans() {
        String prompt = OnlineDependencyPolicy.prompt();

        assertTrue(prompt.contains("androidx.*"));
        assertTrue(prompt.contains("exact pinned version"));
        assertTrue(prompt.contains("Do not add Kotlin"));
        assertTrue(prompt.contains("androidx.appcompat:appcompat:1.7.0"));
    }
}
