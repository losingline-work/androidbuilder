package com.androidbuilder.ui;

import com.androidbuilder.agent.BuildLogContextExtractor;

import java.util.HashSet;
import java.util.Set;

/**
 * Detects when the repair loop stalls. The fingerprint spans BOTH phases — javac diagnostics AND aapt
 * missing-resource tokens — because a build that oscillates javac-fail → aapt-fail → javac-fail would
 * defeat a javac-only fingerprint (no two consecutive rounds share a non-empty signature), which is
 * exactly what let project-134 burn ~80 rounds. A fingerprint of the diagnostics — not the whole log,
 * which also carries volatile timestamps/paths — is compared across rounds.
 */
final class RepairLoopStallPolicy {

    private RepairLoopStallPolicy() {
    }

    /**
     * A stable, multi-phase fingerprint of a build log's javac + aapt diagnostics; empty when there are
     * none to compare (a pure Gradle/env failure or a success), which keeps {@link #stalled}'s
     * empty-signature short-circuit meaningful and avoids stalling two unclassifiable failures.
     */
    static String signature(String buildLog) {
        String javac = normalize(BuildLogContextExtractor.javaCompileDiagnostics(buildLog, 4000));
        String resources = normalize(BuildLogContextExtractor.resourceDiagnostics(buildLog, 4000));
        if (javac.isEmpty() && resources.isEmpty()) {
            return "";
        }
        return "J:" + javac + "|R:" + resources;
    }

    /** True when this failure's diagnostics are non-empty and identical to the previous round's. */
    static boolean stalled(String previousSignature, String currentSignature) {
        return currentSignature != null
                && !currentSignature.isEmpty()
                && currentSignature.equals(previousSignature);
    }

    /**
     * True when the current diagnostics represent real progress over the previous round — nothing left
     * to fix, or strictly fewer distinct error tokens. Used to reset the stalled-round counter so a loop
     * that IS converging is never cut short, while a loop that does not shrink accrues stalled rounds.
     */
    static boolean shrank(String previousSignature, String currentSignature) {
        Set<String> current = tokens(currentSignature);
        if (current.isEmpty()) {
            return true;
        }
        return current.size() < tokens(previousSignature).size();
    }

    private static String normalize(String diagnostics) {
        return diagnostics == null ? "" : diagnostics.replaceAll("\\s+", " ").trim();
    }

    private static Set<String> tokens(String signature) {
        Set<String> set = new HashSet<>();
        if (signature == null) {
            return set;
        }
        for (String token : signature.split("\\s+")) {
            if (!token.isEmpty()) {
                set.add(token);
            }
        }
        return set;
    }
}
