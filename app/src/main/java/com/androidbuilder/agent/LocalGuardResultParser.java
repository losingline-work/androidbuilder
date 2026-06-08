package com.androidbuilder.agent;

import org.json.JSONObject;

import java.util.Locale;

public final class LocalGuardResultParser {
    private LocalGuardResultParser() {
    }

    public static LocalGuardResult parse(String rawOutput) {
        try {
            String json = extractJsonObject(rawOutput);
            JSONObject object = new JSONObject(json);
            String decisionValue = object.optString("decision", "ok").trim().toLowerCase(Locale.ROOT);
            String summary = object.optString("summary", "").trim();
            String additionalInstruction = object.optString("additionalInstruction", "").trim();
            if ("rewrite".equals(decisionValue)) {
                return LocalGuardResult.rewrite(summary, additionalInstruction);
            }
            if ("ok".equals(decisionValue)) {
                return LocalGuardResult.ok(summary);
            }
            return LocalGuardResult.unusable("Local guard output was unparseable: unsupported decision.");
        } catch (Exception error) {
            return LocalGuardResult.unusable("Local guard output was unparseable.");
        }
    }

    private static String extractJsonObject(String rawOutput) {
        String output = rawOutput == null ? "" : rawOutput.trim();
        int start = output.indexOf('{');
        int end = output.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("Missing JSON object.");
        }
        return output.substring(start, end + 1);
    }
}
