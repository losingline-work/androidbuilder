package com.androidbuilder.agent;

import com.androidbuilder.model.FileOperation;
import com.androidbuilder.model.TaskOperations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Parses the FENCED raw-file protocol — the weak-model-friendly alternative to packing every file body inside
 * a JSON string literal. In the fenced format a file's content rides between line markers with ZERO escaping,
 * so a stray quote or backslash can no longer destroy the whole reply:
 *
 * <pre>
 * ===SUMMARY===
 * one line
 * ===FILE app/src/main/res/values/strings.xml===
 * &lt;raw file content, no escaping&gt;
 * ===END===
 * ===EDIT app/build.gradle===
 * ===FIND===
 * &lt;whole lines copied from the current file, unique&gt;
 * ===REPLACE===
 * &lt;replacement lines&gt;
 * ===END===
 * ===DELETE app/src/main/res/layout/old.xml===
 * ===DROP app/src/main/java/com/x/Stale.java===
 * ===BLOCKED===
 * reason
 * ===PREREQ===
 * prerequisite work
 * ===END===
 * </pre>
 *
 * <p>Robustness by construction: only a line whose trimmed form is {@code ===KEYWORD ...===} for a KNOWN
 * keyword is a marker, so an incidental {@code ===...===} inside file content is treated as content. A block
 * that never closes (truncation) is simply dropped — which gives free truncation salvage — and the missing
 * file is carried forward. Additive and self-contained; {@link TaskOperationsCodec} chooses this parser only
 * when {@link #isFenced} sees fenced markers, else falls back to the JSON parser.
 */
final class TaskOperationsFencedParser {
    private static final String KW_SUMMARY = "SUMMARY";
    private static final String KW_FILE = "FILE";
    private static final String KW_EDIT = "EDIT";
    private static final String KW_DELETE = "DELETE";
    private static final String KW_DROP = "DROP";
    private static final String KW_FIND = "FIND";
    private static final String KW_REPLACE = "REPLACE";
    private static final String KW_END = "END";
    private static final String KW_BLOCKED = "BLOCKED";
    private static final String KW_PREREQ = "PREREQ";

    private TaskOperationsFencedParser() {
    }

    /** True when the reply uses the fenced protocol (any FILE/EDIT/DELETE/DROP/SUMMARY/BLOCKED marker). */
    static boolean isFenced(String raw) {
        for (String line : splitLines(stripOuterFence(raw))) {
            String keyword = markerKeyword(line);
            if (KW_FILE.equals(keyword) || KW_EDIT.equals(keyword) || KW_DELETE.equals(keyword)
                    || KW_DROP.equals(keyword) || KW_SUMMARY.equals(keyword) || KW_BLOCKED.equals(keyword)) {
                return true;
            }
        }
        return false;
    }

    /** Parse a complete fenced reply; throws {@link IllegalArgumentException} when no usable block was found. */
    static TaskOperations parse(String raw) {
        Parsed parsed = collect(raw);
        if (parsed.blocked) {
            return new TaskOperations(parsed.summary, Collections.<FileOperation>emptyList(), true,
                    parsed.blockedReason, parsed.prerequisiteWork);
        }
        if (parsed.operations.isEmpty()) {
            throw new IllegalArgumentException("Fenced task-operations response contained no closed file blocks.");
        }
        return new TaskOperations(parsed.summary, parsed.operations);
    }

    /**
     * Salvage the operations from every CLOSED block seen so far (a partial stream): an unterminated trailing
     * FILE/EDIT block is dropped. Stronger than the JSON salvage — closed edit and delete blocks survive too.
     */
    static List<FileOperation> completedOperations(String partialRaw) {
        return collect(partialRaw).operations;
    }

    private static Parsed collect(String raw) {
        Parsed result = new Parsed();
        List<String> lines = splitLines(stripOuterFence(raw));
        int i = 0;
        while (i < lines.size()) {
            String keyword = markerKeyword(lines.get(i));
            if (keyword == null) {
                i++; // stray line outside any block
                continue;
            }
            if (KW_SUMMARY.equals(keyword)) {
                StringBuilder text = new StringBuilder();
                i++;
                while (i < lines.size() && markerKeyword(lines.get(i)) == null) {
                    text.append(lines.get(i)).append('\n');
                    i++;
                }
                result.summary = text.toString().trim();
                continue;
            }
            if (KW_FILE.equals(keyword)) {
                String path = norm(markerPath(lines.get(i)));
                Block block = readUntilEnd(lines, i + 1);
                if (block.closed && path != null) {
                    result.operations.add(new FileOperation("write", path, block.body));
                }
                i = block.next;
                continue;
            }
            if (KW_DELETE.equals(keyword) || KW_DROP.equals(keyword)) {
                String path = norm(markerPath(lines.get(i)));
                if (path != null) {
                    result.operations.add(new FileOperation(keyword.toLowerCase(java.util.Locale.ROOT), path, ""));
                }
                i++;
                continue;
            }
            if (KW_EDIT.equals(keyword)) {
                String path = markerPath(lines.get(i));
                i = readEdit(lines, i + 1, path, result);
                continue;
            }
            if (KW_BLOCKED.equals(keyword)) {
                i = readBlocked(lines, i + 1, result);
                continue;
            }
            // FIND / REPLACE / END / PREREQ seen outside their block: ignore and advance.
            i++;
        }
        return result;
    }

    /** EDIT body is {@code ===FIND=== .. ===REPLACE=== .. ===END===}; a malformed one is skipped. */
    private static int readEdit(List<String> lines, int start, String path, Parsed result) {
        int i = start;
        if (i >= lines.size() || !KW_FIND.equals(markerKeyword(lines.get(i)))) {
            return i;
        }
        Block find = readUntil(lines, i + 1, KW_REPLACE);
        if (!find.closed) {
            return find.next;
        }
        Block replace = readUntil(lines, find.next, KW_END);
        String safe = norm(path);
        if (replace.closed && safe != null) {
            result.operations.add(new FileOperation("edit", safe, "", find.body, replace.body));
        }
        return replace.next;
    }

    private static int readBlocked(List<String> lines, int start, Parsed result) {
        Block reason = readUntilAny(lines, start, KW_PREREQ, KW_END);
        result.blocked = true;
        result.blockedReason = reason.body.trim();
        if (KW_PREREQ.equals(reason.terminator)) {
            Block prereq = readUntil(lines, reason.next, KW_END);
            result.prerequisiteWork = prereq.body.trim();
            return prereq.next;
        }
        return reason.next;
    }

    private static Block readUntilEnd(List<String> lines, int start) {
        return readUntil(lines, start, KW_END);
    }

    private static Block readUntil(List<String> lines, int start, String terminator) {
        return readUntilAny(lines, start, terminator, null);
    }

    /** Accumulate raw lines until a marker equal to {@code a} (or {@code b}); {@link Block#closed} tracks it. */
    private static Block readUntilAny(List<String> lines, int start, String a, String b) {
        StringBuilder body = new StringBuilder();
        int i = start;
        while (i < lines.size()) {
            String keyword = markerKeyword(lines.get(i));
            if (a.equals(keyword) || (b != null && b.equals(keyword))) {
                Block block = new Block();
                block.body = body.toString();
                block.closed = true;
                block.terminator = keyword;
                block.next = i + 1;
                return block;
            }
            body.append(lines.get(i)).append('\n');
            i++;
        }
        Block block = new Block();
        block.body = body.toString();
        block.closed = false;
        block.terminator = null;
        block.next = i;
        return block;
    }

    /** The known keyword of a marker line ({@code ===FILE app/x===} → FILE), or null when not a known marker. */
    private static String markerKeyword(String line) {
        String trimmed = line.trim();
        if (trimmed.length() < 6 || !trimmed.startsWith("===") || !trimmed.endsWith("===")) {
            return null;
        }
        String inner = trimmed.substring(3, trimmed.length() - 3).trim();
        if (inner.isEmpty()) {
            return null;
        }
        int space = inner.indexOf(' ');
        String keyword = space < 0 ? inner : inner.substring(0, space);
        switch (keyword) {
            case KW_SUMMARY:
            case KW_FILE:
            case KW_EDIT:
            case KW_DELETE:
            case KW_DROP:
            case KW_FIND:
            case KW_REPLACE:
            case KW_END:
            case KW_BLOCKED:
            case KW_PREREQ:
                return keyword;
            default:
                return null;
        }
    }

    /** The path portion of a {@code ===FILE path===} / {@code ===DELETE path===} marker, else "". */
    private static String markerPath(String line) {
        String trimmed = line.trim();
        String inner = trimmed.substring(3, trimmed.length() - 3).trim();
        int space = inner.indexOf(' ');
        return space < 0 ? "" : inner.substring(space + 1).trim();
    }

    /** Normalized safe path, or null when the path is empty/unsafe (that block is skipped, not fatal). */
    private static String norm(String path) {
        if (path == null || path.trim().isEmpty()) {
            return null;
        }
        try {
            return PathValidator.normalizeGeneratedPath(path);
        } catch (RuntimeException unsafe) {
            // One malformed path (e.g. absolute or ../ escape) skips just that block, unlike the JSON parser
            // which fails the whole reply; the missing file is carried forward.
            return null;
        }
    }

    /** Strip a single outermost markdown code fence pair (```...```), which some models wrap the reply in. */
    private static String stripOuterFence(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (!text.startsWith("```")) {
            return text;
        }
        int firstNewline = text.indexOf('\n');
        if (firstNewline < 0) {
            return text;
        }
        String withoutOpen = text.substring(firstNewline + 1);
        int lastFence = withoutOpen.lastIndexOf("```");
        return lastFence < 0 ? withoutOpen : withoutOpen.substring(0, lastFence);
    }

    private static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        // Normalize CRLF/CR so markers on Windows-style streams still match.
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        int start = 0;
        for (int i = 0; i < normalized.length(); i++) {
            if (normalized.charAt(i) == '\n') {
                lines.add(normalized.substring(start, i));
                start = i + 1;
            }
        }
        lines.add(normalized.substring(start));
        return lines;
    }

    private static final class Block {
        String body = "";
        boolean closed;
        String terminator;
        int next;
    }

    private static final class Parsed {
        String summary = "";
        final List<FileOperation> operations = new ArrayList<>();
        boolean blocked;
        String blockedReason = "";
        String prerequisiteWork = "";
    }
}
