package com.androidbuilder.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class HermesRepairShardingPolicy {
    private static final int EXCERPT_LIMIT = 2000;

    private HermesRepairShardingPolicy() {
    }

    public static List<HermesRepairShard> shards(String buildLog) {
        String log = clean(buildLog);
        if (log.isEmpty()) {
            return exclusive("", "");
        }
        if (requiresExclusiveRepair(log)) {
            return exclusive("", log);
        }

        Map<String, StringBuilder> excerptsByPath = new LinkedHashMap<>();
        boolean sawShardableDiagnostic = false;
        String[] lines = log.split("\\r?\\n");
        for (String line : lines) {
            String cleanedLine = clean(line);
            if (cleanedLine.isEmpty()) {
                continue;
            }
            if (!isShardableDiagnostic(cleanedLine)) {
                continue;
            }
            sawShardableDiagnostic = true;
            String path = extractFocusPath(cleanedLine);
            if (path.isEmpty()) {
                continue;
            }
            appendExcerpt(excerptsByPath, path, cleanedLine);
        }

        if (excerptsByPath.isEmpty()) {
            return exclusive("", log);
        }

        List<HermesRepairShard> shards = new ArrayList<>();
        for (Map.Entry<String, StringBuilder> entry : excerptsByPath.entrySet()) {
            shards.add(new HermesRepairShard(entry.getKey(), entry.getValue().toString(), false));
        }
        return Collections.unmodifiableList(shards);
    }

    private static boolean requiresExclusiveRepair(String log) {
        String lower = log.toLowerCase(Locale.US);
        return isConstructorOrSignatureMismatch(lower)
                || isGradleDependencyFailure(lower)
                || isManifestMergeFailure(lower);
    }

    private static boolean isConstructorOrSignatureMismatch(String lower) {
        return lower.contains("cannot be applied to given types")
                || lower.contains("no suitable constructor")
                || lower.contains("no suitable method")
                || lower.contains("actual and formal argument lists differ")
                || lower.contains("method does not override or implement a method from a supertype");
    }

    private static boolean isGradleDependencyFailure(String lower) {
        return lower.contains("could not find ")
                || lower.contains("could not resolve all files")
                || lower.contains("could not resolve all artifacts")
                || lower.contains("could not resolve ")
                || lower.contains("failed to resolve:");
    }

    private static boolean isManifestMergeFailure(String lower) {
        return lower.contains("manifest merger failed")
                || lower.contains("manifest merger")
                || (lower.contains("androidmanifest.xml") && lower.contains("merge"));
    }

    private static boolean isShardableDiagnostic(String line) {
        String lower = line.toLowerCase(Locale.US);
        return isMissingResource(lower) || lower.contains("cannot find symbol");
    }

    private static boolean isMissingResource(String lower) {
        return lower.contains("error:")
                && lower.contains("resource ")
                && (lower.contains(" not found") || lower.contains("not found."));
    }

    private static String extractFocusPath(String line) {
        String normalized = line.replace('\\', '/');
        int start = normalized.indexOf("app/src/main/");
        if (start < 0) {
            return "";
        }
        int end = start;
        while (end < normalized.length()) {
            char current = normalized.charAt(end);
            if (Character.isWhitespace(current) || current == ':' || current == ')' || current == ',') {
                break;
            }
            end++;
        }
        if (end <= start) {
            return "";
        }
        return normalized.substring(start, end).trim();
    }

    private static void appendExcerpt(Map<String, StringBuilder> excerptsByPath, String path, String line) {
        StringBuilder builder = excerptsByPath.get(path);
        if (builder == null) {
            builder = new StringBuilder();
            excerptsByPath.put(path, builder);
        }
        if (builder.length() > 0) {
            builder.append('\n');
        }
        if (builder.length() + line.length() <= EXCERPT_LIMIT) {
            builder.append(line);
        } else if (builder.length() < EXCERPT_LIMIT) {
            int remaining = EXCERPT_LIMIT - builder.length();
            builder.append(line.substring(0, Math.max(0, remaining)));
        }
    }

    private static List<HermesRepairShard> exclusive(String focusPath, String logExcerpt) {
        return Collections.singletonList(new HermesRepairShard(focusPath, logExcerpt, true));
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
