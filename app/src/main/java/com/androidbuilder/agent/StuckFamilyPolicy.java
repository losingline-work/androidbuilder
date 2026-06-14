package com.androidbuilder.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects a "stuck family" of failures: a single callee class (a DAO) whose callers keep getting
 * flagged for undeclared methods, but with a different method name each round.
 *
 * <p>Each renamed method ("listByMonth" then "listInRange" then "listRecent") looks like a brand-new
 * error to the digit-only {@link DraftCorrectionPolicy#errorSignature} fuse, so the same-error streak
 * never trips and the retry budget drains re-discovering one method at a time. This policy keys on the
 * DAO class alone - ignoring the specific method - so a family that cycles through many member names is
 * recognised as one stuck cluster. When it fires, the orchestrator switches from patching a single
 * method to reconciling the whole DAO + callers cluster in one pass.
 *
 * <p>This only classifies failure history and produces an advisory directive; it never suppresses or
 * weakens a guard verdict. The merge-time AndroidSourceGuard stays the sole authority on the assembled
 * tree.
 */
final class StuckFamilyPolicy {
    private static final Pattern DAO_NOT_DECLARED = Pattern.compile(
            "calls\\s+([A-Za-z_][A-Za-z0-9_]*)\\.([A-Za-z_][A-Za-z0-9_]*)\\(\\)\\s+but that DAO method is not declared",
            Pattern.CASE_INSENSITIVE);

    private StuckFamilyPolicy() {
    }

    /**
     * The first callee class with at least {@code threshold} distinct methods flagged "not declared"
     * across the failure history, or {@code null} if no family is stuck yet.
     */
    static Family detect(List<FailureFingerprint> history, int threshold) {
        if (history == null || threshold <= 0) {
            return null;
        }
        Map<String, LinkedHashSet<String>> membersByClass = new LinkedHashMap<>();
        for (FailureFingerprint fingerprint : history) {
            if (fingerprint == null || fingerprint.normalizedMessage == null) {
                continue;
            }
            Matcher matcher = DAO_NOT_DECLARED.matcher(fingerprint.normalizedMessage);
            while (matcher.find()) {
                membersByClass
                        .computeIfAbsent(matcher.group(1), key -> new LinkedHashSet<>())
                        .add(matcher.group(2));
            }
        }
        for (Map.Entry<String, LinkedHashSet<String>> entry : membersByClass.entrySet()) {
            if (entry.getValue().size() >= threshold) {
                return new Family(entry.getKey(), new ArrayList<>(entry.getValue()));
            }
        }
        return null;
    }

    /**
     * A one-pass reconcile directive that names every method the callers have asked for, so the model
     * declares them all and aligns the call-sites together instead of fixing one method per round.
     */
    static String reconcileDirective(Family family) {
        StringBuilder calls = new StringBuilder();
        for (String member : family.members) {
            if (calls.length() > 0) {
                calls.append(", ");
            }
            calls.append(family.className).append('.').append(member).append("()");
        }
        return "These calls on " + family.className + " were reported undeclared across multiple attempts: "
                + calls + ". Stop fixing one method at a time - that loop never converges. In THIS response, "
                + "resend " + family.className + ".java declaring every one of those methods with the exact "
                + "return type and signature each caller uses, AND resend every file that calls " + family.className
                + " so each call resolves to a declared method. Reconcile the whole " + family.className
                + " + callers cluster in a single pass.";
    }

    static final class Family {
        final String className;
        final List<String> members;

        Family(String className, List<String> members) {
            this.className = className;
            this.members = members;
        }
    }
}
