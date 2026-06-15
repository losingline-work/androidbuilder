package com.androidbuilder.agent;

import com.androidbuilder.util.FileUtils;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates the AGP-produced symbols (R and BuildConfig) that a direct-javac type-check needs but that
 * a plain {@code abResolveDebugDeps} resolve does NOT produce — Gradle generates them in separate
 * resource/processing tasks the tier check deliberately skips for speed. Scanning the project's own
 * {@code res/} for the resource namespace lets a tier compile resolve {@code R.*} and {@code BuildConfig.*}
 * references the same way a real build eventually would, so the only errors it surfaces are genuine
 * cross-file Java type errors — not phantom "cannot find symbol R" noise.
 *
 * <p>Resource-type correctness is still left to aapt at real build time; this only makes the symbols
 * <em>exist</em> for the type check (every field is an arbitrary int constant).
 */
public final class SyntheticAndroidSymbols {
    private static final Pattern XML_ID =
            Pattern.compile("android:id\\s*=\\s*[\"']@\\+?id/([A-Za-z_][A-Za-z0-9_]*)[\"']");
    private static final Pattern NAMED_VALUE_RESOURCE =
            Pattern.compile("<\\s*(string|color|dimen|style|integer|bool|fraction)\\b[^>]*\\bname\\s*=\\s*[\"']([A-Za-z_][A-Za-z0-9_.]*)[\"']");
    private static final Pattern ARRAY_RESOURCE =
            Pattern.compile("<\\s*(?:string-array|integer-array|array)\\b[^>]*\\bname\\s*=\\s*[\"']([A-Za-z_][A-Za-z0-9_.]*)[\"']");
    private static final Pattern PLURALS_RESOURCE =
            Pattern.compile("<\\s*plurals\\b[^>]*\\bname\\s*=\\s*[\"']([A-Za-z_][A-Za-z0-9_.]*)[\"']");
    private static final Pattern NAMESPACE =
            Pattern.compile("\\bnamespace\\s*(?:=\\s*)?[\"']([A-Za-z_][A-Za-z0-9_.]*)[\"']");
    private static final Pattern APPLICATION_ID =
            Pattern.compile("\\bapplicationId\\s*(?:=\\s*)?[\"']([A-Za-z_][A-Za-z0-9_.]*)[\"']");

    private static final String[] FILE_RES_DIRS = {
            "layout", "menu", "drawable", "mipmap", "anim", "animator", "raw", "xml", "font", "transition"
    };

    public final String packageName;
    public final String rJavaSource;
    public final String buildConfigSource;

    private SyntheticAndroidSymbols(String packageName, String rJavaSource, String buildConfigSource) {
        this.packageName = packageName;
        this.rJavaSource = rJavaSource;
        this.buildConfigSource = buildConfigSource;
    }

    /** Builds the synthetic symbols for the project rooted at {@code sourceDir}; package empty if unknown. */
    public static SyntheticAndroidSymbols from(File sourceDir) throws Exception {
        String pkg = readPackageName(sourceDir);
        Map<String, TreeSet<String>> byType = new LinkedHashMap<>();
        scan(resDir(sourceDir), byType);
        return new SyntheticAndroidSymbols(pkg, renderR(pkg, byType), renderBuildConfig(pkg));
    }

    private static File resDir(File sourceDir) {
        File appRes = new File(sourceDir, "app/src/main/res");
        return appRes.exists() ? appRes : new File(sourceDir, "res");
    }

    private static void scan(File file, Map<String, TreeSet<String>> byType) throws Exception {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) {
                return;
            }
            for (File child : children) {
                scan(child, byType);
            }
            return;
        }
        String parent = file.getParentFile() == null ? "" : file.getParentFile().getName();
        String fileName = file.getName();
        int dot = fileName.lastIndexOf('.');
        String resourceName = dot > 0 ? fileName.substring(0, dot) : fileName;
        if (fileName.endsWith(".xml")) {
            String text = FileUtils.readText(file);
            addAll(byType, "id", XML_ID, text, 1);
            if (parent.startsWith("values")) {
                addNamedValues(byType, text);
                addAll(byType, "array", ARRAY_RESOURCE, text, 1);
                addAll(byType, "plurals", PLURALS_RESOURCE, text, 1);
            }
        }
        for (String resType : FILE_RES_DIRS) {
            if (parent.startsWith(resType)) {
                add(byType, javaType(resType), resourceName);
                break;
            }
        }
    }

    private static void addNamedValues(Map<String, TreeSet<String>> byType, String text) {
        Matcher matcher = NAMED_VALUE_RESOURCE.matcher(text);
        while (matcher.find()) {
            add(byType, matcher.group(1), javaName(matcher.group(2)));
        }
    }

    private static void addAll(Map<String, TreeSet<String>> byType, String type, Pattern pattern, String text, int group) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            add(byType, type, javaName(matcher.group(group)));
        }
    }

    private static String javaType(String resDir) {
        // animator -> animator, transition -> transition; the R inner-class name is the dir family name.
        return resDir;
    }

    private static void add(Map<String, TreeSet<String>> byType, String type, String name) {
        if (name == null || name.isEmpty()) {
            return;
        }
        TreeSet<String> names = byType.get(type);
        if (names == null) {
            names = new TreeSet<>();
            byType.put(type, names);
        }
        names.add(name);
    }

    private static String javaName(String name) {
        return name == null ? "" : name.replace('.', '_');
    }

    private static String renderR(String pkg, Map<String, TreeSet<String>> byType) {
        StringBuilder out = new StringBuilder();
        if (!pkg.isEmpty()) {
            out.append("package ").append(pkg).append(";\n");
        }
        out.append("public final class R {\n");
        int value = 0x7f010000;
        for (Map.Entry<String, TreeSet<String>> entry : byType.entrySet()) {
            out.append("    public static final class ").append(entry.getKey()).append(" {\n");
            for (String name : entry.getValue()) {
                out.append("        public static final int ").append(name).append(" = ").append(value++).append(";\n");
            }
            out.append("    }\n");
        }
        out.append("}\n");
        return out.toString();
    }

    private static String renderBuildConfig(String pkg) {
        StringBuilder out = new StringBuilder();
        if (!pkg.isEmpty()) {
            out.append("package ").append(pkg).append(";\n");
        }
        out.append("public final class BuildConfig {\n");
        out.append("    public static final boolean DEBUG = true;\n");
        out.append("    public static final String APPLICATION_ID = \"").append(pkg).append("\";\n");
        out.append("    public static final String BUILD_TYPE = \"debug\";\n");
        out.append("    public static final int VERSION_CODE = 1;\n");
        out.append("    public static final String VERSION_NAME = \"1.0\";\n");
        out.append("}\n");
        return out.toString();
    }

    private static String readPackageName(File sourceDir) throws Exception {
        File appBuild = new File(sourceDir, "app/build.gradle");
        File rootBuild = new File(sourceDir, "build.gradle");
        for (File candidate : new File[]{appBuild, rootBuild}) {
            if (candidate.isFile()) {
                String text = FileUtils.readText(candidate);
                Matcher namespace = NAMESPACE.matcher(text);
                if (namespace.find()) {
                    return namespace.group(1);
                }
                Matcher applicationId = APPLICATION_ID.matcher(text);
                if (applicationId.find()) {
                    return applicationId.group(1);
                }
            }
        }
        return "";
    }
}
