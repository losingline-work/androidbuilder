package com.androidbuilder.backend;

final class AndroidGradleNormalizer {
    static final String ANDROID_GRADLE_PLUGIN_VERSION = "8.7.3";

    private AndroidGradleNormalizer() {
    }

    static String ensureRootAndroidApplicationPlugin(String build) {
        String normalized = build == null ? "" : build;
        if (hasVersionedAndroidApplicationPlugin(normalized)) {
            return normalized;
        }
        if (containsAndroidApplicationPlugin(normalized)) {
            normalized = normalized.replaceAll(
                    "id\\s*(?:\\(\\s*)?[\"']com\\.android\\.application[\"']\\s*(?:\\))?",
                    "id 'com.android.application' version '" + ANDROID_GRADLE_PLUGIN_VERSION + "' apply false");
            return normalized.replace("apply false apply false", "apply false");
        }
        String pluginLine = "    id 'com.android.application' version '" + ANDROID_GRADLE_PLUGIN_VERSION + "' apply false\n";
        int pluginsIndex = normalized.indexOf("plugins");
        if (pluginsIndex >= 0) {
            int openBrace = normalized.indexOf('{', pluginsIndex);
            if (openBrace >= 0) {
                int closeBrace = matchingBrace(normalized, openBrace);
                if (closeBrace >= 0) {
                    return normalized.substring(0, openBrace + 1) +
                            "\n" + pluginLine +
                            normalized.substring(closeBrace);
                }
            }
        }
        return "plugins {\n" + pluginLine + "}\n" + normalized;
    }

    static String normalizeGradleProperties(String existing, boolean enableAndroidX, String aapt2Path) {
        String input = existing == null ? "" : existing;
        StringBuilder next = new StringBuilder();
        for (String line : input.split("\\R", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("org.gradle.jvmargs=") ||
                    trimmed.startsWith("org.gradle.daemon=") ||
                    trimmed.startsWith("org.gradle.workers.max=") ||
                    trimmed.startsWith("kotlin.compiler.execution.strategy=") ||
                    trimmed.startsWith("android.aapt2FromMavenOverride=") ||
                    (enableAndroidX && trimmed.startsWith("android.useAndroidX="))) {
                continue;
            }
            if (line.isEmpty() && next.length() == 0) {
                continue;
            }
            next.append(line).append('\n');
        }
        if (enableAndroidX) {
            next.append("android.useAndroidX=true\n");
        }
        next.append("org.gradle.daemon=false\n");
        next.append("org.gradle.workers.max=1\n");
        next.append("kotlin.compiler.execution.strategy=in-process\n");
        if (aapt2Path != null && !aapt2Path.isEmpty()) {
            next.append("android.aapt2FromMavenOverride=")
                    .append(aapt2Path)
                    .append('\n');
        }
        return next.toString();
    }

    static String ensureSettingsPluginManagement(String settings) {
        String normalized = settings == null ? "" : settings;
        if (normalized.contains("pluginManagement")) {
            return normalized;
        }
        return "pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }\n" + normalized;
    }

    static String normalizeJvmTargets(String build, boolean kotlinDsl) {
        if (build == null || !build.contains("android {")) {
            return build;
        }
        String normalized = removeGradleBlock(build, "kotlinOptions")
                .replaceAll("(?m)^\\s*jvmTarget\\s*=\\s*[\"'][^\"']+[\"']\\s*$\\n?", "")
                .replaceAll("sourceCompatibility\\s*=\\s*JavaVersion\\.VERSION_[0-9_]+", "sourceCompatibility = JavaVersion.VERSION_1_8")
                .replaceAll("targetCompatibility\\s*=\\s*JavaVersion\\.VERSION_[0-9_]+", "targetCompatibility = JavaVersion.VERSION_1_8")
                .replaceAll("sourceCompatibility\\s+JavaVersion\\.VERSION_[0-9_]+", "sourceCompatibility JavaVersion.VERSION_1_8")
                .replaceAll("targetCompatibility\\s+JavaVersion\\.VERSION_[0-9_]+", "targetCompatibility JavaVersion.VERSION_1_8");

        if (normalized.contains("compileOptions")) {
            return normalized;
        }
        String snippet = kotlinDsl
                ? "\n    compileOptions {\n        sourceCompatibility = JavaVersion.VERSION_1_8\n        targetCompatibility = JavaVersion.VERSION_1_8\n    }\n"
                : "\n    compileOptions { sourceCompatibility JavaVersion.VERSION_1_8; targetCompatibility JavaVersion.VERSION_1_8 }\n";
        return insertBeforeAndroidBlockEnd(normalized, snippet);
    }

