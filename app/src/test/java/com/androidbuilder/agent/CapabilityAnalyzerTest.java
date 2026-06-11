package com.androidbuilder.agent;

import com.androidbuilder.backend.BuildBackendSettings;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CapabilityAnalyzerTest {
    @Test
    public void offlineSafeBlocksPlansThatRequireExternalLibraries() {
        CapabilityAssessment assessment = CapabilityAnalyzer.assess(
                "Use Room database and Retrofit networking for cloud sync.",
                BuildBackendSettings.DEPENDENCY_OFFLINE_SAFE,
                false);

        assertTrue(assessment.blocksExecution());
        assertTrue(assessment.message(true).contains("当前依赖模式无法执行"));
        assertTrue(assessment.message(false).contains("Room"));
    }

    @Test
    public void offlineSafeAllowsPlainSdkLocalApps() {
        CapabilityAssessment assessment = CapabilityAnalyzer.assess(
                "Create a Kotlin XML app with SQLiteOpenHelper and local CRUD screens.",
                BuildBackendSettings.DEPENDENCY_OFFLINE_SAFE,
                false);

        assertFalse(assessment.blocksExecution());
        assertTrue(assessment.message(false).contains("Low risk"));
    }

    @Test
    public void offlineSafeAllowsPlansThatRejectExternalLibraries() {
        CapabilityAssessment assessment = CapabilityAnalyzer.assess(
                "Use Android SDK Java XML and SQLiteOpenHelper. Do not use Room, Compose, Material, Retrofit, OkHttp, Glide, ViewBinding, or DataBinding.",
                BuildBackendSettings.DEPENDENCY_OFFLINE_SAFE,
                false);

        assertFalse(assessment.blocksExecution());
        assertTrue(assessment.message(false).contains("Low risk"));
    }

    @Test
    public void localCacheWarnsWhenCacheIsMissing() {
        CapabilityAssessment assessment = CapabilityAnalyzer.assess(
                "Use Material components for polished settings screens.",
                BuildBackendSettings.DEPENDENCY_LOCAL_CACHE,
                false);

        assertTrue(assessment.blocksExecution());
        assertTrue(assessment.message(true).contains("尚未导入本地 Maven 缓存"));
    }
}
