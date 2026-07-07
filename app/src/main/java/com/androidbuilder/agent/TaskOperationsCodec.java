package com.androidbuilder.agent;

import com.androidbuilder.model.FileOperation;
import com.androidbuilder.model.TaskOperations;

import java.util.List;

/**
 * Single dispatch facade for turning a model's task-operations reply into {@link TaskOperations}, PLUS a
 * classified outcome tag so the caller can record WHY a reply parsed the way it did (the measurement lever
 * for the weak-model funnel: {@code parse_failed} / {@code json_salvaged} are the truncation-and-garbage
 * signals we could never attribute before).
 *
 * <p>Wave 0 wraps the existing JSON parser only ({@link TaskOperationsParser}); Wave 1 adds the fenced
 * raw-file protocol in front (fenced markers present → fenced parse, else fall through to JSON), which is
 * why callers go through this facade instead of {@code TaskOperationsParser.fromJson} directly. The JSON
 * parser stays as the permanent fallback so strong models that keep returning JSON are unaffected.
 */
public final class TaskOperationsCodec {
    public static final String OUTCOME_FENCED_OK = "fenced_ok";
    public static final String OUTCOME_JSON_OK = "json_ok";
    public static final String OUTCOME_JSON_LENIENT = "json_lenient";
    public static final String OUTCOME_JSON_SALVAGED = "json_salvaged";
    public static final String OUTCOME_PARSE_FAILED = "parse_failed";

    private TaskOperationsCodec() {
    }

    /**
     * Parse without throwing; the outcome tag distinguishes fenced / clean-json / lenient / salvaged / failed.
     * Fenced markers present → fenced parse; if that yields nothing usable, fall through to the JSON parser so
     * a strong model that keeps returning JSON is entirely unaffected (the JSON path stays the permanent
     * fallback).
     */
    public static ParseResult parse(String raw) {
        if (TaskOperationsFencedParser.isFenced(raw)) {
            try {
                return ParseResult.of(TaskOperationsFencedParser.parse(raw), OUTCOME_FENCED_OK);
            } catch (RuntimeException fencedError) {
                // Fenced markers but no closed block (e.g. truncated before the first ===END===): fall back to
                // JSON in case the reply is actually JSON that merely mentioned a marker-looking line.
            }
        }
        return TaskOperationsParser.fromJsonClassified(raw);
    }

    /** Best-effort salvage of the fully-formed operations seen before a truncation point (partial stream). */
    public static List<FileOperation> completedOperations(String partialRaw) {
        if (TaskOperationsFencedParser.isFenced(partialRaw)) {
            List<FileOperation> fenced = TaskOperationsFencedParser.completedOperations(partialRaw);
            if (!fenced.isEmpty()) {
                return fenced;
            }
        }
        return TaskOperationsParser.completedOperations(partialRaw);
    }

    /**
     * A parse attempt's result: {@link #operations} is non-null on success (with {@link #outcome} one of the
     * json_* tags), or null on failure (outcome {@code parse_failed}, {@link #error} carrying the same
     * {@link IllegalArgumentException} the throwing parser would have raised so existing catch blocks and the
     * per-attempt retry flow behave identically).
     */
    public static final class ParseResult {
        public final TaskOperations operations;
        public final String outcome;
        public final IllegalArgumentException error;

        private ParseResult(TaskOperations operations, String outcome, IllegalArgumentException error) {
            this.operations = operations;
            this.outcome = outcome;
            this.error = error;
        }

        static ParseResult of(TaskOperations operations, String outcome) {
            return new ParseResult(operations, outcome, null);
        }

        static ParseResult failed(IllegalArgumentException error) {
            return new ParseResult(null, OUTCOME_PARSE_FAILED, error);
        }

        /** The operations, or rethrow the captured parse failure — mirrors {@code TaskOperationsParser.fromJson}. */
        public TaskOperations operationsOrThrow() {
            if (operations == null) {
                throw error != null ? error
                        : new IllegalArgumentException("Task operation response could not be parsed.");
            }
            return operations;
        }
    }
}
