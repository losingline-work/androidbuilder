package com.androidbuilder.agent;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class PolicyRewriteInstructionTest {
    @Test
    public void lambdaPolicyErrorProducesExplicitRewriteHint() {
        String instruction = PolicyRewriteInstruction.create(
                "Repair build failure",
                "Generated source policy blocked Java lambda syntax in TimelineFragment.java. Use anonymous listener classes instead of ->.",
                2);

        assertTrue(instruction.contains("Generated source policy blocked Java lambda syntax"));
        assertTrue(instruction.contains("Remove every Java lambda"));
        assertTrue(instruction.contains("anonymous inner classes"));
        assertTrue(instruction.contains("Do not use ->"));
        assertTrue(instruction.contains("attempt 2"));
    }

    @Test
    public void missingResourcePolicyErrorProducesResourceHint() {
        String instruction = PolicyRewriteInstruction.create(
                "Repair build failure",
                "Generated source policy blocked missing XML resource reference: @mipmap/ic_launcher in AndroidManifest.xml.",
                1);

        assertTrue(instruction.contains("@mipmap/@style/@drawable/@string/@color/@layout"));
        assertTrue(instruction.contains("matching resource"));
    }
}
