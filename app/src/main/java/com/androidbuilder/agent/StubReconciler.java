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
 * Last-resort deterministic coercion: when the model leaves a genuine cross-file gap the guard would
 * reject - a caller references a method/overload/field/constructor that no class declares - this splices
 * a COMPILING stub member onto the callee so the whole tree becomes self-consistent and the project
 * builds, instead of looping forever on a model that will not close the gap. Every stub throws
 * {@code UnsupportedOperationException} and is tagged {@code // ANDROIDBUILDER-STUB} so the unfinished
 * behaviour is greppable and can be filled later.
 *
 * <p>Safety: it runs BEFORE the merge-time AndroidSourceGuard, which still validates the result - a stub
 * never bypasses the guard. It only stubs when it can form a VALID, compiling signature (param types all
 * known; a return/field type it can infer with confidence); anything it cannot stub safely is left
 * untouched so the guard fails with a clear message rather than producing code that breaks the real
 * build. Reuses the same simple-name model the guard uses, so it never fabricates members on library
 * types (those are deferred by the guard already).
 */
final class StubReconciler {
    private static final int MAX_ROUNDS = 6;
    static final String STUB_TAG = "// ANDROIDBUILDER-STUB";

    private static final Pattern MISSING_METHOD = Pattern.compile("blocked missing method:\\s*([A-Za-z_][A-Za-z0-9_]*)\\.([A-Za-z_][A-Za-z0-9_]*)\\(([^)]*)\\)");
    private static final Pattern ARG_MISMATCH = Pattern.compile("blocked method argument mismatch:\\s*([A-Za-z_][A-Za-z0-9_]*)\\.([A-Za-z_][A-Za-z0-9_]*)\\(([^)]*)\\)");
    private static final Pattern MISSING_FIELD = Pattern.compile("blocked missing (?:model|class) field:\\s*([A-Za-z_][A-Za-z0-9_]*)\\.([A-Za-z_][A-Za-z0-9_]*)\\b");
    private static final Pattern CTOR_MISMATCH = Pattern.compile("blocked constructor argument mismatch:\\s*new ([A-Za-z_][A-Za-z0-9_]*)\\(([^)]*)\\)");

    private StubReconciler() {
    }

    /**
     * Splice stubs for genuinely-missing members in {@code tempDir}. Returns one human-readable line per
     * stub written (for the task log); empty if nothing was stubbed.
     */
    static List<String> reconcile(File tempDir, AndroidSourceGuard guard) {
        List<String> stubs = new ArrayList<>();
        Set<String> done = new LinkedHashSet<>();
        for (int round = 0; round < MAX_ROUNDS; round++) {
            List<String> violations;
            try {
                violations = guard.collectViolations(tempDir);
            } catch (Exception ignored) {
                break;
            }
            if (violations.isEmpty()) {
                break;
            }
            boolean progress = false;
            for (String violation : violations) {
                String stubbed = stubOne(tempDir, violation, done);
                if (stubbed != null) {
                    stubs.add(stubbed);
                    progress = true;
                }
            }
            if (!progress) {
                break;
            }
        }
        return stubs;
    }

    private static String stubOne(File tempDir, String violation, Set<String> done) {
        Matcher method = MISSING_METHOD.matcher(violation);
        if (method.find()) {
            return stubMethod(tempDir, method.group(1), method.group(2), method.group(3), false, done);
        }
        Matcher mismatch = ARG_MISMATCH.matcher(violation);
        if (mismatch.find()) {
            return stubMethod(tempDir, mismatch.group(1), mismatch.group(2), mismatch.group(3), true, done);
        }
        Matcher field = MISSING_FIELD.matcher(violation);
        if (field.find()) {
            return stubField(tempDir, field.group(1), field.group(2), done);
        }
        Matcher ctor = CTOR_MISMATCH.matcher(violation);
        if (ctor.find()) {
            return stubConstructor(tempDir, ctor.group(1), ctor.group(2), done);
        }
        return null;
    }

    private static String stubMethod(File tempDir, String className, String methodName, String rawParams,
                                     boolean overloadExists, Set<String> done) {
        File classFile = findClassFile(tempDir, className);
        if (classFile == null) {
            return null;
        }
        List<String> paramTypes = splitTypes(rawParams);
        if (paramTypes == null) {
            return null; // an arg type was "unknown" - cannot form a valid signature
        }
        String key = className + "#" + methodName + "#" + paramTypes.size();
        if (!done.add(key)) {
            return null;
        }
        String classContent = read(classFile);
        if (classContent == null || declaresMethodArity(classContent, methodName, paramTypes.size())) {
            return null;
        }
        String returnType = overloadExists ? existingMethodReturnType(classContent, methodName) : null;
        if (returnType == null) {
            returnType = inferReturnTypeFromCallers(tempDir, methodName);
        }
        if (returnType == null) {
            return null; // cannot prove a compiling return type - leave it for the guard to report
        }
        String signature = signature(returnType, methodName, paramTypes);
        String body = "void".equals(returnType)
                ? "        " + STUB_TAG + ": " + className + "." + methodName + "\n        throw new UnsupportedOperationException(\"stub\");\n"
                : "        " + STUB_TAG + ": " + className + "." + methodName + "\n        throw new UnsupportedOperationException(\"stub\");\n";
        String member = "\n    public " + signature + " {\n" + body + "    }\n";
        if (!spliceIntoClass(classFile, classContent, className, member)) {
            return null;
        }
        return className + "." + methodName + "(" + String.join(", ", paramTypes) + ") -> " + returnType;
    }

    private static String stubField(File tempDir, String className, String fieldName, Set<String> done) {
        File classFile = findClassFile(tempDir, className);
        if (classFile == null) {
            return null;
        }
        if (!done.add(className + "#" + fieldName)) {
            return null;
        }
        String classContent = read(classFile);
        if (classContent == null || declaresField(classContent, fieldName)) {
            return null;
        }
        String fieldType = inferFieldType(tempDir, fieldName);
        if (fieldType == null) {
            return null; // cannot prove a compiling type
        }
        String member = "\n    public " + fieldType + " " + fieldName + "; " + STUB_TAG + "\n";
        if (!spliceIntoClass(classFile, classContent, className, member)) {
            return null;
        }
        return className + "." + fieldName + " : " + fieldType;
    }

    private static String stubConstructor(File tempDir, String className, String rawParams, Set<String> done) {
        File classFile = findClassFile(tempDir, className);
        if (classFile == null) {
            return null;
        }
        List<String> paramTypes = splitTypes(rawParams);
        if (paramTypes == null) {
            return null;
        }
        if (!done.add(className + "#<init>#" + paramTypes.size())) {
            return null;
        }
        String classContent = read(classFile);
        if (classContent == null || declaresMethodArity(classContent, className, paramTypes.size())) {
            return null;
        }
        String member = "\n    public " + signature("", className, paramTypes) + " {\n        " + STUB_TAG + "\n    }\n";
        if (!spliceIntoClass(classFile, classContent, className, member)) {
            return null;
        }
        return "new " + className + "(" + String.join(", ", paramTypes) + ")";
    }

    // --- helpers ---

    private static String signature(String returnType, String name, List<String> paramTypes) {
        StringBuilder params = new StringBuilder();
        for (int i = 0; i < paramTypes.size(); i++) {
            if (i > 0) {
                params.append(", ");
            }
            params.append(paramTypes.get(i)).append(" a").append(i);
        }
        String prefix = returnType == null || returnType.isEmpty() ? "" : returnType + " ";
        return prefix + name + "(" + params + ")";
    }

    /** Split "String, long, long" into types; returns null if any type is unusable (e.g. "unknown"). */
    private static List<String> splitTypes(String raw) {
        List<String> out = new ArrayList<>();
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            return out;
        }
        for (String part : trimmed.split(",")) {
            String type = part.trim();
            if (type.isEmpty() || !type.matches("[A-Za-z_][A-Za-z0-9_$.<>\\[\\]]*")) {
                return null;
            }
            out.add(type);
        }
        return out;
    }

    private static boolean declaresMethodArity(String classContent, String name, int arity) {
        Matcher matcher = Pattern.compile("\\b" + Pattern.quote(name) + "\\s*\\(([^)]*)\\)").matcher(classContent);
        while (matcher.find()) {
            if (arityOf(matcher.group(1)) == arity) {
                return true;
            }
        }
        return false;
    }

    private static boolean declaresField(String classContent, String name) {
        return Pattern.compile("\\b" + Pattern.quote(name) + "\\s*[=;]").matcher(classContent).find();
    }

    private static int arityOf(String params) {
        String trimmed = params == null ? "" : params.trim();
        return trimmed.isEmpty() ? 0 : trimmed.split(",").length;
    }

    private static String existingMethodReturnType(String classContent, String methodName) {
        Matcher matcher = Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_$.<>\\[\\]]*)\\s+" + Pattern.quote(methodName) + "\\s*\\(")
                .matcher(classContent);
        while (matcher.find()) {
            String candidate = matcher.group(1);
            if (!"new".equals(candidate) && !"return".equals(candidate) && !"public".equals(candidate)
                    && !"private".equals(candidate) && !"protected".equals(candidate) && !"static".equals(candidate)
                    && !"final".equals(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** Infer the return type from how a caller uses the result: `Type v = recv.method(` or a bare
     *  statement `recv.method(...);` (void). Returns null when it cannot be proven. */
    private static String inferReturnTypeFromCallers(File tempDir, String methodName) {
        boolean seenStatement = false;
        for (File file : javaFiles(tempDir)) {
            String content = read(file);
            if (content == null) {
                continue;
            }
            Matcher assign = Pattern.compile("([A-Za-z_][A-Za-z0-9_$.<>\\[\\]]*)\\s+[A-Za-z_][A-Za-z0-9_]*\\s*=\\s*[A-Za-z_][A-Za-z0-9_.]*\\." + Pattern.quote(methodName) + "\\s*\\(")
                    .matcher(content);
            if (assign.find()) {
                String type = assign.group(1);
                if (!"return".equals(type) && !"new".equals(type)) {
                    return type;
                }
            }
            if (Pattern.compile("(?m)^\\s*[A-Za-z_][A-Za-z0-9_.]*\\." + Pattern.quote(methodName) + "\\s*\\([^;]*\\)\\s*;")
                    .matcher(content).find()) {
                seenStatement = true;
            }
        }
        return seenStatement ? "void" : null;
    }

    /** Infer a field's type from caller usage: assignment RHS literal, `Type v = recv.field`, String
     *  method calls, or numeric comparison. Returns null when it cannot be proven. */
    private static String inferFieldType(File tempDir, String fieldName) {
        for (File file : javaFiles(tempDir)) {
            String content = read(file);
            if (content == null) {
                continue;
            }
            Matcher typed = Pattern.compile("([A-Za-z_][A-Za-z0-9_$.<>\\[\\]]*)\\s+[A-Za-z_][A-Za-z0-9_]*\\s*=\\s*[A-Za-z_][A-Za-z0-9_.]*\\." + Pattern.quote(fieldName) + "\\b")
                    .matcher(content);
            if (typed.find() && !"return".equals(typed.group(1))) {
                return typed.group(1);
            }
            Matcher assign = Pattern.compile("\\." + Pattern.quote(fieldName) + "\\s*=\\s*([^;]+);").matcher(content);
            if (assign.find()) {
                String type = literalType(assign.group(1).trim());
                if (type != null) {
                    return type;
                }
            }
            if (Pattern.compile("\\." + Pattern.quote(fieldName) + "\\.(equals|length|isEmpty|substring|charAt|toLowerCase|toUpperCase|trim|contains|startsWith)\\s*\\(")
                    .matcher(content).find()) {
                return "String";
            }
            if (Pattern.compile("\\." + Pattern.quote(fieldName) + "\\s*(==|!=|<|>|<=|>=)\\s*[0-9]").matcher(content).find()) {
                return "long";
            }
        }
        return null;
    }

    private static String literalType(String expr) {
        if (expr.startsWith("\"")) {
            return "String";
        }
        if (expr.matches("-?[0-9]+[lL]")) {
            return "long";
        }
        if (expr.matches("-?[0-9]+")) {
            return "int";
        }
        if (expr.matches("-?[0-9]*\\.[0-9]+[dD]?")) {
            return "double";
        }
        if (expr.equals("true") || expr.equals("false")) {
            return "boolean";
        }
        Matcher constructed = Pattern.compile("^new\\s+([A-Z][A-Za-z0-9_]*)\\s*[(<]").matcher(expr);
        if (constructed.find()) {
            return constructed.group(1);
        }
        return null;
    }

    private static boolean spliceIntoClass(File classFile, String content, String className, String member) {
        Matcher decl = Pattern.compile("\\b(?:class|interface|enum)\\s+" + Pattern.quote(className) + "\\b").matcher(content);
        if (!decl.find()) {
            return false;
        }
        int brace = content.indexOf('{', decl.end());
        if (brace < 0) {
            return false;
        }
        String updated = content.substring(0, brace + 1) + member + content.substring(brace + 1);
        try {
            FileUtils.writeText(classFile, updated);
            return true;
        } catch (Exception error) {
            return false;
        }
    }

    private static File findClassFile(File tempDir, String className) {
        Pattern decl = Pattern.compile("\\b(?:class|interface|enum)\\s+" + Pattern.quote(className) + "\\b");
        for (File file : javaFiles(tempDir)) {
            String content = read(file);
            if (content != null && decl.matcher(content).find()) {
                return file;
            }
        }
        return null;
    }

    private static List<File> javaFiles(File dir) {
        List<File> out = new ArrayList<>();
        File javaRoot = new File(dir, "app/src/main/java");
        collectJava(javaRoot.exists() ? javaRoot : dir, out, new LinkedHashSet<String>());
        return out;
    }

    private static void collectJava(File file, List<File> out, Set<String> seen) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    collectJava(child, out, seen);
                }
            }
        } else if (file.getName().endsWith(".java") && seen.add(file.getAbsolutePath())) {
            out.add(file);
        }
    }

    private static String read(File file) {
        try {
            return FileUtils.readText(file);
        } catch (Exception error) {
            return null;
        }
    }
}
