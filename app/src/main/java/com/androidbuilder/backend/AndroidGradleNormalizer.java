package com.androidbuilder.backend;

final class AndroidGradleNormalizer {
    static final String ANDROID_GRADLE_PLUGIN_VERSION = "8.7.3";
    private static final String MIRROR_REPOSITORIES =
            "maven { url 'https://maven.aliyun.com/repository/google' }; " +
                    "maven { url 'https://maven.aliyun.com/repository/public' }; " +
                    "maven { url 'https://maven.aliyun.com/repository/gradle-plugin' };";
    private static final String PLUGIN_REPOSITORIES =
            MIRROR_REPOSITORIES + " google(); mavenCentral(); gradlePluginPortal()";
    private static final String KOTLIN_STDLIB_ALIGNMENT =
            "\nallprojects {\n" +
                    "    configurations.configureEach {\n" +
                    "        resolutionStrategy.eachDependency { details ->\n" +
                    "            if (details.requested.group == 'org.jetbrains.kotlin' && details.requested.name.startsWith('kotlin-stdlib')) {\n" +
                    "                details.useVersion '1.8.22'\n" +
                    "            }\n" +
                    "        }\n" +
                    "    }\n" +
                    "}\n";

    private AndroidGradleNormalizer() {
    }

    static String ensureRootAndroidApplicationPlugin(String build) {
        String normalized = build == null ? "" : build;
        if (hasVersionedAndroidApplicationPlugin(normalized)) {
            return ensureKotlinStdlibAlignment(normalized);
        }
        if (containsAndroidApplicationPlugin(normalized)) {
            normalized = normalized.replaceAll(
                    "id\\s*(?:\\(\\s*)?[\"']com\\.android\\.application[\"']\\s*(?:\\))?",
                    "id 'com.android.application' version '" + ANDROID_GRADLE_PLUGIN_VERSION + "' apply false");
            return ensureKotlinStdlibAlignment(normalized.replace("apply false apply false", "apply false"));
        }
        String pluginLine = "    id 'com.android.application' version '" + ANDROID_GRADLE_PLUGIN_VERSION + "' apply false\n";
        int pluginsIndex = normalized.indexOf("plugins");
        if (pluginsIndex >= 0) {
            int openBrace = normalized.indexOf('{', pluginsIndex);
            if (openBrace >= 0) {
                int closeBrace = matchingBrace(normalized, openBrace);
                if (closeBrace >= 0) {
                    return ensureKotlinStdlibAlignment(normalized.substring(0, openBrace + 1) +
                            "\n" + pluginLine +
                            normalized.substring(closeBrace));
                }
            }
        }
        return ensureKotlinStdlibAlignment("plugins {\n" + pluginLine + "}\n" + normalized);
    }

