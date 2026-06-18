package com.androidbuilder.agent;

import com.androidbuilder.model.FileOperation;
import com.androidbuilder.model.TaskOperations;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class LocalGuardHeuristics {
    private static final Pattern R_DRAWABLE = Pattern.compile("\\bR\\.drawable\\.([A-Za-z_][A-Za-z0-9_]*)\\b");
    private static final Pattern DBHELPER_FIELD = Pattern.compile("\\bDBHelper\\.([A-Z][A-Z0-9_]+)\\b");
    private static final Pattern DAO_DECLARATION = Pattern.compile("\\b([A-Z][A-Za-z0-9_]*Dao)\\s+([A-Za-z_][A-Za-z0-9_]*)\\b");
    private static final Pattern METHOD_CALL = Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_]*)\\.([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");
    private static final Pattern MISSING_DRAWABLE = Pattern.compile("missing drawable resource:\\s*R\\.drawable\\.([A-Za-z_][A-Za-z0-9_]*)\\s+in\\s+([A-Za-z0-9_.$-]+\\.java)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MISSING_XML_RESOURCE = Pattern.compile("missing XML resource reference:\\s*@([a-z]+)/([A-Za-z_][A-Za-z0-9_.]*)\\s+in\\s+([A-Za-z0-9_.$-]+\\.xml)", Pattern.CASE_INSENSITIVE);
    private static final Pattern XML_RESOURCE_REFERENCE = Pattern.compile("@(layout|string|color|drawable|mipmap|style)/([A-Za-z_][A-Za-z0-9_.]*)");
    private static final Pattern NAMED_VALUE_RESOURCE = Pattern.compile("<\\s*(string|color|style)\\b[^>]*\\bname\\s*=\\s*[\"']([A-Za-z_][A-Za-z0-9_.]*)[\"']");
    private static final Pattern MISSING_METHOD = Pattern.compile("missing method:\\s*([A-Z][A-Za-z0-9_]*)\\.([A-Za-z_][A-Za-z0-9_]*)\\(([^)]*)\\)\\s+in\\s+([A-Za-z0-9_.$-]+\\.java)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MISSING_CLASS_FIELD = Pattern.compile("missing class field:\\s*([A-Z][A-Za-z0-9_]*)\\.([A-Za-z_][A-Za-z0-9_]*)\\s+in\\s+([A-Za-z0-9_.$-]+\\.java)", Pattern.CASE_INSENSITIVE);

    private LocalGuardHeuristics() {
    }

    static LocalGuardResult reviewOperations(String sourceSnapshot, TaskOperations operations) {
        // The deterministic pre-apply review no longer raises ANY hint. Every check it used to run is
        // owned by a real build tool that the source flows to anyway:
        //  - cross-file Java API mismatch (a call to a DAO method / DBHelper field / model field not
        //    declared) is javac's authority at the compile gate;
        //  - resource existence (@type/name, R.*) is aapt's authority at resource linking;
        //  - the lambda/Kotlin/synthetic policy is the merge-time AndroidSourceGuard's authority.
        // The preflight only sees ONE task's operations plus a DIGESTED snapshot, so when the missing
        // declaration lives in an already-accepted (frozen) batch or a sibling task, its rewrite demand
        // is UNSATISFIABLE and loops the task to exhaustion (observed: a data-layer task whose frozen DAO
        // could not gain the method its later batch's callers needed, burning all 5 attempts). The build's
        // compile/resource gates + the auto-repair loop see the WHOLE tree and can modify the frozen file,
        // so they close any real gap without the false in-task loop. Kept as a seam for future
        // genuinely-local, always-satisfiable checks.
        return LocalGuardResult.unusable("");
    }

    static LocalGuardResult rewritePolicyFailure(String policyError) {
        String message = policyError == null ? "" : policyError;
        StringBuilder hints = new StringBuilder();
        Matcher drawable = MISSING_DRAWABLE.matcher(message);
        if (drawable.find()) {
            String name = drawable.group(1);
            String file = drawable.group(2);
            appendHint(hints, file + " references R.drawable." + name
                    + " but that drawable is missing. Add app/src/main/res/drawable/" + name
                    + ".xml as a valid vector/shape drawable in the same response, or change " + file
                    + " to use an existing drawable resource.");
        }
        Matcher xmlResource = MISSING_XML_RESOURCE.matcher(message);
        if (xmlResource.find()) {
            appendHint(hints, xmlResource.group(3) + " references @" + xmlResource.group(1)
                    + "/" + xmlResource.group(2) + " but that resource is missing. "
                    + xmlResourceFixInstruction(xmlResource.group(1), xmlResource.group(2), xmlResource.group(3)));
        }
        Matcher method = MISSING_METHOD.matcher(message);
        if (method.find()) {
            appendHint(hints, "The caller " + method.group(4) + " uses "
                    + method.group(1) + "." + method.group(2) + "(" + method.group(3)
                    + "). Add that exact method with a matching return type/signature, or update "
                    + method.group(4) + " to call an existing API. Do not leave the caller unchanged.");
        }
        Matcher field = MISSING_CLASS_FIELD.matcher(message);
        if (field.find()) {
            appendHint(hints, "The caller " + field.group(3) + " uses "
                    + field.group(1) + "." + field.group(2)
                    + ". Add that exact constant/field to " + field.group(1)
                    + " or update the caller to use an existing field.");
        }
        if (hints.length() == 0) {
            return LocalGuardResult.unusable("");
        }
        return LocalGuardResult.rewrite("Deterministic rules converted the policy error into a precise retry hint.", hints.toString());
    }

    private static void appendMissingXmlResourceHints(StringBuilder hints, String sourceSnapshot, TaskOperations operations, String path, String content) {
        Matcher matcher = XML_RESOURCE_REFERENCE.matcher(content);
        Set<String> seen = new HashSet<>();
        while (matcher.find()) {
            String type = matcher.group(1);
            String name = matcher.group(2);
            String key = type + "/" + name;
            if (!seen.add(key) || hasXmlResource(sourceSnapshot, operations, type, name)
                    || FrameworkResourcePolicy.isLibraryProvided(type, name)) {
                continue;
            }
            appendHint(hints, simpleName(path) + " references @" + type + "/" + name
                    + " but that resource is missing. " + xmlResourceFixInstruction(type, name, simpleName(path)));
        }
    }

    private static void appendMissingDrawableHints(StringBuilder hints, String sourceSnapshot, TaskOperations operations, String path, String content) {
        Matcher matcher = R_DRAWABLE.matcher(content);
        Set<String> seen = new HashSet<>();
        Set<String> missing = new LinkedHashSet<>();
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!seen.add(name)) {
                continue;
            }
            if (hasDrawable(sourceSnapshot, operations, name)) {
                continue;
            }
            missing.add(name);
        }
        if (missing.isEmpty()) {
            return;
        }
        if (missing.size() == 1) {
            String name = missing.iterator().next();
            appendHint(hints, simpleName(path) + " references R.drawable." + name
                    + " but no drawable resource is present. Add app/src/main/res/drawable/" + name
                    + ".xml in the same operations, or change the Java reference to an existing drawable.");
            return;
        }
        appendHint(hints, simpleName(path) + " references missing drawable resources: "
                + joinNames(missing)
                + ". Either add vector drawable XML files for all of them in the same operations (app/src/main/res/drawable/<name>.xml), or refactor "
                + simpleName(path)
                + " to use Resources.getIdentifier(...) with one already-declared fallback drawable. Do not keep static R.drawable.* references without matching resources.");
    }

    private static void appendMissingDbHelperFieldHints(StringBuilder hints, String sourceSnapshot, TaskOperations operations, String path, String content) {
        Matcher matcher = DBHELPER_FIELD.matcher(content);
        Set<String> seen = new HashSet<>();
        while (matcher.find()) {
            String field = matcher.group(1);
            if (!seen.add(field) || hasClassField(sourceSnapshot, operations, "DBHelper", field)) {
                continue;
            }
            // Same positive-evidence rule as the DAO check: only flag a missing DBHelper field when
            // DBHelper is conclusively visible (in operations, or a full-text snapshot section), so a
            // stale/truncated DBHelper section never produces a phantom rewrite.
            if (!declarationIsConclusive(sourceSnapshot, operations, "DBHelper")) {
                continue;
            }
            appendHint(hints, simpleName(path) + " references DBHelper." + field
                    + " but DBHelper does not declare it. Add that exact constant or update the caller to an existing DBHelper field.");
        }
    }

    /**
     * Whether a class's declarations are conclusively visible - enough to prove a member is ABSENT.
     * True when the class file is among the operations being written (full content this round) or it
     * appears as a full-text, non-truncated snapshot section. False when it is known only from the
     * budgeted Java-API digest tail or a truncated section, where absence cannot be proven.
     */
    private static boolean declarationIsConclusive(String snapshot, TaskOperations operations, String className) {
        if (operationsContainClass(operations, className)) {
            return true;
        }
        return hasFullTextSnapshotSection(snapshot, className);
    }

    private static boolean operationsContainClass(TaskOperations operations, String className) {
        if (operations == null) {
            return false;
        }
        for (FileOperation operation : operations.operations) {
            if (operation != null && operation.path != null && operation.content != null
                    && operation.path.endsWith("/" + className + ".java")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasFullTextSnapshotSection(String snapshot, String className) {
        if (snapshot == null || snapshot.length() == 0) {
            return false;
        }
        for (String section : snapshot.split("(?m)^--- ")) {
            int newline = section.indexOf('\n');
            if (newline < 0) {
                continue;
            }
            String header = section.substring(0, newline).trim();
            if (!header.endsWith("---")) {
                continue;
            }
            String headerPath = header.substring(0, header.length() - 3).trim();
            boolean focusedFile = headerPath.endsWith("/" + className + ".java") || headerPath.equals(className + ".java");
            if (!focusedFile) {
                continue;
            }
            // A full-text section cut off by the snapshot budget ends with the truncation marker; its
            // declarations may be incomplete, so it cannot prove a member is absent.
            if (section.contains(SourceSnapshotComposer.TRUNCATED.trim())) {
                continue;
            }
            return true;
        }
        return false;
    }

    private static void appendMissingDaoMethodHints(StringBuilder hints, String sourceSnapshot, TaskOperations operations, String path, String content) {
        Map<String, String> daoVars = daoVariables(content);
        if (daoVars.isEmpty()) {
            return;
        }
        Matcher matcher = METHOD_CALL.matcher(content);
        Set<String> seen = new HashSet<>();
        Map<String, String> declarationsByClass = new HashMap<>();
        Map<String, Boolean> conclusiveByClass = new HashMap<>();
        while (matcher.find()) {
            String receiver = matcher.group(1);
            String method = matcher.group(2);
            String daoClass = daoVars.get(receiver);
            if (daoClass == null) {
                continue;
            }
            String key = daoClass + "." + method;
            if (!seen.add(key)) {
                continue;
            }
            String declarations = declarationsByClass.get(daoClass);
            if (declarations == null) {
                declarations = declarationsForClass(sourceSnapshot, operations, daoClass);
                declarationsByClass.put(daoClass, declarations);
                conclusiveByClass.put(daoClass, declarationIsConclusive(sourceSnapshot, operations, daoClass));
            }
            // Only adjudicate "method not declared" when the DAO's full declarations are actually
            // visible: it is among the operations being written (full content this round), or it
            // appears as a full-text, non-truncated snapshot section. A DAO known only from a
            // truncated section or the budgeted Java-API digest cannot prove the method is missing -
            // e.g. a stale on-disk DAO from a sibling task that this (never-committed) task has
            // already superseded in its draft. Flagging there is the phantom "listInRange not
            // declared" seen in project-82. A genuinely absent method on a conclusively-visible DAO
            // is still flagged below, and the merge-time AndroidSourceGuard remains the authority on
            // the full assembled tree regardless.
            if (!Boolean.TRUE.equals(conclusiveByClass.get(daoClass))) {
                continue;
            }
            if (declaresMethod(declarations, method)) {
                continue;
            }
            appendHint(hints, simpleName(path) + " calls " + daoClass + "." + method
                    + "() but that DAO method is not declared. Add " + daoClass + "." + method
                    + "() with the return type expected by " + simpleName(path)
                    + ", or update " + simpleName(path) + " to use an existing DAO method.");
        }
    }

    private static Map<String, String> daoVariables(String content) {
        Map<String, String> values = new HashMap<>();
        Matcher matcher = DAO_DECLARATION.matcher(content);
        while (matcher.find()) {
            values.put(matcher.group(2), matcher.group(1));
        }
        return values;
    }

    private static boolean hasDrawable(String sourceSnapshot, TaskOperations operations, String name) {
        String text = sourceSnapshot == null ? "" : sourceSnapshot;
        if (text.contains("/drawable/" + name + ".xml") || text.contains("/drawable-v") && text.contains(name + ".xml")) {
            return true;
        }
        if (operations != null) {
            for (FileOperation operation : operations.operations) {
                if (operation == null || operation.path == null) {
                    continue;
                }
                String path = operation.path;
                if (path.contains("/res/drawable") && path.endsWith("/" + name + ".xml")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasXmlResource(String sourceSnapshot, TaskOperations operations, String type, String name) {
        if (hasXmlResourceInText(sourceSnapshot, type, name)) {
            return true;
        }
        if (operations != null) {
            for (FileOperation operation : operations.operations) {
                if (operation == null) {
                    continue;
                }
                if (hasXmlResourceInPath(operation.path, type, name)
                        || hasValueResourceInContent(operation.content, type, name)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasXmlResourceInText(String text, String type, String name) {
        String value = text == null ? "" : text;
        return hasXmlResourceInPath(value, type, name) || hasValueResourceInContent(value, type, name);
    }

    private static boolean hasXmlResourceInPath(String path, String type, String name) {
        String value = path == null ? "" : path;
        if ("layout".equals(type)) {
            return value.contains("/res/layout/") && value.contains("/" + name + ".xml");
        }
        if ("drawable".equals(type)) {
            return value.contains("/res/drawable") && value.contains("/" + name + ".xml");
        }
        if ("mipmap".equals(type)) {
            return value.contains("/res/mipmap") && value.contains("/" + name + ".xml");
        }
        return false;
    }

    private static boolean hasValueResourceInContent(String content, String type, String name) {
        if (!"string".equals(type) && !"color".equals(type) && !"style".equals(type)) {
            return false;
        }
        Matcher matcher = NAMED_VALUE_RESOURCE.matcher(content == null ? "" : content);
        while (matcher.find()) {
            if (type.equals(matcher.group(1)) && name.equals(matcher.group(2))) {
                return true;
            }
        }
        return false;
    }

    private static String xmlResourceFixInstruction(String type, String name, String callerFile) {
        if ("color".equals(type)) {
            return "Add <color name=\"" + name + "\">...</color> to app/src/main/res/values/colors.xml in the same response, or change "
                    + callerFile + " to use an existing @color resource.";
        }
        if ("string".equals(type)) {
            return "Add <string name=\"" + name + "\">...</string> to app/src/main/res/values/strings.xml in the same response, or change "
                    + callerFile + " to use an existing @string resource.";
        }
        if ("style".equals(type)) {
            return "Add <style name=\"" + name + "\">...</style> to app/src/main/res/values/styles.xml in the same response, or change "
                    + callerFile + " to use an existing @style resource.";
        }
        if ("layout".equals(type)) {
            return "Add app/src/main/res/layout/" + name + ".xml in the same response, or change "
                    + callerFile + " to use an existing @layout resource.";
        }
        if ("drawable".equals(type)) {
            return "Add app/src/main/res/drawable/" + name + ".xml as a valid vector/shape drawable in the same response, or change "
                    + callerFile + " to use an existing @drawable resource.";
        }
        if ("mipmap".equals(type)) {
            return "Add app/src/main/res/mipmap/" + name + ".xml in the same response, or change "
                    + callerFile + " to use an existing @mipmap resource.";
        }
        return "Add the missing @" + type + "/" + name + " resource in the same response, or change "
                + callerFile + " to use an existing resource.";
    }

    private static boolean hasClassField(String sourceSnapshot, TaskOperations operations, String className, String field) {
        String declarations = declarationsForClass(sourceSnapshot, operations, className);
        return Pattern.compile("\\b" + Pattern.quote(field) + "\\b").matcher(declarations).find();
    }

    private static boolean declaresMethod(String declarations, String method) {
        return Pattern.compile("\\b" + Pattern.quote(method) + "\\s*\\(").matcher(declarations).find();
    }

    private static String declarationsForClass(String sourceSnapshot, TaskOperations operations, String className) {
        StringBuilder builder = new StringBuilder();
        appendSnapshotSectionsForClass(builder, sourceSnapshot, className);
        if (operations != null) {
            for (FileOperation operation : operations.operations) {
                if (operation != null && operation.path != null && operation.content != null
                        && operation.path.endsWith("/" + className + ".java")) {
                    builder.append('\n').append(operation.content);
                }
            }
        }
        return builder.toString();
    }

    private static void appendSnapshotSectionsForClass(StringBuilder builder, String snapshot, String className) {
        if (snapshot == null || snapshot.length() == 0) {
            return;
        }
        String marker = className + ".java";
        String[] sections = snapshot.split("(?m)^--- ");
        for (String section : sections) {
            if (section.contains(marker)) {
                builder.append('\n').append(section);
            }
        }
    }

    private static void appendHint(StringBuilder builder, String hint) {
        if (builder.length() > 0) {
            builder.append('\n');
        }
        builder.append("- ").append(hint);
    }

    private static String simpleName(String path) {
        if (path == null) {
            return "";
        }
        int index = path.lastIndexOf('/');
        String name = index >= 0 ? path.substring(index + 1) : path;
        return name.toLowerCase(Locale.ROOT).endsWith(".java") ? name : path;
    }

    private static String joinNames(Set<String> names) {
        StringBuilder builder = new StringBuilder();
        for (String name : names) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(name);
        }
        return builder.toString();
    }
}
