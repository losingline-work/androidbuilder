package com.androidbuilder.agent;

import com.androidbuilder.util.FileUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic backstop for cross-file method-name mismatches across the generated Java tree, where the
 * caller invokes receiver.foo() but "foo" is declared on NO class anywhere (so it is definitely wrong) and
 * the receiver's class declares exactly ONE matching method. Two mismatch families are reconciled:
 * <ul>
 *   <li><b>accessor-prefix</b> — the model guessed a bare/boolean-style accessor for a field whose real
 *       getter/setter is prefixed: {@code category.isSystem()} when the class declares {@code getIsSystem()}
 *       (field {@code isSystem}), or {@code o.title(v)} when {@code setTitle(v)} is declared. The single most
 *       common cross-file drift in generated DTO/DAO code.</li>
 *   <li><b>single-character typo</b> — exactly one arity-compatible method within edit distance 1.</li>
 * </ul>
 * AndroidSourceGuard re-validates afterwards, so a rewrite that produces any detectable inconsistency is
 * still rejected.
 *
 * Intentionally conservative: it never touches a name that exists somewhere (could be a real call),
 * never rewrites fields, and deny-lists getWritableDb/getReadableDb so a truncated DbHelper is left
 * for the guard to reject rather than silently reconciled to the wrong inherited call.
 */