    static String normalizeGradleProperties(String existing, boolean enableAndroidX, String aapt2Path) {
        String input = existing == null ? "" : existing;
        StringBuilder next = new StringBuilder();
        for (String line : input.split("\\R", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("org.gradle.jvmargs=") ||
                    trimmed.startsWith("org.gradle.daemon=") ||
                    trimmed.startsWith("org.gradle.workers.max=") ||
                    trimmed.startsWith("org.gradle.vfs.watch=") ||
                    trimmed.startsWith("kotlin.compiler.execution.strategy=") ||
                    trimmed.startsWith("android.aapt2FromMavenOverride=") ||
                    trimmed.startsWith("android.javaCompile.suppressSourceTargetDeprecationWarning=") ||
                    trimmed.startsWith("systemProp.org.gradle.internal.http.connectionTimeout=") ||
                    trimmed.startsWith("systemProp.org.gradle.internal.http.socketTimeout=") ||
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
        next.append("org.gradle.vfs.watch=false\n");
        next.append("kotlin.compiler.execution.strategy=in-process\n");
        next.append("systemProp.org.gradle.internal.http.connectionTimeout=30000\n");
        next.append("systemProp.org.gradle.internal.http.socketTimeout=30000\n");
        next.append("android.javaCompile.suppressSourceTargetDeprecationWarning=true\n");
        if (aapt2Path != null && !aapt2Path.isEmpty()) {
            next.append("android.aapt2FromMavenOverride=")
                    .append(aapt2Path)
                    .append('\n');
        }
        return next.toString();
    }

    static String ensureSettingsPluginManagement(String settings) {
        String normalized = settings == null ? "" : settings;
        if (!normalized.contains("pluginManagement")) {
            normalized = "pluginManagement { repositories { " + PLUGIN_REPOSITORIES + " } }\n" + normalized;
        } else {
            normalized = ensureBlockHasRepositories(normalized, "pluginManagement", PLUGIN_REPOSITORIES);
        }
        return ensureJitpackRepository(ensureMirrorRepositories(normalized));
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

    private static String ensureKotlinStdlibAlignment(String build) {
        String normalized = build == null ? "" : build;
        if (normalized.contains("details.requested.name.startsWith('kotlin-stdlib')")) {
            return normalized;
        }
        return normalized + KOTLIN_STDLIB_ALIGNMENT;
    }

    private static String ensureBlockHasRepositories(String text, String blockName, String repositories) {
        StringBuilder output = new StringBuilder(text);
        int blockIndex = indexOfBlockName(output, blockName, 0);
        if (blockIndex < 0) {
            return text;
        }
        int braceIndex = nextNonWhitespace(output, blockIndex + blockName.length());
        if (braceIndex < 0 || output.charAt(braceIndex) != '{') {
            return text;
        }
        int endIndex = matchingBrace(output, braceIndex);
        if (endIndex < 0) {
            return text;
        }
        String block = output.substring(braceIndex + 1, endIndex);
        if (block.contains("repositories")) {
            return text;
        }
        output.insert(endIndex, "\n    repositories { " + repositories + " }\n");
        return output.toString();
    }

    private static String ensureMirrorRepositories(String text) {
        StringBuilder output = new StringBuilder(text);
        int searchFrom = 0;
        while (searchFrom < output.length()) {
            int repositoriesIndex = indexOfBlockName(output, "repositories", searchFrom);
            if (repositoriesIndex < 0) {
                break;
            }
            int braceIndex = nextNonWhitespace(output, repositoriesIndex + "repositories".length());
            if (braceIndex < 0 || output.charAt(braceIndex) != '{') {
                searchFrom = repositoriesIndex + "repositories".length();
                continue;
            }
            int endIndex = matchingBrace(output, braceIndex);
            if (endIndex < 0) {
                searchFrom = repositoriesIndex + "repositories".length();
                continue;
            }
            String block = output.substring(braceIndex + 1, endIndex);
            if (!block.contains("maven.aliyun.com")) {
                String insertion = " " + MIRROR_REPOSITORIES + " ";
                output.insert(braceIndex + 1, insertion);
                endIndex += insertion.length();
            }
            searchFrom = endIndex + 1;
        }
        return output.toString();
    }

    /**
     * Appends JitPack as the LAST repository in every repositories block that lacks it, so it is
     * only consulted for artifacts the mirrors and official repos cannot serve (e.g. catalog
     * entries under com.github.* such as MPAndroidChart). Last position keeps the common
     * androidx/material resolution path free of extra JitPack round-trips.
     */
    private static String ensureJitpackRepository(String text) {
        StringBuilder output = new StringBuilder(text);
        int searchFrom = 0;
        while (searchFrom < output.length()) {
            int repositoriesIndex = indexOfBlockName(output, "repositories", searchFrom);
            if (repositoriesIndex < 0) {
                break;
            }
            int braceIndex = nextNonWhitespace(output, repositoriesIndex + "repositories".length());
            if (braceIndex < 0 || output.charAt(braceIndex) != '{') {
                searchFrom = repositoriesIndex + "repositories".length();
                continue;
            }
            int endIndex = matchingBrace(output, braceIndex);
            if (endIndex < 0) {
                searchFrom = repositoriesIndex + "repositories".length();
                continue;
            }
            String block = output.substring(braceIndex + 1, endIndex);
            if (!block.contains("jitpack.io")) {
                // Insert on its OWN line. Appending right before the closing brace after an expression that
                // lacks a trailing ';' (e.g. "gradlePluginPortal()") would otherwise produce
                // "gradlePluginPortal() maven { ... }", which Groovy parses as a method call on the repository
                // ("Could not find method maven() ... on DefaultMavenArtifactRepository") and breaks the build.
                String insertion = "\n        maven { url 'https://jitpack.io' }\n    ";
                output.insert(endIndex, insertion);
                endIndex += insertion.length();
            }
            searchFrom = endIndex + 1;
        }
        return output.toString();
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
