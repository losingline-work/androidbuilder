package com.androidbuilder.agent;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BuildFailureClassifierTest {
    @Test
    public void unresolvedKotlinReferencesAreRepairable() {
        BuildFailureClassifier.Result result = BuildFailureClassifier.classify(
                "embedded_runtime_finished",
                "compileDebugKotlin FAILED Unresolved reference 'findViewById'");

        assertEquals(BuildFailureClassifier.Kind.KOTLIN_COMPILE, result.kind);
        assertTrue(result.repairableByModel);
    }

    @Test
    public void networkDependencyFailuresAreNotModelRepairable() {
        BuildFailureClassifier.Result result = BuildFailureClassifier.classify(
                "dependency_network_unavailable",
                "UnknownHostException: dl.google.com");

        assertEquals(BuildFailureClassifier.Kind.DEPENDENCY_NETWORK, result.kind);
        assertFalse(result.repairableByModel);
    }

    @Test
    public void mavenCentralTimeoutsAreNetworkFailures() {
        BuildFailureClassifier.Result result = BuildFailureClassifier.classify(
                "embedded_runtime_finished",
                "Could not download error_prone_annotations-2.15.0.jar from repo.maven.apache.org. Connect timed out");

        assertEquals(BuildFailureClassifier.Kind.DEPENDENCY_NETWORK, result.kind);
        assertFalse(result.repairableByModel);
    }

    @Test
    public void missingRuntimeToolsAreNotModelRepairable() {
        BuildFailureClassifier.Result result = BuildFailureClassifier.classify(
                "embedded_runtime_missing_tools",
                "Embedded runtime toolchain is incomplete.");

        assertEquals(BuildFailureClassifier.Kind.RUNTIME_ENVIRONMENT, result.kind);
        assertFalse(result.repairableByModel);
    }

    @Test
    public void jdkImageTransformFailuresAreRuntimeCompatibilityIssues() {
        BuildFailureClassifier.Result result = BuildFailureClassifier.classify(
                "embedded_runtime_finished",
                "Execution failed for JdkImageTransform: core-for-system-modules.jar. Error while executing process javac");

        assertEquals(BuildFailureClassifier.Kind.RUNTIME_ENVIRONMENT, result.kind);
        assertFalse(result.repairableByModel);
    }

    @Test
    public void buildTimeoutsAreNotModelRepairable() {
        BuildFailureClassifier.Result result = BuildFailureClassifier.classify(
                "embedded_runtime_timeout",
                "Build timed out after 30 minutes. Last output was 10 minutes ago.");

        assertEquals(BuildFailureClassifier.Kind.BUILD_TIMEOUT, result.kind);
        assertFalse(result.repairableByModel);
    }

    @Test
    public void kotlinStdlibDuplicateClassesAreDependencyConflicts() {
        BuildFailureClassifier.Result result = BuildFailureClassifier.classify(
                "embedded_runtime_finished",
                "Execution failed for task ':app:checkDebugDuplicateClasses'. Duplicate class kotlin.text.jdk8.RegexExtensionsJDK8Kt found in modules kotlin-stdlib-1.8.22.jar and kotlin-stdlib-jdk8-1.6.21.jar");

        assertEquals(BuildFailureClassifier.Kind.DEPENDENCY_CONFLICT, result.kind);
        assertFalse(result.repairableByModel);
    }

    @Test
    public void unresolvableCoordinatesAreModelRepairable() {
        BuildFailureClassifier.Result result = BuildFailureClassifier.classify(
                "dependency_resolution_failed",
                "Could not find com.github.PhilJay:MPAndroidChart:v3.0.0.\nSearched in the following locations: ...");

        assertEquals(BuildFailureClassifier.Kind.DEPENDENCY_UNRESOLVABLE, result.kind);
        assertTrue(result.repairableByModel);
    }

    @Test
    public void networkOutweighsUnresolvableWhenBothPresent() {
        // A connection timeout is a network problem, not a "wrong coordinate" problem.
        BuildFailureClassifier.Result result = BuildFailureClassifier.classify(
                "embedded_runtime_finished",
                "Could not find com.squareup.okhttp3:okhttp:3.12.13. Connect timed out");

        assertEquals(BuildFailureClassifier.Kind.DEPENDENCY_NETWORK, result.kind);
        assertFalse(result.repairableByModel);
    }

    @Test
    public void javacSymbolErrorsAreModelRepairable() {
        BuildFailureClassifier.Result result = BuildFailureClassifier.classify(
                "embedded_runtime_finished",
                "Execution failed for task ':app:compileDebugJavaWithJavac'. StatisticsActivity.java:102: error: cannot find symbol");

        assertEquals(BuildFailureClassifier.Kind.JAVA_COMPILE, result.kind);
        assertTrue(result.repairableByModel);
    }

    @Test
    public void jitpackDownloadFailureIsRepairableBySdkSubstitution() {
        // A JitPack app library (no domestic mirror) failing to download is recoverable — remove it and
        // reimplement with the SDK.
        BuildFailureClassifier.Result result = BuildFailureClassifier.classify(
                "embedded_runtime_finished",
                "Could not resolve com.github.PhilJay:MPAndroidChart:v3.1.0. Connect timed out (jitpack.io)");

        assertEquals(BuildFailureClassifier.Kind.DEPENDENCY_NETWORK, result.kind);
        assertTrue(result.repairableByModel);
    }

    @Test
    public void generalNetworkOutageIsNotRepairable() {
        // No-network / toolchain outage: the model can't reimplement Gradle/AGP, so don't burn repair rounds.
        BuildFailureClassifier.Result result = BuildFailureClassifier.classify(
                "embedded_runtime_finished",
                "Could not GET 'https://dl.google.com/...'. UnknownHostException: dl.google.com. network unavailable");

        assertEquals(BuildFailureClassifier.Kind.DEPENDENCY_NETWORK, result.kind);
        assertFalse(result.repairableByModel);
    }
}
