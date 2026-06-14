package com.androidbuilder.agent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The cross-file Java symbol model shared by {@link AndroidSourceGuard} (the merge-time authority) and
 * any earlier deterministic validator (e.g. a contract linter).
 *
 * <p>It can be populated two ways that produce IDENTICAL query results: by the guard parsing on-disk
 * {@code .java} sources (writing the public maps via {@link #ensureClass}/{@link #addMethod}/etc.), or
 * by ingesting TYPED class facts via {@link #addClass}. The typed path exists precisely so a contract
 * can be checked with the exact same {@code hasMethod}/{@code hasMethodSignature}/
 * {@code availableConstructors}/{@code isAssignable} logic the guard uses - without rendering
 * signatures back to text and re-parsing them, which would diverge from the guard's parser and
 * re-introduce false positives (nested/qualified names, generics in {@code extends}).
 */
final class SymbolTable {
    final Map<String, Set<String>> fieldsByClass = new HashMap<>();
    final Map<String, Map<String, List<List<String>>>> methodsByClass = new HashMap<>();
    final Map<String, List<List<String>>> constructorsByClass = new HashMap<>();
    final Map<String, String> superClassByClass = new HashMap<>();

    void ensureClass(String className) {
        if (!fieldsByClass.containsKey(className)) {
            fieldsByClass.put(className, new HashSet<String>());
        }
        if (!methodsByClass.containsKey(className)) {
            methodsByClass.put(className, new HashMap<String, List<List<String>>>());
        }
        if (!constructorsByClass.containsKey(className)) {
            constructorsByClass.put(className, new ArrayList<List<String>>());
        }
    }

    void addMethod(String className, String methodName, List<String> parameterTypes) {
        ensureClass(className);
        Map<String, List<List<String>>> methods = methodsByClass.get(className);
        List<List<String>> signatures = methods.get(methodName);
        if (signatures == null) {
            signatures = new ArrayList<>();
            methods.put(methodName, signatures);
        }
        signatures.add(parameterTypes);
    }

    boolean hasClass(String className) {
        return fieldsByClass.containsKey(className);
    }

    boolean hasField(String className, String fieldName) {
        Set<String> fields = fieldsByClass.get(className);
        if (fields != null && fields.contains(fieldName)) {
            return true;
        }
        String parent = superClassByClass.get(className);
        Set<String> visited = new HashSet<>();
        while (parent != null && visited.add(parent)) {
            fields = fieldsByClass.get(parent);
            if (fields != null && fields.contains(fieldName)) {
                return true;
            }
            parent = superClassByClass.get(parent);
        }
        return false;
    }

    boolean hasMethod(String className, String methodName) {
        if (methodsByClass.containsKey(className) && methodsByClass.get(className).containsKey(methodName)) {
            return true;
        }
        String parent = superClassByClass.get(className);
        Set<String> visited = new HashSet<>();
        while (parent != null && visited.add(parent)) {
            Map<String, List<List<String>>> methods = methodsByClass.get(parent);
            if (methods != null && methods.containsKey(methodName)) {
                return true;
            }
            parent = superClassByClass.get(parent);
        }
        return false;
    }

    boolean hasMethodSignature(String className, String methodName, List<String> argumentTypes) {
        if (hasMethodSignatureInClass(className, methodName, argumentTypes)) {
            return true;
        }
        String parent = superClassByClass.get(className);
        Set<String> visited = new HashSet<>();
        while (parent != null && visited.add(parent)) {
            if (hasMethodSignatureInClass(parent, methodName, argumentTypes)) {
                return true;
            }
            parent = superClassByClass.get(parent);
        }
        return false;
    }

    private boolean hasMethodSignatureInClass(String className, String methodName, List<String> argumentTypes) {
        Map<String, List<List<String>>> methods = methodsByClass.get(className);
        if (methods == null) {
            return false;
        }
        List<List<String>> signatures = methods.get(methodName);
        if (signatures == null) {
            return false;
        }
        for (List<String> signature : signatures) {
            if (signature.size() != argumentTypes.size()) {
                continue;
            }
            boolean matches = true;
            for (int i = 0; i < signature.size(); i++) {
                if (!isAssignable(argumentTypes.get(i), signature.get(i))) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return true;
            }
        }
        return false;
    }

    List<List<String>> availableConstructors(String className) {
        List<List<String>> constructors = constructorsByClass.get(className);
        if (constructors == null || constructors.isEmpty()) {
            List<List<String>> defaultConstructor = new ArrayList<>();
            defaultConstructor.add(new ArrayList<String>());
            return defaultConstructor;
        }
        return constructors;
    }

    // --- Typed ingest (used by the contract linter; produces the same facts as the on-disk parser) ---

    /** A method's name and (raw, will be simplified) parameter types. */
    static final class MethodFact {
        final String name;
        final List<String> paramTypes;

        MethodFact(String name, List<String> paramTypes) {
            this.name = name;
            this.paramTypes = paramTypes == null ? new ArrayList<String>() : paramTypes;
        }
    }

    /**
     * Ingest a class from typed facts, normalising types exactly as the on-disk parser does
     * (parameter/field/super types are reduced via {@link #simpleType}), so a contract-built table and
     * a source-built table answer queries identically.
     */
    void addClass(String simpleName, String superType, List<String> fieldNames,
                  List<MethodFact> methods, List<List<String>> constructorParamTypes) {
        if (simpleName == null || simpleName.isEmpty()) {
            return;
        }
        ensureClass(simpleName);
        String parent = simpleType(superType);
        if (!parent.isEmpty()) {
            superClassByClass.put(simpleName, parent);
        }
        if (fieldNames != null) {
            for (String field : fieldNames) {
                if (field != null && !field.trim().isEmpty()) {
                    fieldsByClass.get(simpleName).add(field.trim());
                }
            }
        }
        if (methods != null) {
            for (MethodFact method : methods) {
                if (method != null && method.name != null && !method.name.trim().isEmpty()) {
                    addMethod(simpleName, method.name.trim(), simplifyTypes(method.paramTypes));
                }
            }
        }
        if (constructorParamTypes != null) {
            for (List<String> params : constructorParamTypes) {
                constructorsByClass.get(simpleName).add(simplifyTypes(params));
            }
        }
    }

    private static List<String> simplifyTypes(List<String> types) {
        List<String> out = new ArrayList<>();
        if (types != null) {
            for (String type : types) {
                String simple = simpleType(type);
                if (!simple.isEmpty()) {
                    out.add(simple);
                }
            }
        }
        return out;
    }

    // --- Assignability: Java autoboxing + JLS primitive widening + superclass chain ---

    boolean isAssignable(String actualType, String expectedType) {
        String actual = simpleType(actualType);
        String expected = simpleType(expectedType);
        if (actual.isEmpty() || expected.isEmpty()) {
            return false;
        }
        if (actual.equals(expected) || "Object".equals(expected)) {
            return true;
        }
        // Java autoboxing + JLS primitive widening: Long<->long, int->long, etc. are assignable and
        // must not be reported as argument mismatches (the dominant false positive in data-layer DAOs).
        if (isPrimitiveOrBoxedAssignable(actual, expected)) {
            return true;
        }
        if ("Context".equals(expected) && (actual.endsWith("Activity") || actual.endsWith("Service") || "Application".equals(actual))) {
            return true;
        }
        String parent = superClassByClass.get(actual);
        Set<String> visited = new HashSet<>();
        while (parent != null && visited.add(parent)) {
            if (expected.equals(parent)) {
                return true;
            }
            if ("Context".equals(expected) && (parent.endsWith("Activity") || parent.endsWith("Service") || "Application".equals(parent))) {
                return true;
            }
            parent = superClassByClass.get(parent);
        }
        return false;
    }

    private boolean isPrimitiveOrBoxedAssignable(String actual, String expected) {
        String a = unbox(actual);
        String e = unbox(expected);
        if (a.isEmpty() || e.isEmpty()) {
            return false;
        }
        // Autoboxing/unboxing equivalence (Long<->long), then directional numeric widening (int->long).
        if (a.equals(e)) {
            return true;
        }
        if ("boolean".equals(a) || "boolean".equals(e)) {
            return false;
        }
        return numericRank(a) > 0 && numericRank(a) <= numericRank(e);
    }

    private static String unbox(String type) {
        switch (type) {
            case "Long": case "long": return "long";
            case "Integer": case "int": return "int";
            case "Short": case "short": return "short";
            case "Byte": case "byte": return "byte";
            case "Character": case "char": return "char";
            case "Boolean": case "boolean": return "boolean";
            case "Float": case "float": return "float";
            case "Double": case "double": return "double";
            default: return "";
        }
    }

    // Numeric widening rank per JLS 5.1.2 (byte<short<int<long<float<double; char widens to int+).
    private static int numericRank(String primitive) {
        switch (primitive) {
            case "byte": return 1;
            case "short": return 2;
            case "char": return 2;
            case "int": return 3;
            case "long": return 4;
            case "float": return 5;
            case "double": return 6;
            default: return 0;
        }
    }

    static String simpleType(String type) {
        String value = type == null ? "" : type.trim();
        if (value.isEmpty()) {
            return "";
        }
        value = value.replace("?", "").trim();
        value = value.replaceAll("\\bextends\\s+", "");
        value = value.replaceAll("\\bsuper\\s+", "");
        int generic = value.indexOf('<');
        if (generic >= 0) {
            value = value.substring(0, generic);
        }
        while (value.endsWith("[]")) {
            value = value.substring(0, value.length() - 2);
        }
        int dot = value.lastIndexOf('.');
        if (dot >= 0) {
            value = value.substring(dot + 1);
        }
        return value.trim();
    }
}
