package com.androidbuilder.ui;

import com.androidbuilder.agent.BuildLogContextExtractor;

/**
 * Stops the auto-repair loop early when it stalls. If two consecutive build failures carry the exact
 * same javac diagnostics, the last repair changed nothing that mattered, so continuing would just burn
 * the remaining rounds on the same wall. A fingerprint of the diagnostics — not the whole log, which
 * also contains volatile timestamps/paths — is compared across rounds.
 */
final class RepairLoopStallPolicy {

    private RepairLoopStallPolicy() {
    }

    /** A stable fingerprint of a build log's javac diagnostics; empty when there are none to compare. */
    static String signature(String buildLog) {
        String diagnostics = BuildLogContextExtractor.javaCompileDiagnostics(buildLog, 4000);
        return diagnostics.replaceAll("\\s+", " ").trim();
    }

    /** True when this failure's diagnostics are non-empty and identical to the previous round's. */
    static boolean stalled(String previousSignature, String currentSignature) {
        return currentSignature != null
                && !currentSignature.isEmpty()
                && currentSignature.equals(previousSignature);
    }
}