final class JavaApiReconciler {
    private static final Pattern CLASS_DECL = Pattern.compile("\\bclass\\s+([A-Z][A-Za-z0-9_]*)");
    private static final Pattern METHOD_DECL = Pattern.compile("\\b(?:public|protected|private)?\\s*(?:static\\s+)?(?:final\\s+)?[A-Za-z_][A-Za-z0-9_$.<>?\\[\\]]*\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(([^)]*)\\)\\s*[\\{;]");
    // Type→variable bindings: locals/fields (`Type x =` / `Type x;`) AND method parameters / for-each / catch
    // (`Type x,` / `Type x)`) — DAOs call into a model passed as a parameter, so params must resolve too.
    private static final Pattern LOCAL_VAR = Pattern.compile("\\b([A-Z][A-Za-z0-9_]*)\\s+([a-z_][A-Za-z0-9_]*)\\s*[=;,)]");
    private static final Pattern CALL = Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_]*)\\.([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");
    private static final Set<String> DENY = new HashSet<>(java.util.Arrays.asList("getWritableDb", "getReadableDb"));

    private JavaApiReconciler() {
    }

    static void reconcile(File sourceDir) {
        if (sourceDir == null || !sourceDir.exists()) {
            return;
        }
        List<File> javaFiles = new ArrayList<>();
        collectJava(sourceDir, javaFiles);
        if (javaFiles.isEmpty()) {
            return;
        }
        Map<String, Map<String, Set<Integer>>> methodsByClass = new HashMap<>();
        Set<String> allDeclaredMethods = new HashSet<>();
        Map<File, String> contents = new HashMap<>();
        for (File file : javaFiles) {
            String content;
            try {
                content = FileUtils.readText(file);
            } catch (Exception ignored) {
                continue;
            }
            contents.put(file, content);
            indexDeclarations(content, methodsByClass, allDeclaredMethods);
        }
        for (Map.Entry<File, String> entry : contents.entrySet()) {
            String updated = reconcileFile(entry.getValue(), methodsByClass, allDeclaredMethods);
            if (!updated.equals(entry.getValue())) {
                try {
                    FileUtils.writeText(entry.getKey(), updated);
                } catch (Exception ignored) {
                    // Best-effort: a failed rewrite leaves the original for the guard to adjudicate.
                }
            }
        }
    }

    private static String reconcileFile(String content,
                                        Map<String, Map<String, Set<Integer>>> methodsByClass,
                                        Set<String> allDeclaredMethods) {
        Map<String, String> varTypes = new HashMap<>();
        Matcher var = LOCAL_VAR.matcher(content);
        while (var.find()) {
            varTypes.put(var.group(2), var.group(1));
        }
        StringBuilder result = new StringBuilder();
        int last = 0;
        Matcher call = CALL.matcher(content);
        while (call.find()) {
            String receiver = call.group(1);
            String method = call.group(2);
            String receiverClass = methodsByClass.containsKey(receiver) ? receiver : varTypes.get(receiver);
            String replacement = candidate(receiverClass, method, content, call.end(), methodsByClass, allDeclaredMethods);
            if (replacement != null) {
                result.append(content, last, call.start(2));
                result.append(replacement);
                last = call.end(2);
            }
        }
        result.append(content, last, content.length());
        return result.toString();
    }

    private static String candidate(String receiverClass, String method, String content, int callEnd,
                                    Map<String, Map<String, Set<Integer>>> methodsByClass,
                                    Set<String> allDeclaredMethods) {
        if (receiverClass == null || DENY.contains(method) || allDeclaredMethods.contains(method)) {
            return null;
        }
        Map<String, Set<Integer>> methods = methodsByClass.get(receiverClass);
        if (methods == null || methods.containsKey(method)) {
            return null;
        }
        int arity = callArity(content, callEnd);
        // Accessor-prefix mismatch first (e.g. isSystem() -> getIsSystem()); it is the most common drift and
        // unambiguous, so it takes precedence over the edit-distance heuristic.
        String accessor = accessorCandidate(method, arity, methods);
        if (accessor != null) {
            return accessor;
        }
        String match = null;
        for (Map.Entry<String, Set<Integer>> declared : methods.entrySet()) {
            if (editDistanceWithin1(method, declared.getKey()) && declared.getValue().contains(arity)) {
                if (match != null) {
                    return null; // ambiguous: more than one near-miss candidate
                }
                match = declared.getKey();
            }
        }
        return match;
    }

    /** The receiver's declared accessor for the field the caller named bare: a 0-arg {@code X()} maps to
     * {@code getX()}/{@code isX()}, a 1-arg {@code X(v)} maps to {@code setX(v)}. Returns the single declared
     * variant with a matching arity, or null when none or more than one match (kept unambiguous). */
    private static String accessorCandidate(String method, int arity, Map<String, Set<Integer>> methods) {
        if (method.isEmpty()) {
            return null;
        }
        String cap = Character.toUpperCase(method.charAt(0)) + method.substring(1);
        List<String> variants = new ArrayList<>();
        if (arity == 0) {
            variants.add("get" + cap);
            variants.add("is" + cap);
        } else if (arity == 1) {
            variants.add("set" + cap);
        }
        String match = null;
        for (String variant : variants) {
            Set<Integer> arities = methods.get(variant);
            if (arities != null && arities.contains(arity)) {
                if (match != null) {
                    return null; // ambiguous (e.g. both getX and isX declared)
                }
                match = variant;
            }
        }
        return match;
    }

    private static void indexDeclarations(String content,
                                          Map<String, Map<String, Set<Integer>>> methodsByClass,
                                          Set<String> allDeclaredMethods) {
        Matcher classMatcher = CLASS_DECL.matcher(content);
        if (!classMatcher.find()) {
            return;
        }
        String className = classMatcher.group(1);
        Map<String, Set<Integer>> methods = methodsByClass.computeIfAbsent(className, k -> new HashMap<>());
        Matcher methodMatcher = METHOD_DECL.matcher(content);
        while (methodMatcher.find()) {
            String name = methodMatcher.group(1);
            if ("if".equals(name) || "for".equals(name) || "while".equals(name) || "switch".equals(name)
                    || "catch".equals(name) || className.equals(name)) {
                continue;
            }
            // Skip `new Type(` etc.: the constructed type name is not a declared method.
            if (precededByNew(content, methodMatcher.start(1))) {
                continue;
            }
            allDeclaredMethods.add(name);
            methods.computeIfAbsent(name, k -> new HashSet<>()).add(paramArity(methodMatcher.group(2)));
        }
    }

    private static boolean precededByNew(String content, int nameStart) {
        int cursor = nameStart - 1;
        while (cursor >= 0 && Character.isWhitespace(content.charAt(cursor))) {
            cursor--;
        }
        int end = cursor + 1;
        while (cursor >= 0 && Character.isJavaIdentifierPart(content.charAt(cursor))) {
            cursor--;
        }
        return end > 0 && "new".equals(content.substring(cursor + 1, end));
    }

    private static int paramArity(String params) {
        String trimmed = params == null ? "" : params.trim();
        if (trimmed.isEmpty()) {
            return 0;
        }
        int depth = 0;
        int count = 1;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '<' || c == '(' || c == '[') {
                depth++;
            } else if (c == '>' || c == ')' || c == ']') {
                depth--;
            } else if (c == ',' && depth == 0) {
                count++;
            }
        }
        return count;
    }

    private static int callArity(String content, int afterParen) {
        int depth = 1;
        int count = 0;
        boolean sawArg = false;
        for (int i = afterParen; i < content.length() && depth > 0; i++) {
            char c = content.charAt(i);
            if (c == '(' || c == '<' || c == '[') {
                depth++;
            } else if (c == ')' || c == '>' || c == ']') {
                depth--;
                if (depth == 0 && sawArg) {
                    count++;
                }
            } else if (c == ',' && depth == 1) {
                count++;
            } else if (!Character.isWhitespace(c) && depth == 1) {
                sawArg = true;
            }
        }
        return count;
    }

    private static boolean editDistanceWithin1(String a, String b) {
        if (a.equals(b)) {
            return false;
        }
        int la = a.length();
        int lb = b.length();
        if (Math.abs(la - lb) > 1) {
            return false;
        }
        if (la == lb) {
            int diffs = 0;
            for (int i = 0; i < la; i++) {
                if (a.charAt(i) != b.charAt(i) && ++diffs > 1) {
                    return false;
                }
            }
            return diffs == 1;
        }
        String shorter = la < lb ? a : b;
        String longer = la < lb ? b : a;
        int i = 0;
        int j = 0;
        boolean skipped = false;
        while (i < shorter.length() && j < longer.length()) {
            if (shorter.charAt(i) == longer.charAt(j)) {
                i++;
                j++;
            } else if (skipped) {
                return false;
            } else {
                skipped = true;
                j++;
            }
        }
        return true;
    }

    private static void collectJava(File file, List<File> out) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) {
                return;
            }
            for (File child : children) {
                collectJava(child, out);
            }
            return;
        }
        if (file.getName().toLowerCase(Locale.ROOT).endsWith(".java")) {
            out.add(file);
        }
    }
}
