package com.androidbuilder.agent;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OnlineDependencyPolicyTest {
    @Test
    public void approvedDependenciesRequireExactVersions() {
        assertTrue(OnlineDependencyPolicy.isApproved("androidx.core", "core-ktx", "1.13.1"));
        assertFalse(OnlineDependencyPolicy.isApproved("androidx.core", "core-ktx", "1.12.0"));
    }

    @Test
    public void promptListsStableDependenciesAndVersions() {
        String prompt = OnlineDependencyPolicy.prompt();

        assertTrue(prompt.contains("androidx.appcompat:appcompat:1.7.0"));
        assertTrue(prompt.contains("Do not add Kotlin"));
    }
}
