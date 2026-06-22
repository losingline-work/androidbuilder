package com.androidbuilder.agent;

import com.androidbuilder.util.FileUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Closes a frequent javac-loop sink: callers use a class as a singleton — {@code X.getInstance(...)} — but the
 * generated class {@code X} never declares that static accessor, so the build loops forever on "cannot find
 * symbol: method getInstance" (project-27 burned ~110 rounds on {@code DatabaseHelper.getInstance}).
 *
 * <p>{@link StubReconciler} cannot fix this: it would add a NON-static method returning {@code null}, which
 * neither satisfies the static call nor survives a chained {@code .getWritableDatabase()}. This adds a PROPER
 * static singleton, but ONLY when it can build a compiling one from {@code X}'s existing constructor:
 * {@code getInstance(Context)} when {@code X} has an {@code X(Context)} ctor (the SQLiteOpenHelper shape), or
 * {@code getInstance()} when {@code X} is no-arg constructible. Anything else is left to the repair loop.
 * Additive, self-contained, runs at the generation merge BEFORE the source guard.
 */
final class SingletonGetInstanceReconciler {
    // X.getInstance(  -> group(2) is ")" only when the arg list is empty (robust to nested parens in args).
    private static final Pattern CALL =
            Pattern.compile("\\b([A-Z][A-Za-z0-9_]*)\\.getInstance\\s*\\(\\s*(\\))?");

    private SingletonGetInstanceReconciler() {
    }

    /** Returns one label per class given a singleton accessor (for the task log); empty when none needed. */
    static List<String> reconcile(File sourceDir) {
        List<String> added = new ArrayList<>();
        try {
            List<File> files = javaFiles(new File(sourceDir, "app/src/main/java"));
            Map<String, Integer> neededArity = new LinkedHashMap<>();
            for (File file : files) {
                Matcher matcher = CALL.matcher(read(file));
                while (matcher.find()) {
                    int arity = matcher.group(2) != null ? 0 : 1;
                    neededArity.putIfAbsent(matcher.group(1), arity);
                }
            }
            for (Map.Entry<String, Integer> entry : neededArity.entrySet()) {
                String className = entry.getKey();
                File classFile = findClassFile(files, className);
                if (classFile == null) {
                    continue; // a library/unknown type, never the generated source
                }
                String content = read(classFile);
                if (content == null || declaresGetInstance(content)) {
                    continue;
                }
                String member = singletonMember(className, content, entry.getValue());
                if (member != null && spliceIntoClass(classFile, content, className, member)) {
                    added.add(className + ".getInstance (singleton)");
                }
            }
        } catch (Exception ignored) {
            // Best-effort; never block the merge.
        }
        return added;
    }

    /** A compiling static singleton, or null when X's constructors can't form one we can prove compiles. */
    private static String singletonMember(String className, String content, int arity) {
        String quoted = Pattern.quote(className);
        // An Application/Activity/Fragment/View is created by the framework, never with `new X()`; its
        // getInstance must return the onCreate-set instance, which we cannot synthesize. Leave it to the
        // model (a `new App()` singleton compiles but NPEs at runtime — onCreate never ran on it).
        if (Pattern.compile("\\bclass\\s+" + quoted
                + "\\s+extends\\s+[A-Za-z0-9_.]*(?:Application|Activity|Fragment|View|Service)\\b").matcher(content).find()) {
            return null;
        }
        boolean contextCtor = Pattern.compile(
                "\\b" + quoted + "\\s*\\(\\s*(?:android\\.content\\.)?Context\\s+\\w+\\s*\\)").matcher(content).find();
        boolean noArgCtor = Pattern.compile("\\b" + quoted + "\\s*\\(\\s*\\)").matcher(content).find();
        boolean declaresAnyCtor = Pattern.compile(
                "(?m)^\\s*(?:public|protected|private)\\s+" + quoted + "\\s*\\(").matcher(content).find();
        String field = "\n    private static " + className + " sInstance;\n";
        if (arity == 1 && contextCtor) {
            return field
                    + "    public static synchronized " + className + " getInstance(android.content.Context a0) {\n"
                    + "        if (sInstance == null) { sInstance = new " + className + "(a0.getApplicationContext()); }\n"
                    + "        return sInstance;\n    }\n";
        }
        if (arity == 0 && (noArgCtor || !declaresAnyCtor)) {
            return field
                    + "    public static synchronized " + className + " getInstance() {\n"
                    + "        if (sInstance == null) { sInstance = new " + className + "(); }\n"
                    + "        return sInstance;\n    }\n";
        }
        return null;
    }

    /** True when X already declares getInstance (a declaration is {@code getInstance(} NOT preceded by a dot;
     *  a call is {@code recv.getInstance(}). */
    private static boolean declaresGetInstance(String content) {
        Matcher matcher = Pattern.compile("getInstance\\s*\\(").matcher(content);
        while (matcher.find()) {
            int j = matcher.start() - 1;
            while (j >= 0 && Character.isWhitespace(content.charAt(j))) {
                j--;
            }
            if (j < 0 || content.charAt(j) != '.') {
                return true; // not a `recv.getInstance(` call -> a declaration
            }
        }
        return false;
    }

    private static boolean spliceIntoClass(File classFile, String content, String className, String member) {
        Matcher decl = Pattern.compile("\\bclass\\s+" + Pattern.quote(className) + "\\b").matcher(content);
        if (!decl.find()) {
            return false; // only real classes get a singleton (never an interface/enum)
        }
        int brace = content.indexOf('{', decl.end());
        if (brace < 0) {
            return false;
        }
        try {
            FileUtils.writeText(classFile, content.substring(0, brace + 1) + member + content.substring(brace + 1));
            return true;
        } catch (Exception error) {
            return false;
        }
    }

    private static File findClassFile(List<File> files, String className) {
        Pattern decl = Pattern.compile("\\bclass\\s+" + Pattern.quote(className) + "\\b");
        for (File file : files) {
            String content = read(file);
            if (content != null && decl.matcher(content).find()) {
                return file;
            }
        }
        return null;
    }

    private static List<File> javaFiles(File root) {
        List<File> out = new ArrayList<>();
        collect(root, out, new LinkedHashSet<String>());
        return out;
    }

    private static void collect(File file, List<File> out, Set<String> seen) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    collect(child, out, seen);
                }
            }
        } else if (file.getName().endsWith(".java") && seen.add(file.getAbsolutePath())) {
            out.add(file);
        }
    }

    private static String read(File file) {
        try {
            return file != null && file.isFile() ? FileUtils.readText(file) : "";
        } catch (Exception ignored) {
            return "";
        }
    }
}
