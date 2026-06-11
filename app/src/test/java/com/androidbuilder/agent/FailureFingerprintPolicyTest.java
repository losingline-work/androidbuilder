package com.androidbuilder.agent;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FailureFingerprintPolicyTest {
    @Test
    public void normalizesMissingResourceErrorsToSameFingerprint() {
        FailureFingerprint left = FailureFingerprintPolicy.fromPolicyError(
                "Generated source policy blocked missing XML resource reference: @color/primary in app/src/main/res/values/styles.xml.");
        FailureFingerprint right = FailureFingerprintPolicy.fromPolicyError(
                "AAPT failed: resource color/primary not found in app/src/main/res/values/styles.xml.");

        assertEquals(left.code, right.code);
        assertEquals("@color/primary", left.symbol);
        assertEquals("app/src/main/res/values/styles.xml", left.path);
        assertTrue(FailureFingerprintPolicy.isRepeated(Arrays.asList(left, right), left, 2));
    }

    @Test
    public void detectsApiSignatureMismatch() {
        FailureFingerprint fingerprint = FailureFingerprintPolicy.fromPolicyError(
                "constructor RecordDao in class RecordDao cannot be applied to given types; required Context, found DBHelper in app/src/main/java/com/example/MainActivity.java");

        assertEquals("API_SIGNATURE_MISMATCH", fingerprint.code);
        assertEquals("RecordDao", fingerprint.symbol);
        assertEquals("app/src/main/java/com/example/MainActivity.java", fingerprint.path);
    }

    @Test
    public void repeatedContextMentionsNarrowScopeOnlyAtThreshold() {
        FailureFingerprint fingerprint = FailureFingerprintPolicy.fromPolicyError(
                "cannot find symbol method listAll() in app/src/main/java/com/example/RecordDao.java");

        assertFalse(FailureFingerprintPolicy.repeatedRetryContext(Collections.singletonList(fingerprint), fingerprint, 2).contains("Repeated failure"));
        assertTrue(FailureFingerprintPolicy.repeatedRetryContext(Arrays.asList(fingerprint, fingerprint), fingerprint, 2).contains("narrowest file set"));
    }
}
