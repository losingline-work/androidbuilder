package com.androidbuilder.agent;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class SourceSnapshotComposer {
    static final String JAVA_API_DIGEST_HEADER = "--- Java API digest (non-focused source files) ---";
    static final String RESOURCE_INDEX_HEADER = "--- resource index (complete, authoritative) ---";
    static final String RESOURCE_INDEX_RULE = "Every R.id/R.layout/R.string/R.color/R.drawable/R.mipmap/R.style in Java MUST appear verbatim in this index. If a needed id is missing here, it does not exist - return blocked instead of inventing it.";
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
        // The resource index may legitimately exceed its own layer budget (id/layout/string are
        // never cut), so the trailing layers are sized first and the full-text layer absorbs the
        // overflow. A final tail trim would otherwise eat the resource index.
        StringBuilder tail = new StringBuilder();
        appendNamedLayer(tail, JAVA_API_DIGEST_HEADER, javaApiDigest);
        appendResourceIndex(tail, resourceIndex);
        appendContextNote(tail, contextNote);
        int fullTextBudget = fullTextLimit;
        if (totalLimit > 0) {
            int remaining = totalLimit - tail.length();
            fullTextBudget = fullTextLimit < 0 ? Math.max(0, remaining)
                    : Math.max(0, Math.min(fullTextLimit, remaining));
        }
        StringBuilder snapshot = new StringBuilder();
        appendFullTextSections(snapshot, fullTextSections, fullTextBudget);
        snapshot.append(tail);
        String value = snapshot.toString().trim();
        if (value.isEmpty()) {
            return "(empty)";
        }
        return trimToLimit(value, totalLimit);
    }

    private static void appendFullTextSections(StringBuilder snapshot, List<TextSection> sections, int limit) {
        if (sections == null || sections.isEmpty() || limit == 0) {
            return;
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
            if (text.length() > remaining) {
                snapshot.append(trimToLimit(text, remaining));
                break;
            }
            snapshot.append(text);
            if (snapshot.length() == 0 || snapshot.charAt(snapshot.length() - 1) != '\n') {
                snapshot.append('\n');
            }
        }
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

    private static void appendContextNote(StringBuilder snapshot, String contextNote) {
        if (contextNote == null || contextNote.trim().isEmpty()) {
            return;
        }
        snapshot.append("\n--- context note ---\n");
        snapshot.append(contextNote.trim()).append('\n');
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
}
