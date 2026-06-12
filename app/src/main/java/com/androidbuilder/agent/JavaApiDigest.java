package com.androidbuilder.agent;

import com.androidbuilder.util.FileUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class JavaApiDigest {
    private static final String TRUNCATED = "\n...[truncated]";
    private static final Pattern CLASS_DECLARATION = Pattern.compile("\\b(?:public\\s+|protected\\s+|private\\s+|static\\s+|final\\s+|abstract\\s+)*class\\s+([A-Z][A-Za-z0-9_]*)\\b(?:\\s+extends\\s+([A-Za-z_][A-Za-z0-9_$.<>?]*))?");
    private static final Pattern METHOD_DECLARATION = Pattern.compile("\\b(?:(public|protected|private)\\s+)?(?:static\\s+)?(?:final\\s+)?(?:synchronized\\s+)?([A-Za-z_][A-Za-z0-9_$.<>?\\[\\]]*)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(([^)]*)\\)\\s*(?:throws\\s+[A-Za-z0-9_.,\\s]+)?[\\{;]");
    private static final Pattern FIELD_DECLARATION = Pattern.compile("\\b(?:(public|protected|private)\\s+)?(?:(static)\\s+)?(?:(final)\\s+)?([A-Za-z_][A-Za-z0-9_$.<>?\\[\\]]*)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*(?=[=;,])");

    private JavaApiDigest() {
    }

    static String digest(File javaFile) throws Exception {
        return digestSource(javaFile.getName(), FileUtils.readText(javaFile));
    }

    static String digestSource(String path, String content) {
        try {
            String stripped = stripJavaCommentsAndStrings(content == null ? "" : content);
            if (!balancedBraces(stripped)) {
                return fileName(path) + ": unable to parse Java API digest";
            }
            List<ClassInfo> classes = collectClasses(stripped);
            if (classes.isEmpty()) {
                return fileName(path) + ": no Java API declarations found";
            }
            collectMembers(stripped, classes);
            StringBuilder builder = new StringBuilder();
            for (ClassInfo info : classes) {
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                builder.append(info.line());
            }
            return builder.toString();
        } catch (Exception ignored) {
            return fileName(path) + ": unable to parse Java API digest";
        }
    }

    static String digestTree(File sourceDir, Set<String> excludePaths, int maxChars) throws Exception {
        List<File> files = new ArrayList<>();
        collectJavaFiles(sourceDir, files);
        Collections.sort(files, Comparator.comparing(file -> relativePath(sourceDir, file)));
        Set<String> excluded = excludePaths == null ? new HashSet<String>() : excludePaths;
        StringBuilder builder = new StringBuilder();
        for (File file : files) {
            String path = relativePath(sourceDir, file);
            if (excluded.contains(path)) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append("--- ").append(path).append(" API ---\n");
            builder.append(digest(file)).append('\n');
            if (maxChars > 0 && builder.length() >= maxChars) {
                return trimToLimit(builder.toString(), maxChars);
            }
        }
        return maxChars > 0 ? trimToLimit(builder.toString().trim(), maxChars) : builder.toString().trim();
    }

    private static void collectJavaFiles(File file, List<File> out) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) {
                return;
            }
            for (File child : children) {
                collectJavaFiles(child, out);
            }
            return;
        }
        if (file.getName().endsWith(".java")) {
            out.add(file);
        }
    }

    private static List<ClassInfo> collectClasses(String content) {
        List<ClassInfo> classes = new ArrayList<>();
        Matcher matcher = CLASS_DECLARATION.matcher(content);
        while (matcher.find()) {
            int bodyStart = content.indexOf('{', matcher.end());
            int bodyEnd = bodyStart < 0 ? -1 : matchingBrace(content, bodyStart);
            if (bodyStart < 0 || bodyEnd < 0) {
                continue;
            }
            classes.add(new ClassInfo(matcher.group(1), simpleType(matcher.group(2)), matcher.start(), bodyStart, bodyEnd));
        }
        for (ClassInfo info : classes) {
            ClassInfo parent = enclosingClass(classes, info.declarationStart);
            info.qualifiedName = parent == null ? info.name : parent.qualifiedName + "." + info.name;
        }
        return classes;
    }

    private static void collectMembers(String content, List<ClassInfo> classes) {
        for (ClassInfo info : classes) {
            Pattern constructorPattern = Pattern.compile("\\b(?:(public|protected|private)\\s+)?" + Pattern.quote(info.name) + "\\s*\\(([^)]*)\\)\\s*\\{");
            Matcher matcher = constructorPattern.matcher(content);
            while (matcher.find()) {
                if (isPrivate(matcher.group(1)) || !isDirectClassMember(content, info, matcher.start())) {
                    continue;
                }
                addUnique(info.constructors, info.name + "(" + parameterTypes(matcher.group(2)) + ");");
            }
        }

        Matcher methodMatcher = METHOD_DECLARATION.matcher(content);
        while (methodMatcher.find()) {
            ClassInfo owner = enclosingClass(classes, methodMatcher.start());
            if (owner == null || !isDirectClassMember(content, owner, methodMatcher.start()) || isPrivate(methodMatcher.group(1))) {
                continue;
            }
            String methodName = methodMatcher.group(3);
            if (owner.name.equals(methodName) || isKeyword(methodName)) {
                continue;
            }
            addUnique(owner.methods, simpleType(methodMatcher.group(2)) + " " + methodName + "(" + parameterTypes(methodMatcher.group(4)) + ");");
        }

        Matcher fieldMatcher = FIELD_DECLARATION.matcher(content);
        while (fieldMatcher.find()) {
            ClassInfo owner = enclosingClass(classes, fieldMatcher.start());
            if (owner == null || !isDirectClassMember(content, owner, fieldMatcher.start())) {
                continue;
            }
            String access = fieldMatcher.group(1);
            String staticModifier = fieldMatcher.group(2);
            String finalModifier = fieldMatcher.group(3);
            String type = simpleType(fieldMatcher.group(4));
            String name = fieldMatcher.group(5);
            if (isKeyword(name) || (!"static".equals(staticModifier) && isPrivate(access))) {
                continue;
            }
            StringBuilder declaration = new StringBuilder();
            if ("static".equals(staticModifier)) {
                declaration.append("static ");
            }
            if ("final".equals(finalModifier)) {
                declaration.append("final ");
            }
            declaration.append(type).append(' ').append(name).append(';');
            owner.fields.add(declaration.toString());
        }
    }

    private static boolean isDirectClassMember(String content, ClassInfo owner, int position) {
        int depth = 0;
        for (int i = owner.bodyStart; i < position && i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
            }
        }
        return depth == 1;
    }

    private static ClassInfo enclosingClass(List<ClassInfo> classes, int position) {
        ClassInfo owner = null;
        int bestWidth = Integer.MAX_VALUE;
        for (ClassInfo info : classes) {
            if (position <= info.bodyStart || position >= info.bodyEnd) {
                continue;
            }
            int width = info.bodyEnd - info.bodyStart;
            if (width < bestWidth) {
                bestWidth = width;
                owner = info;
            }
        }
        return owner;
    }

    private static int matchingBrace(String content, int openIndex) {
        int depth = 0;
        for (int i = openIndex; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static boolean balancedBraces(String content) {
        int depth = 0;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth < 0) {
                    return false;
                }
            }
        }
        return depth == 0;
    }

    private static String parameterTypes(String parameters) {
        List<String> types = new ArrayList<>();
        for (String parameter : splitArguments(parameters)) {
            String type = typeFromDeclaration(parameter);
            if (!type.isEmpty()) {
                types.add(type);
            }
        }
        return join(types, ", ");
    }

    private static String typeFromDeclaration(String declaration) {
        String value = declaration == null ? "" : declaration.trim();
        if (value.isEmpty()) {
            return "";
        }
        value = value.replaceAll("@[A-Za-z_][A-Za-z0-9_.]*(?:\\([^)]*\\))?\\s*", "");
        value = value.replaceAll("\\bfinal\\s+", "");
        int split = lastWhitespaceOutsideGenerics(value);
        if (split <= 0) {
            return simpleType(value);
        }
        return simpleType(value.substring(0, split).replace("...", "[]"));
    }

    private static int lastWhitespaceOutsideGenerics(String value) {
        int depth = 0;
        int split = -1;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '<') {
                depth++;
            } else if (c == '>' && depth > 0) {
                depth--;
            } else if (Character.isWhitespace(c) && depth == 0) {
                split = i;
            }
        }
        return split;
    }

    private static List<String> splitArguments(String arguments) {
        List<String> values = new ArrayList<>();
        String value = arguments == null ? "" : arguments.trim();
        if (value.isEmpty()) {
            return values;
        }
        int depth = 0;
        int start = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '(' || c == '[' || c == '<') {
                depth++;
            } else if ((c == ')' || c == ']' || c == '>') && depth > 0) {
                depth--;
            } else if (c == ',' && depth == 0) {
                values.add(value.substring(start, i).trim());
                start = i + 1;
            }
        }
        values.add(value.substring(start).trim());
        return values;
    }

    private static String simpleType(String type) {
        String value = type == null ? "" : type.trim();
        if (value.isEmpty()) {
            return "";
        }
        value = value.replace("?", "").trim();
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

    private static boolean isPrivate(String access) {
        return "private".equals(access);
    }

    private static boolean isKeyword(String value) {
        return "if".equals(value) || "for".equals(value) || "while".equals(value) ||
                "switch".equals(value) || "catch".equals(value) || "return".equals(value) ||
                "new".equals(value) || "class".equals(value);
    }

    private static String stripJavaCommentsAndStrings(String content) {
        StringBuilder builder = new StringBuilder(content.length());
        boolean inString = false;
        boolean inChar = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            char next = i + 1 < content.length() ? content.charAt(i + 1) : '\0';
            if (inLineComment) {
                if (c == '\n') {
                    inLineComment = false;
                    builder.append(c);
                } else {
                    builder.append(' ');
                }
            } else if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    builder.append(' ');
                    builder.append(' ');
                    i++;
                } else {
                    builder.append(c == '\n' ? c : ' ');
                }
            } else if (inString) {
                if (c == '\\' && next != '\0') {
                    builder.append(' ');
                    builder.append(' ');
                    i++;
                } else if (c == '"') {
                    inString = false;
                    builder.append(' ');
                } else {
                    builder.append(c == '\n' ? c : ' ');
                }
            } else if (inChar) {
                if (c == '\\' && next != '\0') {
                    builder.append(' ');
                    builder.append(' ');
                    i++;
                } else if (c == '\'') {
                    inChar = false;
                    builder.append(' ');
                } else {
                    builder.append(c == '\n' ? c : ' ');
                }
            } else if (c == '/' && next == '/') {
                inLineComment = true;
                builder.append(' ');
                builder.append(' ');
                i++;
            } else if (c == '/' && next == '*') {
                inBlockComment = true;
                builder.append(' ');
                builder.append(' ');
                i++;
            } else if (c == '"') {
                inString = true;
                builder.append(' ');
            } else if (c == '\'') {
                inChar = true;
                builder.append(' ');
            } else {
                builder.append(c);
            }
        }
        return builder.toString();
    }

    private static String trimToLimit(String text, int limit) {
        if (limit <= 0 || text.length() <= limit) {
            return text;
        }
        if (limit <= TRUNCATED.length()) {
            return text.substring(0, Math.max(0, limit));
        }
        return text.substring(0, limit - TRUNCATED.length()).trim() + TRUNCATED;
    }

    private static String relativePath(File root, File file) {
        return root.toURI().relativize(file.toURI()).getPath();
    }

    private static String fileName(String path) {
        int slash = path == null ? -1 : path.lastIndexOf('/');
        return slash < 0 ? (path == null ? "" : path) : path.substring(slash + 1);
    }

    private static String join(Iterable<String> values, String separator) {
        StringBuilder builder = new StringBuilder();
        int index = 0;
        for (String value : values) {
            if (index > 0) {
                builder.append(separator);
            }
            builder.append(value);
            index++;
        }
        return builder.toString();
    }

    private static void addUnique(List<String> values, String value) {
        if (!values.contains(value)) {
            values.add(value);
        }
    }

    private static class ClassInfo {
        final String name;
        final String superClass;
        final int declarationStart;
        final int bodyStart;
        final int bodyEnd;
        String qualifiedName;
        final List<String> constructors = new ArrayList<>();
        final List<String> methods = new ArrayList<>();
        final TreeSet<String> fields = new TreeSet<>();

        ClassInfo(String name, String superClass, int declarationStart, int bodyStart, int bodyEnd) {
            this.name = name;
            this.superClass = superClass;
            this.declarationStart = declarationStart;
            this.bodyStart = bodyStart;
            this.bodyEnd = bodyEnd;
            this.qualifiedName = name;
        }

        String line() {
            List<String> members = new ArrayList<>();
            members.addAll(constructors);
            members.addAll(methods);
            members.addAll(fields);
            StringBuilder builder = new StringBuilder();
            builder.append("class ").append(qualifiedName);
            if (!superClass.isEmpty()) {
                builder.append(" extends ").append(superClass);
            }
            builder.append(" { ");
            if (!members.isEmpty()) {
                builder.append(join(members, " "));
                builder.append(' ');
            }
            builder.append('}');
            return builder.toString();
        }
    }
}
