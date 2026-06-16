package com.androidbuilder.agent;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HighVolumeTaskPolicyTest {
    @Test
    public void cannedHeavyPhasesAreHighVolume() {
        assertTrue(HighVolumeTaskPolicy.isHighVolume("drawable and layout XML"));
        assertTrue(HighVolumeTaskPolicy.isHighVolume("resources: values, themes, and menu"));
        assertTrue(HighVolumeTaskPolicy.isHighVolume("Java source wiring"));
        assertTrue(HighVolumeTaskPolicy.isHighVolume("  drawable and layout XML  "));
    }

    @Test
    public void otherTasksAreNotForcedHighVolume() {
        assertFalse(HighVolumeTaskPolicy.isHighVolume("Gradle skeleton and dependencies"));
        assertFalse(HighVolumeTaskPolicy.isHighVolume("Add a single settings screen"));
        assertFalse(HighVolumeTaskPolicy.isHighVolume(""));
        assertFalse(HighVolumeTaskPolicy.isHighVolume(null));
    }

    @Test
    public void titlesMatchTheCannedNormalizerPhases() {
        // Guards against drift: these must equal ImplementationTaskNormalizer's canned phase titles.
        assertTrue(HighVolumeTaskPolicy.isHighVolume("drawable and layout XML"));
        assertTrue(HighVolumeTaskPolicy.isHighVolume("resources: values, themes, and menu"));
    }
}
