package com.androidbuilder.agent;

import com.androidbuilder.model.HermesTaskContract;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class HermesFileLockPolicy {
    private static final String WILDCARD = "*";
    private static final List<String> GRADLE_LOCKS;
    private static final List<String> MANIFEST_LOCKS;
    private static final List<String> VALUES_LOCKS;

    static {
        List<String> gradle = new ArrayList<>();
        gradle.add("settings.gradle");
        gradle.add("build.gradle");
        gradle.add("app/build.gradle");
        GRADLE_LOCKS = Collections.unmodifiableList(gradle);
        MANIFEST_LOCKS = Collections.singletonList("app/src/main/AndroidManifest.xml");
        VALUES_LOCKS = Collections.singletonList("app/src/main/res/values/*");
    }

    private HermesFileLockPolicy() {
    }

    public static List<String> locksFor(String title, String instruction, HermesTaskContract contract) {
        HermesTaskContract safeContract = contract == null ? HermesTaskContract.empty() : contract;
        List<String> contractLocks = normalizedNonEmpty(safeContract.allowedPaths);
        if (!contractLocks.isEmpty()) {
            return contractLocks;
        }
        contractLocks = normalizedNonEmpty(safeContract.expectedFiles);
        if (!contractLocks.isEmpty()) {
            return contractLocks;
        }
        String text = textFor(title, instruction, safeContract);
        if (mentionsGradle(text)) {
            return GRADLE_LOCKS;
        }
        if (mentionsManifest(text)) {
            return MANIFEST_LOCKS;
        }
        if (mentionsValues(text)) {
            return VALUES_LOCKS;
        }
        return Collections.singletonList(WILDCARD);
    }

    public static boolean conflicts(List<String> left, List<String> right) {
        if (left == null || left.isEmpty() || right == null || right.isEmpty()) {
            return false;
        }
        for (String leftLock : left) {
            String normalizedLeft = normalizePath(leftLock);
            for (String rightLock : right) {
                String normalizedRight = normalizePath(rightLock);
                if (lockConflicts(normalizedLeft, normalizedRight)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean isExclusiveBarrier(String title, String instruction, HermesTaskContract contract) {
        HermesTaskContract safeContract = contract == null ? HermesTaskContract.empty() : contract;
        if (safeContract.buildRequiredAfter) {
            return true;
        }
        String text = textFor(title, instruction, safeContract);
        return mentionsGradle(text) || mentionsManifest(text);
    }

    private static List<String> normalizedNonEmpty(List<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String path : paths) {
            String normalized = normalizePath(path);
            if (!normalized.isEmpty()) {
                unique.add(normalized);
            }
        }
        if (unique.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(unique));
    }

    private static boolean lockConflicts(String left, String right) {
        if (left.isEmpty() || right.isEmpty()) {
            return false;
        }
        if (WILDCARD.equals(left) || WILDCARD.equals(right) || left.equals(right)) {
            return true;
        }
        return wildcardMatches(left, right) || wildcardMatches(right, left);
    }

    private static boolean wildcardMatches(String pattern, String path) {
        if (!pattern.endsWith("/*")) {
            return false;
        }
        String prefix = pattern.substring(0, pattern.length() - 2);
        return path.equals(prefix) || path.startsWith(prefix + "/") || path.startsWith(prefix + ".");
    }

    private static String textFor(String title, String instruction, HermesTaskContract contract) {
        StringBuilder builder = new StringBuilder();
        append(builder, title);
        append(builder, instruction);
        if (contract != null) {
            appendAll(builder, contract.allowedPaths);
            appendAll(builder, contract.expectedFiles);
            appendAll(builder, contract.riskNotes);
            appendAll(builder, contract.acceptanceChecks);
        }
        return builder.toString().toLowerCase(Locale.US);
    }

    private static boolean mentionsGradle(String text) {
        return text.contains("gradle") || text.contains("settings.gradle") || text.contains("build.gradle");
    }

    private static boolean mentionsManifest(String text) {
        return text.contains("manifest") || text.contains("androidmanifest.xml");
    }

    private static boolean mentionsValues(String text) {
        return text.contains("values") || text.contains("strings.xml")
                || text.contains("colors.xml") || text.contains("themes.xml");
    }

    private static void appendAll(StringBuilder builder, List<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            append(builder, value);
        }
    }

    private static void append(StringBuilder builder, String value) {
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        if (builder.length() > 0) {
            builder.append(' ');
        }
        builder.append(value.trim());
    }

    private static String normalizePath(String path) {
        if (path == null) {
            return "";
        }
        String normalized = path.trim().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        while (normalized.contains("//")) {
            normalized = normalized.replace("//", "/");
        }
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }
}
