package com.androidbuilder.agent;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class RepairPlaybookPolicyTest {
    @Test
    public void missingResourcePlaybookNamesSmallestFix() {
        FailureFingerprint fingerprint = new FailureFingerprint(
                "policy",
                "MISSING_RESOURCE",
                "app/src/main/res/values/styles.xml",
                "@color/primary",
                "resource missing");

        RepairPlaybook match = RepairPlaybookPolicy.match(fingerprint);

        assertEquals("missing_resource", match.id);
        assertTrue(match.hint.contains("@color/primary"));
        assertTrue(match.hint.contains("values"));
        assertTrue(match.focusTerms.contains("@color/primary"));
    }

    @Test
    public void apiSignatureMismatchPlaybookKeepsCallersAndDaoTogether() {
        FailureFingerprint fingerprint = new FailureFingerprint(
                "policy",
                "API_SIGNATURE_MISMATCH",
                "app/src/main/java/com/example/MainActivity.java",
                "RecordDao",
                "constructor mismatch");

        RepairPlaybook match = RepairPlaybookPolicy.match(fingerprint);

        assertEquals("api_signature_mismatch", match.id);
        assertTrue(match.hint.contains("RecordDao"));
        assertTrue(match.hint.contains("callers"));
    }

    @Test
    public void unknownFingerprintHasNoPlaybook() {
        assertNull(RepairPlaybookPolicy.match(new FailureFingerprint(
                "policy",
                "UNKNOWN_BUILD_OR_POLICY_ERROR",
                "",
                "",
                "unknown")));
    }
}
