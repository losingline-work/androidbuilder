package com.androidbuilder.agent;

import com.androidbuilder.agent.ThemeCompatibilityPolicy.Requirement;
import com.androidbuilder.util.FileUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Merge-time auto-fix for the #1 launch crash (framework theme under AppCompat/Material code). Runs at the
 * generation merge next to {@link CrossReferenceReconciler}; additive and self-validating.
 *
 * <p>Strictly MODE A: it only rewrites the app theme parent when the AppCompat/Material dependency is
 * ALREADY on the classpath. If the code uses {@code AppCompatActivity}/Material widgets without that
 * dependency, the project would fail to COMPILE (javac: cannot find symbol) — a build error the repair loop
 * already handles — never a launch crash, so this guard correctly stays out of it. It also never touches a
 * theme that already resolves (through its parent chain) to an AppCompat/Material theme.
 */
final class ThemeCompatibilityReconciler {
    private static final Pattern MANIFEST_APP_THEME =
            Pattern.compile("<application\\b[^>]*android:theme\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern ACTIVITY_SUPERCLASS =
            Pattern.compile("\\bclass\\s+[A-Za-z_][A-Za-z0-9_]*\\s+extends\\s+([A-Za-z_][A-Za-z0-9_.]*)");
    private static final Pattern STYLE_DECL =
            Pattern.compile("<style\\s+name\\s*=\\s*[\"']([^\"']+)[\"']([^>]*)>");
    private static final Pattern PARENT_ATTR = Pattern.compile("\\bparent\\s*=\\s*[\"']([^\"']*)[\"']");
    private static final Pattern APPCOMPAT_DEP = Pattern.compile("androidx\\.appcompat:appcompat:");
    private static final Pattern MATERIAL_DEP = Pattern.compile("com\\.google\\.android\\.material:material:");
    private static final String MATERIAL_PARENT = "Theme.Material3.DayNight.NoActionBar";
    private static final String APPCOMPAT_PARENT = "Theme.AppCompat.DayNight.NoActionBar";

    private ThemeCompatibilityReconciler() {
    }

    /** Returns greppable labels for each theme rewrite (empty when nothing needed fixing). */
    static List<String> reconcile(File sourceDir) {
        List<String> fixed = new ArrayList<>();
        try {
            File javaRoot = new File(sourceDir, "app/src/main/java");
            File resDir = new File(sourceDir, "app/src/main/res");
            Requirement required = ThemeCompatibilityPolicy.requirementOf(
                    collectActivitySuperclasses(javaRoot), layoutsUseMaterial(resDir));
            if (required == Requirement.NONE) {
                return fixed;
            }
            // MODE A gate: only act when the needed library is on the classpath. Otherwise it is a compile
            // error (handled by the build repair loop), not a launch crash.
            String gradle = readAllBuildGradle(sourceDir);
            boolean material = MATERIAL_DEP.matcher(gradle).find();
            boolean appcompat = material || APPCOMPAT_DEP.matcher(gradle).find(); // material depends on appcompat
            if (!material && !appcompat) {
                return fixed;
            }
            String appliedTheme = appThemeFromManifest(new File(sourceDir, "app/src/main/AndroidManifest.xml"));
            if (appliedTheme.isEmpty()) {
                return fixed;
            }
            Map<String, String> styleParents = collectStyleParents(resDir);
            if (ThemeCompatibilityPolicy.satisfies(
                    ThemeCompatibilityPolicy.resolveApplied(appliedTheme, styleParents), required)) {
                return fixed; // already compatible through its parent chain — never rewrite (false-positive guard)
            }
            String styleName = appliedTheme.replace("@style/", "").trim();
            String targetParent = material ? MATERIAL_PARENT : APPCOMPAT_PARENT;
            rewriteStyleParent(resDir, styleName, targetParent, required, fixed);
        } catch (Exception ignored) {
            // best-effort; the real aapt/runtime still gate the result
        }
        return fixed;
    }

    private static void rewriteStyleParent(File resDir, String styleName, String targetParent,
                                           Requirement required, List<String> fixed) {
        for (File xml : valuesXmlFiles(resDir)) {
            String content = readText(xml);
            String updated = rewriteParentInContent(content, styleName, targetParent, required);
            if (updated == null || updated.equals(content)) {
                continue;
            }
            if (TaskOperationsPreflight.xmlError(updated) != null) {
                continue; // self-validate: never write malformed XML
            }
            try {
                FileUtils.writeText(xml, updated);
                fixed.add("theme @style/" + styleName + " -> parent " + targetParent + " (" + xml.getParentFile().getName() + ")");
            } catch (Exception ignored) {
            }
        }
    }

    /** Rewrites the {@code parent} of the named style to {@code targetParent} when its current parent is incompatible. */
    static String rewriteParentInContent(String content, String styleName, String targetParent, Requirement required) {
        Matcher matcher = STYLE_DECL.matcher(content);
        while (matcher.find()) {
            if (!styleName.equals(matcher.group(1))) {
                continue;
            }
            String attrs = matcher.group(2) == null ? "" : matcher.group(2);
            Matcher parent = PARENT_ATTR.matcher(attrs);
            String newAttrs;
            if (parent.find()) {
                if (ThemeCompatibilityPolicy.satisfies(
                        ThemeCompatibilityPolicy.satisfiedBy(parent.group(1)), required)) {
                    return null; // this declaration is already compatible; leave it
                }
                newAttrs = attrs.substring(0, parent.start())
                        + "parent=\"" + targetParent + "\""
                        + attrs.substring(parent.end());
            } else {
                newAttrs = attrs + " parent=\"" + targetParent + "\"";
            }
            return content.substring(0, matcher.start())
                    + "<style name=\"" + styleName + "\"" + newAttrs + ">"
                    + content.substring(matcher.end());
        }
        return null;
    }

    private static Set<String> collectActivitySuperclasses(File javaRoot) {
        Set<String> superclasses = new HashSet<>();
        for (File java : filesWithExtension(javaRoot, ".java")) {
            String scannable = JavaApiDigest.stripJavaCommentsAndStrings(readText(java));
            Matcher matcher = ACTIVITY_SUPERCLASS.matcher(scannable);
            while (matcher.find()) {
                superclasses.add(matcher.group(1));
            }
        }
        return superclasses;
    }

    private static boolean layoutsUseMaterial(File resDir) {
        for (File xml : valuesOrLayoutXmlFiles(resDir, "layout")) {
            if (readText(xml).contains("com.google.android.material.")) {
                return true;
            }
        }
        return false;
    }

    private static String appThemeFromManifest(File manifest) {
        Matcher matcher = MANIFEST_APP_THEME.matcher(readText(manifest));
        return matcher.find() ? matcher.group(1) : "";
    }

    private static Map<String, String> collectStyleParents(File resDir) {
        Map<String, String> parents = new HashMap<>();
        for (File xml : valuesXmlFiles(resDir)) {
            Matcher matcher = STYLE_DECL.matcher(readText(xml));
            while (matcher.find()) {
                String name = matcher.group(1);
                Matcher parent = PARENT_ATTR.matcher(matcher.group(2) == null ? "" : matcher.group(2));
                String parentValue = parent.find() ? parent.group(1) : "";
                if (!parents.containsKey(name) || parents.get(name).isEmpty()) {
                    parents.put(name, parentValue);
                }
            }
        }
        return parents;
    }

    private static String readAllBuildGradle(File sourceDir) {
        StringBuilder gradle = new StringBuilder();
        for (File file : filesWithExtension(sourceDir, ".gradle")) {
            gradle.append(readText(file)).append('\n');
        }
        return gradle.toString();
    }

    private static List<File> valuesXmlFiles(File resDir) {
        return valuesOrLayoutXmlFiles(resDir, "values");
    }

    private static List<File> valuesOrLayoutXmlFiles(File resDir, String dirPrefix) {
        List<File> out = new ArrayList<>();
        File[] dirs = resDir == null ? null : resDir.listFiles();
        if (dirs == null) {
            return out;
        }
        for (File dir : dirs) {
            if (dir.isDirectory() && dir.getName().startsWith(dirPrefix)) {
                for (File xml : filesWithExtension(dir, ".xml")) {
                    out.add(xml);
                }
            }
        }
        return out;
    }

    private static List<File> filesWithExtension(File root, String extension) {
        List<File> out = new ArrayList<>();
        collectFiles(root, extension, out);
        return out;
    }

    private static void collectFiles(File file, String extension, List<File> out) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isFile()) {
            if (file.getName().endsWith(extension)) {
                out.add(file);
            }
            return;
        }
        File[] children = file.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            collectFiles(child, extension, out);
        }
    }

    private static String readText(File file) {
        try {
            return file != null && file.isFile() ? FileUtils.readText(file) : "";
        } catch (Exception ignored) {
            return "";
        }
    }
}
