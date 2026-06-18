package com.androidbuilder.agent;

import com.androidbuilder.util.FileUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Seeds minimal, valid producers for resources and classes that the generated source REFERENCES but never
 * declares, so the build reaches aapt/javac with a far shallower hole instead of forcing the repair loop to
 * dig it out (project-134 referenced 26 absent resources — a full colour palette, both launcher mipmaps, a
 * menu — plus two never-generated Activity classes). Runs at the generation merge on the temp tree, AFTER
 * the model's files are written and BEFORE the source-policy guard.
 *
 * <p>Hard rules:
 * <ul>
 *   <li><b>Additive only</b> — never edits or overwrites a model-produced file or an already-declared
 *       resource/class. Value resources are appended into the existing colors.xml/strings.xml; everything
 *       else is a brand-new file.</li>
 *   <li><b>Self-validating</b> — every XML seed is parsed before it is written, so a malformed seed is
 *       skipped rather than shipped (the merge guard does not check resource existence in compile-driven
 *       mode, so it would not catch a bad seed).</li>
 *   <li><b>Mirrors aapt/javac</b> — the real build-time authorities — not the regex guard.</li>
 *   <li><b>Best-effort</b> — any failure is swallowed; the build's real aapt/javac still reports whatever
 *       is left and the repair loop handles it. Reconciliation must never block a merge.</li>
 * </ul>
 * Each seed is tagged so the unfinished-design debt is greppable and surfaced to the user.
 */
final class CrossReferenceReconciler {
    private static final int MAX_ROUNDS = 3;