    private static boolean containsAndroidApplicationPlugin(String build) {
        return build != null && build.matches("(?s).*id\\s*(?:\\(\\s*)?[\"']com\\.android\\.application[\"']\\s*(?:\\))?.*");
    }

    private static boolean hasVersionedAndroidApplicationPlugin(String build) {
        return build != null && build.matches("(?s).*id\\s*(?:\\(\\s*)?[\"']com\\.android\\.application[\"']\\s*(?:\\))?\\s+version\\s+[\"'][^\"']+[\"'].*");
    }

    private static String removeGradleBlock(String build, String blockName) {
        StringBuilder output = new StringBuilder(build);
        int searchFrom = 0;
        while (searchFrom < output.length()) {
            int nameIndex = indexOfBlockName(output, blockName, searchFrom);
            if (nameIndex < 0) {
                break;
            }
            int braceIndex = nextNonWhitespace(output, nameIndex + blockName.length());
            if (braceIndex < 0 || output.charAt(braceIndex) != '{') {
                searchFrom = nameIndex + blockName.length();
                continue;
            }
            int endIndex = matchingBrace(output, braceIndex);
            if (endIndex < 0) {
                searchFrom = nameIndex + blockName.length();
                continue;
            }
            int deleteStart = lineStart(output, nameIndex);
            int deleteEnd = includeTrailingLineBreak(output, endIndex + 1);
            output.delete(deleteStart, deleteEnd);
            searchFrom = deleteStart;
        }
        return output.toString();
    }

    private static int indexOfBlockName(CharSequence text, String blockName, int from) {
        int index = text.toString().indexOf(blockName, from);
        while (index >= 0) {
            boolean leftOk = index == 0 || !isIdentifier(text.charAt(index - 1));
            int rightIndex = index + blockName.length();
            boolean rightOk = rightIndex >= text.length() || !isIdentifier(text.charAt(rightIndex));
            if (leftOk && rightOk) {
                return index;
            }
            index = text.toString().indexOf(blockName, index + blockName.length());
        }
        return -1;
    }

    private static boolean isIdentifier(char value) {
        return Character.isLetterOrDigit(value) || value == '_' || value == '-';
    }

    private static int nextNonWhitespace(CharSequence text, int from) {
        for (int index = from; index < text.length(); index++) {
            if (!Character.isWhitespace(text.charAt(index))) {
                return index;
            }
        }
        return -1;
    }

    private static int matchingBrace(CharSequence text, int openBrace) {
        int depth = 0;
        for (int index = openBrace; index < text.length(); index++) {
            char value = text.charAt(index);
            if (value == '{') {
                depth++;
            } else if (value == '}') {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
        }
        return -1;
    }

    private static int lineStart(CharSequence text, int index) {
        int start = index;
        while (start > 0 && text.charAt(start - 1) != '\n' && text.charAt(start - 1) != '\r') {
            start--;
        }
        return start;
    }

    private static int includeTrailingLineBreak(CharSequence text, int index) {
        int end = index;
        if (end < text.length() && text.charAt(end) == '\r') {
            end++;
        }
        if (end < text.length() && text.charAt(end) == '\n') {
            end++;
        }
        return end;
    }

    private static String insertBeforeAndroidBlockEnd(String build, String snippet) {
        int androidIndex = build.indexOf("android {");
        if (androidIndex < 0) {
            return build;
        }
        int openBrace = build.indexOf('{', androidIndex);
        if (openBrace < 0) {
            return build;
        }
        int depth = 0;
        for (int index = openBrace; index < build.length(); index++) {
            char value = build.charAt(index);
            if (value == '{') {
                depth++;
            } else if (value == '}') {
                depth--;
                if (depth == 0) {
                    return build.substring(0, index) + snippet + build.substring(index);
                }
            }
        }
        return build;
    }
}
