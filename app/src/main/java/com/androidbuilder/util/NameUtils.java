package com.androidbuilder.util;

import java.util.Locale;

public final class NameUtils {
    private NameUtils() {
    }

    public static String projectNameFromPrompt(String prompt) {
        String value = firstWords(prompt, 5).trim();
        if (value.isEmpty()) {
            return "New App";
        }
        return value.length() > 28 ? value.substring(0, 28).trim() : value;
    }

    public static String packageNameFromProject(String projectName) {
        String slug = asciiSlug(projectName);
        if (slug.isEmpty()) {
            slug = "app" + Math.abs((projectName == null ? "app" : projectName).hashCode());
        }
        return "com.generated." + slug;
    }

    public static boolean isPackageName(String value) {
        return value != null && value.matches("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+");
    }

    public static String className(String value, String fallback) {
        String slug = asciiSlug(value);
        if (slug.isEmpty()) {
            slug = fallback.toLowerCase(Locale.US);
        }
        StringBuilder out = new StringBuilder();
        boolean upper = true;
        for (char c : slug.toCharArray()) {
            if (c == '_') {
                upper = true;
            } else if (upper) {
                out.append(Character.toUpperCase(c));
                upper = false;
            } else {
                out.append(c);
            }
        }
        if (out.length() == 0 || !Character.isJavaIdentifierStart(out.charAt(0))) {
            out.insert(0, fallback);
        }
        return out.toString();
    }

    public static String asciiSlug(String value) {
        if (value == null) {
            return "";
        }
        String lower = value.toLowerCase(Locale.US);
        StringBuilder out = new StringBuilder();
        boolean lastUnderscore = false;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (c >= 'a' && c <= 'z' || c >= '0' && c <= '9') {
                out.append(c);
                lastUnderscore = false;
            } else if (!lastUnderscore && out.length() > 0) {
                out.append('_');
                lastUnderscore = true;
            }
        }
        while (out.length() > 0 && out.charAt(out.length() - 1) == '_') {
            out.deleteCharAt(out.length() - 1);
        }
        if (out.length() > 0 && out.charAt(0) >= '0' && out.charAt(0) <= '9') {
            out.insert(0, 'a');
        }
        return out.toString();
    }

    private static String firstWords(String value, int count) {
        if (value == null) {
            return "";
        }
        String[] parts = value.replace('\n', ' ').trim().split("\\s+");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(part);
            if (--count == 0) {
                break;
            }
        }
        return out.toString();
    }
}
