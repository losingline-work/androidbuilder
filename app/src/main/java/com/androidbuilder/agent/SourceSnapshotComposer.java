package com.androidbuilder.agent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class SourceSnapshotComposer {
    static final String JAVA_API_DIGEST_HEADER = "--- Java API digest (non-focused source files) ---";
    static final String RESOURCE_INDEX_HEADER = "--- resource index (complete, authoritative) ---";
    static final String RESOURCE_INDEX_RULE = "Every R.id/R.layout/R.string/R.color/R.drawable/R.mipmap/R.style in Java MUST appear verbatim in this index. Never invent a new resource name: if the exact name is missing, bind to the nearest existing indexed name, or return blocked with prerequisiteWork naming it - do not reference an R.* name that is not in this index. Conversely, every name listed here EXISTS - you may reference it from Java without seeing the XML body.";
    private static final String TRUNCATED = "\n...[truncated]";

    private SourceSnapshotComposer() {
    }

    static TextSection textSection(String path, String text) {
        return new TextSection(path, text);
    }

    static String assemble(
            List<TextSection> fullTextSections,
            String javaApiDigest,
            String resourceIndex,
            String contextNote,
            int fullTextLimit,
            int totalLimit) {
        int compositionBudget = totalLimit;
        if (totalLimit > 0 && contextNote != null && !contextNote.trim().isEmpty()) {
            compositionBudget = Math.max(0, totalLimit - "\n--- context note ---\n".length() - contextNote.trim().length());
        }
        Composition composition = compose(fullTextSections, javaApiDigest, resourceIndex, fullTextLimit, compositionBudget);
        return appendContextNote(composition.text, contextNote, totalLimit);
    }

    static Composition compose(
            List<TextSection> fullTextSections,
            String javaApiDigest,
            String resourceIndex,
            int fullTextLimit,
            int budget) {
        String tail = tailLayers(javaApiDigest, resourceIndex);
        int fullTextBudget = fullTextLimit;
        if (budget > 0) {
            int remaining = budget - tail.length();
            fullTextBudget = fullTextLimit < 0 ? Math.max(0, remaining)
                    : Math.max(0, Math.min(fullTextLimit, remaining));
        }
        StringBuilder snapshot = new StringBuilder();
        List<String> fullyIncludedPaths = new ArrayList<>();
        String partialPath = appendFullTextSections(snapshot, fullTextSections, fullTextBudget, fullyIncludedPaths);
        snapshot.append(tail);
        String value = snapshot.toString().trim();
        if (value.isEmpty()) {
            value = "(empty)";
        }
        return new Composition(value, fullyIncludedPaths, partialPath);
    }

    static String appendContextNote(String composedText, String contextNote, int totalLimit) {
        String base = composedText == null ? "" : composedText.trim();
        if (contextNote == null || contextNote.trim().isEmpty()) {
            return base.isEmpty() ? "(empty)" : base;
        }
        String header = "\n--- context note ---\n";
        String note = contextNote.trim();
        if (totalLimit > 0) {
            int remaining = totalLimit - base.length() - header.length();
            if (remaining <= 0) {
                return base.isEmpty() ? "(empty)" : base;
            }
            note = trimToLimit(note, remaining);
        }
        String result = base + header + note;
        return result.trim().isEmpty() ? "(empty)" : result.trim();
    }

    private static String tailLayers(String javaApiDigest, String resourceIndex) {
        StringBuilder tail = new StringBuilder();
        appendNamedLayer(tail, JAVA_API_DIGEST_HEADER, javaApiDigest);
        appendResourceIndex(tail, resourceIndex);
        return tail.toString();
    }

    private static String appendFullTextSections(StringBuilder snapshot, List<TextSection> sections, int limit, List<String> fullyIncludedPaths) {
        if (sections == null || sections.isEmpty() || limit == 0) {
            return null;
        }
        int max = limit < 0 ? Integer.MAX_VALUE : limit;
        Set<String> seen = new HashSet<>();
        for (TextSection section : sections) {
            if (section == null || section.path == null || !seen.add(section.path)) {
                continue;
            }
            String header = "\n--- " + section.path + " ---\n";
            String text = section.text == null ? "" : section.text;
            int remaining = max - snapshot.length() - header.length();
            if (remaining <= 0) {
                break;
            }
            snapshot.append(header);
            String normalized = endsWithNewline(text) ? text : text + "\n";
            if (normalized.length() > remaining) {
                snapshot.append(trimToLimit(normalized, remaining));
                return section.path;
            }
            snapshot.append(normalized);
            fullyIncludedPaths.add(section.path);
        }
        return null;
    }

    private static boolean endsWithNewline(String text) {
        return text.endsWith("\n") || text.endsWith("\r");
    }

    private static void appendNamedLayer(StringBuilder snapshot, String header, String text) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        snapshot.append("\n").append(header).append("\n");
        snapshot.append(text.trim()).append('\n');
    }

    private static void appendResourceIndex(StringBuilder snapshot, String resourceIndex) {
        if (resourceIndex == null || resourceIndex.trim().isEmpty()) {
            return;
        }
        snapshot.append("\n").append(RESOURCE_INDEX_HEADER).append("\n");
        snapshot.append(RESOURCE_INDEX_RULE).append('\n');
        snapshot.append(resourceIndex.trim()).append('\n');
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

    static final class TextSection {
        final String path;
        final String text;

        TextSection(String path, String text) {
            this.path = path;
            this.text = text;
        }
    }

    static final class Composition {
        final String text;
        final List<String> fullyIncludedPaths;
        final String partiallyIncludedPath;

        Composition(String text, List<String> fullyIncludedPaths, String partiallyIncludedPath) {
            this.text = text;
            this.fullyIncludedPaths = fullyIncludedPaths;
            this.partiallyIncludedPath = partiallyIncludedPath;
        }
    }
}
