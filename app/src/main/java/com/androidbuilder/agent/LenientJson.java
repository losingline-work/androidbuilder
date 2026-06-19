package com.androidbuilder.agent;

import org.json.JSONObject;

/**
 * Tolerant extraction + parsing of a JSON object from a model response: pull the outermost {@code {...}} out
 * of any surrounding prose, and retry parsing once with bare double-quotes inside string values escaped (a
 * common model mistake). Shared by the task and milestone parsers so both behave identically.
 */
public final class LenientJson {
    private LenientJson() {
    }

    /** The first {@code {...}} span of {@code raw}; throws with {@code label} if none is present. */
    public static String extractObject(String raw, String label) {
        String text = raw == null ? "" : raw.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException(label + " did not contain a JSON object.");
        }
        return text.substring(start, end + 1);
    }

    /** Parse {@code jsonText}; on failure retry once with bare in-string quotes escaped. */
    public static JSONObject parse(String jsonText) throws Exception {
        try {
            return new JSONObject(jsonText);
        } catch (Exception firstError) {
            try {
                return new JSONObject(escapeBareQuotesInsideStrings(jsonText));
            } catch (Exception ignored) {
                throw firstError;
            }
        }
    }

    static String escapeBareQuotesInsideStrings(String jsonText) {
        StringBuilder repaired = new StringBuilder(jsonText.length() + 16);
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < jsonText.length(); i++) {
            char c = jsonText.charAt(i);
            if (!inString) {
                repaired.append(c);
                if (c == '"') {
                    inString = true;
                    escaped = false;
                }
                continue;
            }
            if (escaped) {
                repaired.append(c);
                escaped = false;
                continue;
            }
            if (c == '\\') {
                repaired.append(c);
                escaped = true;
                continue;
            }
            if (c == '"') {
                if (isStringTerminator(jsonText, i + 1)) {
                    repaired.append(c);
                    inString = false;
                } else {
                    repaired.append('\\').append(c);
                }
                continue;
            }
            repaired.append(c);
        }
        return repaired.toString();
    }

    private static boolean isStringTerminator(String text, int index) {
        for (int i = index; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            return c == ':' || c == ',' || c == '}' || c == ']';
        }
        return true;
    }
}
