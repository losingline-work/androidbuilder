package com.androidbuilder.agent;

import com.androidbuilder.util.FileUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prevents two launch-time manifest crashes the build cannot see:
 * <ul>
 *   <li>navigating to an Activity that is started in code but never declared throws
 *       {@code ActivityNotFoundException} ("Unable to find explicit activity class …");</li>
 *   <li>an app with no {@code MAIN/LAUNCHER} activity is uninstallable-as-launchable / cannot be opened.</li>
 * </ul>
 *
 * <p>Runs at the generation merge after {@link CrossReferenceReconciler} (which has already stubbed any
 * missing Intent-target class as an empty {@code extends android.app.Activity}, so the class exists by the
 * time this declares it). Strictly additive + self-validating: it only splices an {@code <activity>} for a
 * started, app-package class whose declaring {@code .java extends *Activity}, never touches a declaration
 * that already exists, and only adds a launcher when there is exactly ZERO (it never strips a model-chosen
 * launcher). aapt remains the authority — a spliced declaration is never re-rejected by the policy guard,
 * which does not inspect {@code <activity>} elements.
 */
final class ManifestCompletenessPolicy {
    private static final Pattern NAMESPACE =
            Pattern.compile("namespace\\s*(?:=\\s*)?[\"']([A-Za-z_][A-Za-z0-9_.]*)[\"']");
    // A "X.class" / "com.app.X.class" reference (Intent targets, setClass, etc.).
    private static final Pattern CLASS_LITERAL = Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_.]*)\\.class\\b");
    private static final Pattern ANDROID_NAME = Pattern.compile("android:name\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern LAUNCHER_CATEGORY =
            Pattern.compile("category\\s+android:name\\s*=\\s*[\"']android\\.intent\\.category\\.LAUNCHER[\"']");

    private ManifestCompletenessPolicy() {
    }

    /** Returns greppable labels for each declaration/launcher added (empty when the manifest is already whole). */
    static List<String> reconcile(File sourceDir) {
        List<String> changes = new ArrayList<>();
        try {
            File manifestFile = new File(sourceDir, "app/src/main/AndroidManifest.xml");
            String manifest = readText(manifestFile);
            if (manifest.isEmpty() || !manifest.contains("</application>")) {
                return changes; // nothing to do / malformed — never clobber
            }
            String namespace = appNamespace(sourceDir);
            if (namespace == null || namespace.isEmpty()) {
                return changes;
            }
            File javaRoot = new File(sourceDir, "app/src/main/java");

            String updated = manifest;
            // (1) Declare every started, app-package Activity that is not yet declared.
            Set<String> declared = declaredActivityFqns(updated, namespace);
            for (String fqn : startedActivityFqns(javaRoot, namespace)) {
                if (declared.contains(fqn)) {
                    continue;
                }
                String relativeName = fqn.startsWith(namespace + ".") ? fqn.substring(namespace.length()) : fqn;
                String activity = "        <activity android:name=\"" + relativeName + "\" android:exported=\"false\" />\n";
                updated = insertBeforeApplicationClose(updated, activity);
                declared.add(fqn);
                changes.add("manifest <activity " + relativeName + ">");
            }
            // (2) If NOTHING is a launcher, give the main activity a MAIN/LAUNCHER filter so the app opens.
            if (!LAUNCHER_CATEGORY.matcher(updated).find()) {
                String withLauncher = injectLauncher(updated);
                if (withLauncher != null && !withLauncher.equals(updated)) {
                    updated = withLauncher;
                    changes.add("manifest MAIN/LAUNCHER intent-filter");
                }
            }

            if (!updated.equals(manifest) && TaskOperationsPreflight.xmlError(updated) == null) {
                FileUtils.writeText(manifestFile, updated);
            } else if (!updated.equals(manifest)) {
                changes.clear(); // self-validate failed — write nothing
            }
        } catch (Exception ignored) {
            // best-effort; the real aapt/runtime still gate the result
        }
        return changes;
    }

    /** Fully-qualified names of app-package Activity classes that are started somewhere in the .java sources. */
    private static Set<String> startedActivityFqns(File javaRoot, String namespace) {
        Set<String> started = new LinkedHashSet<>();
        List<File> javaFiles = filesWithExtension(javaRoot, ".java");
        for (File java : javaFiles) {
            String scannable = JavaApiDigest.stripJavaCommentsAndStrings(readText(java));
            Matcher matcher = CLASS_LITERAL.matcher(scannable);
            while (matcher.find()) {
                String fqn = resolveActivityFqn(matcher.group(1), namespace, javaRoot, javaFiles);
                if (fqn != null) {
                    started.add(fqn);
                }
            }
        }
        return started;
    }

    /** Resolves a {@code X.class} / {@code com.app.X.class} token to an app-package Activity FQN, or null. */
    private static String resolveActivityFqn(String ref, String namespace, File javaRoot, List<File> javaFiles) {
        String fqn;
        File declaring;
        if (ref.contains(".")) {
            if (!ref.startsWith(namespace + ".")) {
                return null; // not an app-package class (framework/library)
            }
            fqn = ref;
            declaring = new File(javaRoot, ref.replace('.', '/') + ".java");
            if (!declaring.isFile()) {
                declaring = findClassFile(javaFiles, ref.substring(ref.lastIndexOf('.') + 1));
            }
        } else {
            declaring = findClassFile(javaFiles, ref);
            if (declaring == null) {
                return null; // a bare simple name with no app .java -> not an app class
            }
            String pkg = packageOf(declaring);
            fqn = pkg.isEmpty() ? ref : pkg + "." + ref;
            if (!fqn.startsWith(namespace + ".") && !fqn.equals(namespace)) {
                return null;
            }
        }
        return (declaring != null && declaring.isFile() && extendsActivity(readText(declaring), simpleName(fqn)))
                ? fqn : null;
    }

    /** Activity FQNs already declared in the manifest, resolved through relative (".X") / absolute names. */
    private static Set<String> declaredActivityFqns(String manifest, String namespace) {
        Set<String> declared = new LinkedHashSet<>();
        Matcher matcher = ANDROID_NAME.matcher(manifest);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (name.startsWith(".")) {
                declared.add(namespace + name);
            } else if (!name.contains(".")) {
                declared.add(namespace + "." + name);
            } else {
                declared.add(name);
            }
        }
        return declared;
    }

    private static boolean extendsActivity(String javaContent, String simpleName) {
        Matcher matcher = Pattern.compile("\\bclass\\s+" + Pattern.quote(simpleName) + "\\s+extends\\s+([A-Za-z_][A-Za-z0-9_.]*)")
                .matcher(JavaApiDigest.stripJavaCommentsAndStrings(javaContent));
        return matcher.find() && matcher.group(1).endsWith("Activity");
    }

    private static String injectLauncher(String manifest) {
        // Target the conventional MainActivity, else the sole <activity>. Add MAIN/LAUNCHER + exported=true.
        Matcher main = Pattern.compile("<activity\\b([^>]*?android:name\\s*=\\s*[\"'][^\"']*MainActivity[\"'][^>]*?)\\s*(/?)>")
                .matcher(manifest);
        Matcher target = main.find() ? main : null;
        if (target == null) {
            Matcher any = Pattern.compile("<activity\\b([^>]*?)\\s*(/?)>").matcher(manifest);
            if (any.find()) {
                target = any;
            }
        }
        if (target == null) {
            return null;
        }
        String attrs = target.group(1);
        if (!attrs.contains("android:exported")) {
            attrs = attrs + " android:exported=\"true\"";
        } else {
            attrs = attrs.replaceAll("android:exported\\s*=\\s*[\"'][^\"']*[\"']", "android:exported=\"true\"");
        }
        String filter = "\n            <intent-filter>\n"
                + "                <action android:name=\"android.intent.action.MAIN\" />\n"
                + "                <category android:name=\"android.intent.category.LAUNCHER\" />\n"
                + "            </intent-filter>\n        ";
        String replacement = "/".equals(target.group(2))
                ? "<activity" + attrs + ">" + filter + "</activity>"
                : "<activity" + attrs + ">" + filter;
        return manifest.substring(0, target.start()) + replacement + manifest.substring(target.end());
    }

    private static String insertBeforeApplicationClose(String manifest, String snippet) {
        int close = manifest.lastIndexOf("</application>");
        return close < 0 ? manifest : manifest.substring(0, close) + snippet + manifest.substring(close);
    }

    private static String appNamespace(File sourceDir) {
        for (File gradle : filesWithExtension(sourceDir, ".gradle")) {
            Matcher matcher = NAMESPACE.matcher(readText(gradle));
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return null;
    }

    private static File findClassFile(List<File> javaFiles, String simpleName) {
        Pattern decl = Pattern.compile("\\bclass\\s+" + Pattern.quote(simpleName) + "\\b");
        for (File java : javaFiles) {
            if (decl.matcher(JavaApiDigest.stripJavaCommentsAndStrings(readText(java))).find()) {
                return java;
            }
        }
        return null;
    }

    private static String packageOf(File java) {
        Matcher matcher = Pattern.compile("\\bpackage\\s+([A-Za-z_][A-Za-z0-9_.]*)\\s*;").matcher(readText(java));
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String simpleName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
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
