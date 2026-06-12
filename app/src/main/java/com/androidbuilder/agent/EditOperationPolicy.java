package com.androidbuilder.agent;

final class EditOperationPolicy {
    private EditOperationPolicy() {
    }

    static String apply(String existingContent, String find, String replace, String path) {
        String targetPath = path == null ? "" : path;
        String needle = find == null ? "" : find;
        if (needle.isEmpty()) {
            throw new IllegalArgumentException("edit operation has empty find text in " + targetPath + "; resend the full file with action write");
        }
        String existing = existingContent == null ? "" : existingContent;
        int first = existing.indexOf(needle);
        if (first < 0) {
            throw new IllegalArgumentException("edit target not found in " + targetPath + " (the file may have changed); resend the full file with action write");
        }
        int second = existing.indexOf(needle, first + needle.length());
        if (second >= 0) {
            throw new IllegalArgumentException("edit target is ambiguous in " + targetPath + " (" + countMatches(existing, needle) + " matches); include more surrounding context in find, or resend the full file");
        }
        return existing.substring(0, first) + (replace == null ? "" : replace) + existing.substring(first + needle.length());
    }

    private static int countMatches(String existing, String needle) {
        int count = 0;
        int index = 0;
        while (index <= existing.length()) {
            int found = existing.indexOf(needle, index);
            if (found < 0) {
                return count;
            }
            count++;
            index = found + needle.length();
        }
        return count;
    }
}
