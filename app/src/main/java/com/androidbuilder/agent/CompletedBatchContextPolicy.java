package com.androidbuilder.agent;

import com.androidbuilder.model.FileOperation;

import java.util.List;

final class CompletedBatchContextPolicy {
    static final int DEFAULT_MAX_CHARS = 20_000;

    private CompletedBatchContextPolicy() {
    }

    static String context(List<FileOperation> acceptedOperations, int maxChars) {
        if (acceptedOperations == null || acceptedOperations.isEmpty()) {
            return "";
        }
        int limit = maxChars <= 0 ? DEFAULT_MAX_CHARS : maxChars;
        StringBuilder builder = new StringBuilder();
        for (FileOperation operation : acceptedOperations) {
            if (operation == null || !"write".equals(operation.action)) {
                continue;
            }
            String section = fullSection(operation);
            if (builder.length() + section.length() > limit) {
                section = summarySection(operation);
            }
            if (builder.length() + section.length() > limit) {
                continue;
            }
            builder.append(section);
        }
        return builder.toString().trim();
    }

    private static String fullSection(FileOperation operation) {
        return "--- " + operation.path + " ---\n" + (operation.content == null ? "" : operation.content) + "\n";
    }

    private static String summarySection(FileOperation operation) {
        String path = operation.path == null ? "" : operation.path;
        if (path.endsWith(".java")) {
            return "--- " + path + " API ---\n" + JavaApiDigest.digestSource(path, operation.content) + "\n";
        }
        if (path.endsWith(".xml")) {
            ResourceSymbolsOverlay overlay = ResourceSymbolsOverlay.empty();
            overlay.absorb(java.util.Collections.singletonList(operation));
            return "--- " + path + " resources ---\n"
                    + "ids=" + overlay.ids
                    + "; layouts=" + overlay.layouts
                    + "; strings=" + overlay.strings
                    + "; colors=" + overlay.colors
                    + "; drawables=" + overlay.drawables
                    + "; mipmaps=" + overlay.mipmaps
                    + "; styles=" + overlay.styles + "\n";
        }
        return "--- " + path + " ---\n[accepted earlier]\n";
    }
}
