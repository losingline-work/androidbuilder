package com.androidbuilder.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BuildLogContextExtractor {
    private static final Pattern JAVAC_ERROR = Pattern.compile(".*\\.java:\\d+:\\s+error:.*");
    // The file portion of a javac diagnostic line: "<path>.java:<line>: error:".
    private static final Pattern JAVAC_ERROR_FILE = Pattern.compile("([^\\s:]+\\.java):\\d+:\\s+error:");
    private static final Pattern ERROR_COUNT = Pattern.compile("\\d+\\s+errors?");
    // aapt missing-resource diagnostic, e.g. "error: resource color/colorSurface (aka ...) not found."
    private static final Pattern AAPT_MISSING_RESOURCE = Pattern.compile("error:\\s+resource\\s+([a-z]+)/([A-Za-z0-9_.]+)");
    private static final Pattern REFERENCED_TYPE = Pattern.compile("\\btype\\s+([A-Z][A-Za-z0-9_]*)\\b");
    private static final Pattern SYMBOL_VARIABLE = Pattern.compile("\\bsymbol:\\s+variable\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\b");
    private static final Pattern LOCATION_VARIABLE_TYPE = Pattern.compile("\\blocation:\\s+variable\\s+\\S+\\s+of\\s+type\\s+([A-Z][A-Za-z0-9_]*)\\b");

    private BuildLogContextExtractor() {
    }

    public static String javaCompileDiagnostics(String logs, int maxChars) {
        if (logs == null || logs.trim().isEmpty()) {
            return "";
        }
        StringBuilder diagnostics = new StringBuilder();
        int trailingLines = 0;
        for (String line : logs.split("\\R")) {
            boolean diagnosticStart = isJavacError(line);
            if (diagnosticStart) {
                appendLine(diagnostics, line);
                trailingLines = 6;
                continue;
            }
            if (trailingLines > 0) {
                appendLine(diagnostics, line);
                trailingLines--;
                continue;
            }
            if (ERROR_COUNT.matcher(line.trim()).matches()) {
                appendLine(diagnostics, line);
            }
        }
        return trimMiddle(diagnostics.toString().trim(), maxChars);
    }

    /**
     * A stable fingerprint of the aapt missing-resource diagnostics: one sorted, de-duplicated
     * "missing &lt;type&gt;/&lt;name&gt;" token per absent resource. Sorted so a build that merely re-orders the
     * same missing set between rounds is not mistaken for progress; volatile work-dir paths and line
     * numbers are dropped. Empty when the log has no aapt resource errors (a pure-javac or success log),
     * which keeps the stall policy's empty-signature short-circuit meaningful.
     */
    public static String resourceDiagnostics(String logs, int maxChars) {
        if (logs == null || logs.trim().isEmpty()) {
            return "";
        }
        TreeSet<String> tokens = new TreeSet<>();
        Matcher matcher = AAPT_MISSING_RESOURCE.matcher(logs);
        while (matcher.find()) {
            tokens.add("missing " + matcher.group(1) + "/" + matcher.group(2));
        }
        if (tokens.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String token : tokens) {
            appendLine(out, token);
        }
        return trimMiddle(out.toString(), maxChars);
    }

    /**
     * javac error lines grouped by source file, in first-seen order, so a focused repair round can target the
     * file with the most errors. Keys are normalized to the {@code app/src/...} project-relative path (the
     * build runs in a work dir with an absolute prefix) so the model can match them to the snapshot.
     */
    public static LinkedHashMap<String, List<String>> perFileErrorClusters(String logs) {
        LinkedHashMap<String, List<String>> clusters = new LinkedHashMap<>();
        if (logs == null || logs.trim().isEmpty()) {
            return clusters;
        }
        for (String line : logs.split("\\R")) {
            Matcher matcher = JAVAC_ERROR_FILE.matcher(line);
            if (matcher.find()) {
                String file = normalizeJavaPath(matcher.group(1));
                List<String> lines = clusters.get(file);
                if (lines == null) {
                    lines = new ArrayList<>();
                    clusters.put(file, lines);
                }
                lines.add(line.trim());
            }
        }
        return clusters;
    }

    private static String normalizeJavaPath(String rawPath) {
        int index = rawPath.indexOf("app/src/");
        if (index >= 0) {
            return rawPath.substring(index);
        }
        int slash = rawPath.lastIndexOf('/');
        return slash >= 0 ? rawPath.substring(slash + 1) : rawPath;
    }

    public static Set<String> referencedJavaTypes(String text) {
        Set<String> types = new LinkedHashSet<>();
        if (text == null || text.trim().isEmpty()) {
            return types;
        }
        Matcher matcher = REFERENCED_TYPE.matcher(text);
        while (matcher.find()) {
            types.add(matcher.group(1));
        }
        return types;
    }

    public static String missingFieldHints(String logs) {
        if (logs == null || logs.trim().isEmpty()) {
            return "";
        }
        Set<String> fields = new LinkedHashSet<>();
        String pendingVariable = null;
        for (String line : logs.split("\\R")) {
            Matcher variable = SYMBOL_VARIABLE.matcher(line);
            if (variable.find()) {
                pendingVariable = variable.group(1);
                continue;
            }
            if (pendingVariable == null) {
                continue;
            }
            Matcher location = LOCATION_VARIABLE_TYPE.matcher(line);
            if (location.find()) {
                fields.add(location.group(1) + "." + pendingVariable);
                pendingVariable = null;
            }
        }
        if (fields.isEmpty()) {
            return "";
        }
        StringBuilder hints = new StringBuilder("Missing field references:");
        for (String field : fields) {
            hints.append("\n- ").append(field);
        }
        return hints.toString();
    }

    private static boolean isJavacError(String line) {
        if (line == null) {
            return false;
        }
        String lower = line.toLowerCase(java.util.Locale.ROOT);
        return JAVAC_ERROR.matcher(line).matches() ||
                lower.contains("error: cannot find symbol") ||
                lower.contains("has private access") ||
                lower.contains("cannot be applied to given types") ||
                lower.contains("actual and formal argument lists differ");
    }

    private static void appendLine(StringBuilder out, String line) {
        if (out.length() > 0) {
            out.append('\n');
        }
        out.append(line);
    }

    private static String trimMiddle(String text, int maxChars) {
        if (maxChars <= 0 || text.length() <= maxChars) {
            return text;
        }
        String marker = "\n...[truncated middle]...\n";
        int headLength = Math.max(0, (maxChars - marker.length()) / 2);
        int tailLength = Math.max(0, maxChars - marker.length() - headLength);
        return text.substring(0, headLength).trim() + marker + text.substring(text.length() - tailLength).trim();
    }
}
