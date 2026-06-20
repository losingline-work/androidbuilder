package com.androidbuilder.agent;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DependencyCatalogTest {
    @Test
    public void catalogedCoordinatesMatchExactVersionOnly() {
        assertTrue(DependencyCatalog.isCataloged("com.github.PhilJay", "MPAndroidChart", "v3.1.0"));
        assertTrue(DependencyCatalog.isCataloged("com.squareup.okhttp3", "okhttp", "3.12.13"));
        assertFalse(DependencyCatalog.isCataloged("com.github.PhilJay", "MPAndroidChart", "v3.0.0"));
        assertFalse(DependencyCatalog.isCataloged("com.unknown", "thing", "1.0"));
    }

    @Test
    public void noCatalogEntryIsAnAnnotationProcessorOrBlockedCoordinate() {
        for (DependencyCatalog.Entry entry : DependencyCatalog.entries()) {
            assertFalse(entry.coordinate(), OnlineDependencyPolicy.isBlockedCoordinate(entry.group, entry.artifact));
            assertTrue(entry.coordinate(), OnlineDependencyPolicy.isPinnedVersion(entry.version));
        }
    }

    @Test
    public void versionMismatchSuggestsThePinnedVersion() {
        String advice = DependencyCatalog.substituteAdvice("com.github.PhilJay", "MPAndroidChart");
        assertTrue(advice.contains("v3.1.0"));
    }

    @Test
    public void useCaseMatchingSuggestsSubstitutes() {
        // An off-catalog chart library maps to MPAndroidChart.
        assertTrue(DependencyCatalog.substituteAdvice("com.example", "supercharts").contains("MPAndroidChart"));
        // An off-catalog image loader maps to a cataloged image library.
        assertTrue(DependencyCatalog.substituteAdvice("com.facebook", "fresco").contains("image"));
        // Unrelated coordinates produce no advice.
        assertEquals("", DependencyCatalog.substituteAdvice("com.example", "leftpad"));
    }

    @Test
    public void promptSummaryAdvertisesChartsHttpAndPermissions() {
        String prompt = DependencyCatalog.promptSummary();
        assertTrue(prompt.contains("MPAndroidChart:v3.1.0"));
        assertTrue(prompt.contains("INTERNET permission"));
        assertTrue(prompt.contains("Android SDK"));
    }

    @Test
    public void offlineBundleCoordinatesIncludeCapabilityLibsAndBaseToolchain() {
        java.util.List<String> coords = DependencyCatalog.offlineBundleCoordinates();
        // every capability library is in the bundle
        for (DependencyCatalog.Entry entry : DependencyCatalog.entries()) {
            assertTrue(entry.coordinate(), coords.contains(entry.coordinate()));
        }
        // base toolchain/UI the offline build also needs
        assertTrue(coords.contains("com.android.tools.build:gradle:8.7.3"));
        assertTrue(coords.contains("com.google.android.material:material:1.12.0"));
        assertTrue(coords.contains("org.jetbrains.kotlin:kotlin-stdlib:1.8.22"));
        assertTrue(coords.contains("com.github.PhilJay:MPAndroidChart:v3.1.0"));
    }
}
