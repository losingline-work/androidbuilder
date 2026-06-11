package com.androidbuilder.agent;

import com.androidbuilder.model.HermesReview;

import org.json.JSONObject;

import java.util.Locale;

public final class HermesReviewParser {
    private static final int MAX_SUMMARY_CHARS = 600;
    private static final int MAX_REWRITE_INSTRUCTION_CHARS = 2000;

    private HermesReviewParser() {
    }

    public static HermesReview fromJson(String raw) throws Exception {
        JSONObject json = new JSONObject(extractJson(raw));
        String decision = json.optString("decision", "").trim().toLowerCase(Locale.ROOT);
        String summary = cap(json.optString("summary", "").trim(), MAX_SUMMARY_CHARS);
        String rewriteInstruction = cap(json.optString("rewriteInstruction", "").trim(), MAX_REWRITE_INSTRUCTION_CHARS);
        if ("ok".equals(decision)) {
            return new HermesReview(HermesReview.Decision.OK, summary, rewriteInstruction);
        }
        if ("rewrite".equals(decision)) {
            return new HermesReview(HermesReview.Decision.REWRITE, summary, rewriteInstruction);
        }
        if ("fallback".equals(decision)) {
            return new HermesReview(HermesReview.Decision.FALLBACK, summary, rewriteInstruction);
        }
        throw new IllegalArgumentException("Hermes reviewer decision is invalid: " + decision);
    }

    private static String extractJson(String raw) {
        String text = raw == null ? "" : raw.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("Hermes reviewer response did not contain a JSON object.");
        }
        return text.substring(start, end + 1);
    }

    private static String cap(String value, int limit) {
        if (value.length() <= limit) {
            return value;
        }
        return value.substring(0, limit);
    }
}
