package com.androidbuilder.agent;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class StreamProgressRegistry {
    private final Map<String, StreamProgress> progressByTag = new HashMap<>();

    public synchronized void updatePhase(String callTag, String phase, int attempt, int maxAttempts) {
        String tag = clean(callTag);
        if (tag.isEmpty()) {
            return;
        }
        StreamProgress current = progressByTag.get(tag);
        long now = System.currentTimeMillis();
        String cleanPhase = clean(phase);
        boolean samePhase = current != null
                && current.phase.equals(cleanPhase)
                && current.attempt == Math.max(0, attempt)
                && current.maxAttempts == Math.max(0, maxAttempts);
        progressByTag.put(tag, new StreamProgress(
                tag,
                cleanPhase,
                Math.max(0, attempt),
                Math.max(0, maxAttempts),
                samePhase ? current.answerChars : 0,
                samePhase ? current.reasoningChars : 0,
                samePhase ? current.startedAt : now,
                now));
    }

    public synchronized void updateCounts(String callTag, int answerChars, int reasoningChars) {
        String tag = clean(callTag);
        if (tag.isEmpty()) {
            return;
        }
        StreamProgress current = progressByTag.get(tag);
        long now = System.currentTimeMillis();
        progressByTag.put(tag, new StreamProgress(
                tag,
                current == null ? "" : current.phase,
                current == null ? 0 : current.attempt,
                current == null ? 0 : current.maxAttempts,
                Math.max(0, answerChars),
                Math.max(0, reasoningChars),
                current == null ? now : current.startedAt,
                now));
    }

    public synchronized void clear(String callTag) {
        String tag = clean(callTag);
        if (!tag.isEmpty()) {
            progressByTag.remove(tag);
        }
    }

    public synchronized Map<String, StreamProgress> snapshot() {
        return Collections.unmodifiableMap(new HashMap<>(progressByTag));
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    public static final class StreamProgress {
        public final String callTag;
        public final String phase;
        public final int attempt;
        public final int maxAttempts;
        public final int answerChars;
        public final int reasoningChars;
        public final long startedAt;
        public final long updatedAt;

        public StreamProgress(String callTag, String phase, int attempt, int maxAttempts,
                              int answerChars, int reasoningChars, long startedAt, long updatedAt) {
            this.callTag = clean(callTag);
            this.phase = clean(phase);
            this.attempt = Math.max(0, attempt);
            this.maxAttempts = Math.max(0, maxAttempts);
            this.answerChars = Math.max(0, answerChars);
            this.reasoningChars = Math.max(0, reasoningChars);
            this.startedAt = Math.max(0, startedAt);
            this.updatedAt = Math.max(0, updatedAt);
        }
    }
}
