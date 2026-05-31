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
}