    // @type/name references in XML. The android: framework namespace is naturally excluded: "@android:color/x"
    // does not start with "@color", so the type alternative never matches at that position.
    private static final Pattern XML_RESOURCE_REFERENCE =
            Pattern.compile("@(color|string|drawable|mipmap|menu)/([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern R_MENU = Pattern.compile("(?<![A-Za-z0-9_.])R\\.menu\\.([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern NAMESPACE =
            Pattern.compile("namespace\\s*(?:=\\s*)?[\"']([A-Za-z_][A-Za-z0-9_.]*)[\"']");
    // Names that look like an app class but are framework-generated (no .java) and must never be stubbed.
    private static final Set<String> NON_STUBBABLE_CLASS_NAMES = new HashSet<>();

    static {
        NON_STUBBABLE_CLASS_NAMES.add("R");
        NON_STUBBABLE_CLASS_NAMES.add("BuildConfig");
        NON_STUBBABLE_CLASS_NAMES.add("Manifest");
    }

    private CrossReferenceReconciler() {
    }

    /** Returns greppable debt labels for each seeded resource/class (empty when the tree is already whole). */
    static List<String> reconcile(File sourceDir) {
        List<String> seeded = new ArrayList<>();
        try {
            for (int round = 0; round < MAX_ROUNDS; round++) {
                int before = seeded.size();
                seedMissingResources(sourceDir, seeded);
                seedMissingClasses(sourceDir, seeded);
                if (seeded.size() == before) {
                    break; // no-progress guard
                }
            }
        } catch (Exception ignored) {
            // Best-effort; never block the merge on reconciliation.
        }
        return seeded;
    }

    // ---- resources -------------------------------------------------------------------------------------

    private static void seedMissingResources(File sourceDir, List<String> seeded) {
        File resDir = new File(sourceDir, "app/src/main/res");
        Set<String> declaredColors = new HashSet<>();
        Set<String> declaredStrings = new HashSet<>();
        Set<String> declaredDrawables = new HashSet<>();
        Set<String> declaredMipmaps = new HashSet<>();
        Set<String> declaredMenus = new HashSet<>();
        collectFileResourceNames(resDir, "drawable", declaredDrawables);
        collectFileResourceNames(resDir, "color", declaredColors); // file-based ColorStateList selectors
        collectFileResourceNames(resDir, "mipmap", declaredMipmaps);
        collectFileResourceNames(resDir, "menu", declaredMenus);
        collectValueResourceNames(resDir, "color", declaredColors);
        collectValueResourceNames(resDir, "string", declaredStrings);

        Set<String> missingColors = new TreeSet<>();
        Set<String> missingStrings = new TreeSet<>();
        Set<String> missingDrawables = new TreeSet<>();
        Set<String> missingMipmaps = new TreeSet<>();
        Set<String> missingMenus = new TreeSet<>();
        for (String reference : collectResourceReferences(sourceDir)) {
            int slash = reference.indexOf('/');
            String type = reference.substring(0, slash);
            String name = reference.substring(slash + 1);
            if ("color".equals(type) && !declaredColors.contains(name)) {
                missingColors.add(name);
            } else if ("string".equals(type) && !declaredStrings.contains(name)) {
                missingStrings.add(name);
            } else if ("drawable".equals(type) && !declaredDrawables.contains(name)) {
                missingDrawables.add(name);
            } else if ("mipmap".equals(type) && !declaredMipmaps.contains(name)) {
                missingMipmaps.add(name);
            } else if ("menu".equals(type) && !declaredMenus.contains(name)) {
                missingMenus.add(name);
            }
        }

        appendValueResources(resDir, "colors.xml", "color", missingColors, "#FF000000", seeded);
        appendValueResources(resDir, "strings.xml", "string", missingStrings, null, seeded);
        for (String name : missingDrawables) {
            writeXmlFile(new File(resDir, "drawable/" + name + ".xml"), placeholderShapeXml(), "@drawable/" + name, seeded);
        }
        for (String name : missingMipmaps) {
            writeXmlFile(new File(resDir, "mipmap/" + name + ".xml"), placeholderShapeXml(), "@mipmap/" + name, seeded);
        }
        for (String name : missingMenus) {
            writeXmlFile(new File(resDir, "menu/" + name + ".xml"), emptyMenuXml(), "@menu/" + name, seeded);
        }
    }

    private static Set<String> collectResourceReferences(File sourceDir) {
        Set<String> references = new LinkedHashSet<>();
        for (File xml : filesWithExtension(new File(sourceDir, "app/src/main"), ".xml")) {
            Matcher matcher = XML_RESOURCE_REFERENCE.matcher(readText(xml));
            while (matcher.find()) {
                references.add(matcher.group(1) + "/" + matcher.group(2));
            }
        }
        for (File java : filesWithExtension(new File(sourceDir, "app/src/main/java"), ".java")) {
            String text = readText(java);
            addRReferences(text, AndroidSourceGuard.R_COLOR, "color", references);
            addRReferences(text, AndroidSourceGuard.R_STRING, "string", references);
            addRReferences(text, AndroidSourceGuard.R_DRAWABLE, "drawable", references);
            addRReferences(text, AndroidSourceGuard.R_MIPMAP, "mipmap", references);
            addRReferences(text, R_MENU, "menu", references);
        }
        return references;
    }

    private static void addRReferences(String text, Pattern pattern, String type, Set<String> references) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            references.add(type + "/" + matcher.group(1));
        }
    }

    /** Appends {@code <type name="..">value</type>} for each missing name into a values file, additively. */
    private static void appendValueResources(File resDir, String fileName, String type, Set<String> names,
                                             String fixedValue, List<String> seeded) {
        if (names.isEmpty()) {
            return;
        }
        File file = new File(resDir, "values/" + fileName);
        boolean existed = file.isFile();
        String content = existed ? readText(file) : "<resources>\n</resources>\n";
        int close = content.lastIndexOf("</resources>");
        if (close < 0) {
            // An existing file with no closing tag is malformed; never clobber a model-produced file. (Our
            // synthetic content always closes, so this only triggers for a pre-existing bad file.)
            return;
        }
        StringBuilder elements = new StringBuilder();
        List<String> added = new ArrayList<>();
        for (String name : names) {
            String body = fixedValue != null ? fixedValue : escapeXml(name);
            elements.append("    <").append(type).append(" name=\"").append(name).append("\">")
                    .append(body).append("</").append(type).append(">\n");
            added.add("@" + type + "/" + name);
        }
        String updated = content.substring(0, close) + elements + content.substring(close);
        if (TaskOperationsPreflight.xmlError(updated) != null) {
            return; // self-validate: never ship malformed XML
        }
        try {
            FileUtils.writeText(file, updated);
            seeded.addAll(added);
        } catch (Exception ignored) {
        }
    }

    private static void writeXmlFile(File file, String content, String debtLabel, List<String> seeded) {
        if (file.isFile()) {
            return; // additive: never overwrite an existing resource file
        }
        if (TaskOperationsPreflight.xmlError(content) != null) {
            return;
        }
        try {
            FileUtils.writeText(file, content);
            seeded.add(debtLabel);
        } catch (Exception ignored) {
        }
    }

    private static String placeholderShapeXml() {
        // A self-contained, transparent shape drawable: valid wherever a @drawable/@mipmap is referenced.
        return "<shape xmlns:android=\"http://schemas.android.com/apk/res/android\" android:shape=\"rectangle\">\n"
                + "    <solid android:color=\"#00000000\" />\n"
                + "</shape>\n";
    }

    private static String emptyMenuXml() {
        return "<menu xmlns:android=\"http://schemas.android.com/apk/res/android\">\n</menu>\n";
    }

    // ---- classes ---------------------------------------------------------------------------------------

    private static void seedMissingClasses(File sourceDir, List<String> seeded) {
        String namespace = appNamespace(sourceDir);
        if (namespace == null || namespace.isEmpty()) {
            return; // cannot safely tell an app class from a library class without the app package
        }
        File javaRoot = new File(sourceDir, "app/src/main/java");
        // namespace, then zero+ lower-initial package segments, then exactly ONE upper-initial class
        // segment. Stopping at the class name means a trailing ".class", ".method()", or ".FIELD" is not
        // swallowed into the captured FQN.
        Pattern fqnPattern = Pattern.compile(
                "\\b(" + namespace.replace(".", "\\.") + "(?:\\.[a-z][A-Za-z0-9_]*)*\\.[A-Z][A-Za-z0-9_]*)");
        Set<String> handled = new HashSet<>();
        for (File java : filesWithExtension(javaRoot, ".java")) {
            Matcher matcher = fqnPattern.matcher(readText(java));
            while (matcher.find()) {
                String fqn = matcher.group(1);
                if (!handled.add(fqn) || !isStubbableClassFqn(fqn)) {
                    continue;
                }
                File target = new File(javaRoot, fqn.replace('.', '/') + ".java");
                if (target.isFile()) {
                    continue; // declared at its conventional path
                }
                writeClassStub(target, fqn, seeded);
            }
        }
    }

    /** True when every package segment is lower-initial and the final (class) segment is upper-initial. */
    private static boolean isStubbableClassFqn(String fqn) {
        String[] segments = fqn.split("\\.");
        if (segments.length < 2) {
            return false;
        }
        String simpleName = segments[segments.length - 1];
        if (NON_STUBBABLE_CLASS_NAMES.contains(simpleName) || !Character.isUpperCase(simpleName.charAt(0))) {
            return false;
        }
        for (int i = 0; i < segments.length - 1; i++) {
            if (segments[i].isEmpty() || !Character.isLowerCase(segments[i].charAt(0))) {
                return false; // an upper-initial mid-segment means this is Outer.NESTED, not a package path
            }
        }
        return true;
    }

    private static void writeClassStub(File target, String fqn, List<String> seeded) {
        int lastDot = fqn.lastIndexOf('.');
        String packageName = fqn.substring(0, lastDot);
        String simpleName = fqn.substring(lastDot + 1);
        // A name ending in Activity is almost always an Intent target; a real (empty) framework Activity
        // both resolves the symbol AND remains launchable. Anything else becomes a minimal compiling class.
        String declaration = simpleName.endsWith("Activity")
                ? "public class " + simpleName + " extends android.app.Activity {\n    " + StubReconciler.STUB_TAG + "\n}\n"
                : "public class " + simpleName + " {\n    " + StubReconciler.STUB_TAG + "\n}\n";
        String content = "package " + packageName + ";\n\n" + declaration;
        try {
            FileUtils.writeText(target, content);
            seeded.add(fqn + " (class stub)");
        } catch (Exception ignored) {
        }
    }

    private static String appNamespace(File sourceDir) {
        Matcher matcher = NAMESPACE.matcher(readText(new File(sourceDir, "app/build.gradle")));
        return matcher.find() ? matcher.group(1) : null;
    }

    // ---- shared helpers --------------------------------------------------------------------------------

    private static void collectFileResourceNames(File resDir, String directoryPrefix, Set<String> names) {
        File[] children = resDir == null ? null : resDir.listFiles();
        if (children == null) {
            return;
        }
        for (File dir : children) {
            if (!dir.isDirectory() || !dir.getName().startsWith(directoryPrefix)) {
                continue;
            }
            File[] files = dir.listFiles();
            if (files == null) {
                continue;
            }
            for (File file : files) {
                if (!file.isFile()) {
                    continue;
                }
                String name = file.getName();
                int dot = name.lastIndexOf('.');
                names.add(dot > 0 ? name.substring(0, dot) : name);
            }
        }
    }

    private static void collectValueResourceNames(File resDir, String type, Set<String> names) {
        File[] children = resDir == null ? null : resDir.listFiles();
        if (children == null) {
            return;
        }
        for (File dir : children) {
            if (!dir.isDirectory() || !dir.getName().startsWith("values")) {
                continue;
            }
            for (File xml : filesWithExtension(dir, ".xml")) {
                Matcher matcher = AndroidSourceGuard.NAMED_VALUE_RESOURCE.matcher(readText(xml));
                while (matcher.find()) {
                    if (type.equals(matcher.group(1))) {
                        names.add(matcher.group(2));
                    }
                }
            }
        }
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

    private static String escapeXml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
