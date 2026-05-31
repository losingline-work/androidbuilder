package com.androidbuilder.agent;

import java.util.ArrayList;
import java.util.List;

final class PathValidator {
    private PathValidator() {
    }

    static String normalizeGeneratedPath(String rawPath) {
        String path = rawPath == null ? "" : rawPath.trim();
        if (path.isEmpty() || path.startsWith("/") || path.contains("\\") || hasControlCharacter(path)) {
            throw new IllegalArgumentException("Unsafe generated file path: " + path);
        }
        String[] segments = path.split("/");
        List<String> normalized = new ArrayList<>();
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment) || ".git".equals(segment)) {
                throw new IllegalArgumentException("Unsafe generated file path: " + path);
            }
            normalized.add(segment);
        }
        return String.join("/", normalized);
    }

    private static boolean hasControlCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }
}
