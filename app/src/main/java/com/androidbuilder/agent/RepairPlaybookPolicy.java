package com.androidbuilder.agent;

import java.util.Arrays;
import java.util.Collections;

final class RepairPlaybookPolicy {
    private RepairPlaybookPolicy() {
    }

    static RepairPlaybook match(FailureFingerprint fingerprint) {
        if (fingerprint == null || fingerprint.code.isEmpty()) {
            return null;
        }
        if ("MISSING_RESOURCE".equals(fingerprint.code)) {
            String symbol = fallback(fingerprint.symbol, "the missing resource");
            String path = fallback(fingerprint.path, "the values/drawable/layout resource file");
            return new RepairPlaybook(
                    "missing_resource",
                    "Resolve " + symbol + " at " + path + " with the smallest change: add the missing values/drawable/layout resource or change the caller to an existing resource. Keep Java/XML references synchronized.",
                    Arrays.asList(symbol, path));
        }
        if ("MISSING_JAVA_SYMBOL".equals(fingerprint.code)) {
            String symbol = fallback(fingerprint.symbol, "the missing Java symbol");
            return new RepairPlaybook(
                    "missing_java_symbol",
                    "Resolve missing symbol " + symbol + " by adding the method/field/class with the expected signature or updating the caller to an existing API. Keep the patch scoped to the declaring file and direct callers.",
                    Collections.singletonList(symbol));
        }
        if ("API_SIGNATURE_MISMATCH".equals(fingerprint.code)) {
            String symbol = fallback(fingerprint.symbol, "the mismatched API");
            return new RepairPlaybook(
                    "api_signature_mismatch",
                    "Synchronize " + symbol + " signatures and all callers together. Prefer updating DAO/model/helper constructors or methods and the Activity/Adapter callers in one focused patch.",
                    Collections.singletonList(symbol));
        }
        if ("MISSING_DEPENDENCY".equals(fingerprint.code)) {
            String symbol = fallback(fingerprint.symbol, "the missing dependency");
            return new RepairPlaybook(
                    "missing_dependency",
                    "Remove or replace unavailable dependency " + symbol + ". Use the approved dependency catalog or Android SDK/Java/XML APIs instead of inventing a new coordinate.",
                    Collections.singletonList(symbol));
        }
        if ("JAVA_LAMBDA".equals(fingerprint.code)) {
            return new RepairPlaybook(
                    "java_lambda",
                    "Replace Java lambda or arrow syntax with anonymous inner classes. Do not leave arrow examples in comments, strings, or Javadocs.",
                    Collections.singletonList("->"));
        }
        return null;
    }

    static String retryHint(FailureFingerprint fingerprint) {
        RepairPlaybook playbook = match(fingerprint);
        if (playbook == null) {
            return "";
        }
        return "Hermes playbook matched: " + playbook.id + "\n" + playbook.hint;
    }

    private static String fallback(String value, String fallback) {
        String text = value == null ? "" : value.trim();
        return text.isEmpty() ? fallback : text;
    }
}
