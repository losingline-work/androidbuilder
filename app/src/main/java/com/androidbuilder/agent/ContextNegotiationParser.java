package com.androidbuilder.agent;

import com.androidbuilder.model.ContextNegotiation;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class ContextNegotiationParser {
    private static final int MAX_ITEMS = 12;
    private static final int MAX_TERM_CHARS = 80;
    private static final int MAX_RISK_NOTE_CHARS = 240;
    private static final int MAX_PATCH_INTENT_CHARS = 2000;

    static final int MAX_ITEMS_FOR_TEST = MAX_ITEMS;
    static final int MAX_RISK_NOTE_CHARS_FOR_TEST = MAX_RISK_NOTE_CHARS;
    static final int MAX_PATCH_INTENT_CHARS_FOR_TEST = MAX_PATCH_INTENT_CHARS;

    private ContextNegotiationParser() {
    }

    public static ContextNegotiation fromJson(String raw) throws Exception {
        JSONObject json = new JSONObject(extractJson(raw));
        String patchIntent = cap(json.optString("patchIntent", "").trim(), MAX_PATCH_INTENT_CHARS);
        if (patchIntent.isEmpty()) {
            throw new IllegalArgumentException("Context negotiation patchIntent is empty.");
        }
        return new ContextNegotiation(
                json.optBoolean("ready", false),
                sourcePaths(json.optJSONArray("neededFiles")),
                shortStrings(json.optJSONArray("focusTerms"), MAX_TERM_CHARS),
                shortStrings(json.optJSONArray("riskNotes"), MAX_RISK_NOTE_CHARS),
                patchIntent);
    }

    private static List<String> sourcePaths(JSONArray array) {
        List<String> values = new ArrayList<>();
        if (array == null) {
            return values;
        }
        for (int i = 0; i < array.length() && values.size() < MAX_ITEMS; i++) {
            String raw = array.optString(i, "").trim();
            if (raw.isEmpty()) {
                continue;
            }
            try {
                String path = PathValidator.normalizeGeneratedPath(raw);
                if (AgentService.isTextSourceFile(path)) {
                    values.add(path);
                }
            } catch (IllegalArgumentException ignored) {
                // Unsafe model-requested paths are ignored, not fatal.
            }
        }
        return values;
    }

    private static List<String> shortStrings(JSONArray array, int maxChars) {
        List<String> values = new ArrayList<>();
        if (array == null) {
            return values;
        }
        for (int i = 0; i < array.length() && values.size() < MAX_ITEMS; i++) {
            String value = cap(array.optString(i, "").trim(), maxChars);
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return values;
    }

    private static String extractJson(String raw) {
        String text = raw == null ? "" : raw.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("Context negotiation response did not contain a JSON object.");
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
