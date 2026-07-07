package com.androidbuilder.agent;

import com.androidbuilder.model.FileOperation;
import com.androidbuilder.model.TaskOperations;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TaskOperationsParser {
    private static final Pattern SUMMARY_PATTERN = Pattern.compile("\"summary\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern QUOTED_OPERATION_OBJECT_PATTERN = Pattern.compile("(?:\\[|,)\\s*\"\\s*\\{\\s*\"action\"");

    private TaskOperationsParser() {
    }

    public static TaskOperations fromJson(String raw) throws Exception {
        String jsonText = extractJson(raw);
        JSONObject json;
        try {
            json = new JSONObject(jsonText);
        } catch (Exception parseError) {
            return fromLenientJson(jsonText, parseError);
        }
        try {
            return fromJsonObject(json);
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception parseError) {
            throw parseException(parseError);
        }
    }

    /**
     * The same parse as {@link #fromJson} but reporting WHICH path produced the result (clean strict parse,
     * lenient quoted-object recovery, truncation salvage, or hard failure) so the caller can record the
     * outcome. Never throws — a failure is returned as {@link TaskOperationsCodec.ParseResult#failed} carrying
     * the {@link IllegalArgumentException} {@link #fromJson} would have thrown. Additive: {@link #fromJson}
     * is left byte-for-byte unchanged.
     */
    static TaskOperationsCodec.ParseResult fromJsonClassified(String raw) {
        String jsonText;
        try {
            jsonText = extractJson(raw);
        } catch (Exception extractError) {
            return classifiedFailure(extractError);
        }
        JSONObject json = null;
        try {
            json = new JSONObject(jsonText);
        } catch (Exception ignored) {
            // Not strict JSON — fall through to the lenient/salvage paths below.
        }
        if (json != null) {
            try {
                return TaskOperationsCodec.ParseResult.of(fromJsonObject(json), TaskOperationsCodec.OUTCOME_JSON_OK);
            } catch (Exception strictError) {
                // Strict JSON but empty/invalid operations: try to salvage before declaring failure.
                TaskOperationsCodec.ParseResult lenient = lenientClassified(jsonText);
                return lenient != null ? lenient : classifiedFailure(strictError);
            }
        }
        TaskOperationsCodec.ParseResult lenient = lenientClassified(jsonText);
        return lenient != null ? lenient : classifiedFailure(new IllegalArgumentException("Task operation response JSON could not be parsed."));
    }

    /** Lenient recovery classified by which salvage fired, or null when nothing could be recovered. */
    private static TaskOperationsCodec.ParseResult lenientClassified(String jsonText) {
        if (QUOTED_OPERATION_OBJECT_PATTERN.matcher(jsonText).find()) {
            try {
                List<JSONObject> operationObjects = operationObjectsFromMalformedArray(jsonText);
                List<FileOperation> operations = new ArrayList<>();
                for (JSONObject operationObject : operationObjects) {
                    operations.add(operationFromJson(operationObject));
                }
                if (!operations.isEmpty()) {
                    return TaskOperationsCodec.ParseResult.of(
                            new TaskOperations(summaryFromText(jsonText), operations), TaskOperationsCodec.OUTCOME_JSON_LENIENT);
                }
            } catch (Exception ignored) {
                // Fall through to truncation salvage.
            }
        }
        List<FileOperation> salvaged = completedOperations(jsonText);
        if (!salvaged.isEmpty()) {
            return TaskOperationsCodec.ParseResult.of(
                    new TaskOperations(summaryFromText(jsonText), salvaged), TaskOperationsCodec.OUTCOME_JSON_SALVAGED);
        }
        return null;
    }

    private static TaskOperationsCodec.ParseResult classifiedFailure(Exception error) {
        return TaskOperationsCodec.ParseResult.failed(
                error instanceof IllegalArgumentException ? (IllegalArgumentException) error : parseException(error));
    }

    public static List<FileOperation> completedOperations(String partialRaw) {
        List<FileOperation> operations = new ArrayList<>();
        String text = partialRaw == null ? "" : partialRaw;
        int jsonStart = text.indexOf('{');
        if (jsonStart >= 0) {
            text = text.substring(jsonStart);
        }
        int key = text.indexOf("\"operations\"");
        if (key < 0) {
            return operations;
        }
        int start = text.indexOf('[', key);
        if (start < 0) {
            return operations;
        }
        String arrayText = text.substring(start + 1);
        int index = 0;
        while (index < arrayText.length()) {
            int objectStart = arrayText.indexOf('{', index);
            if (objectStart < 0) {
                break;
            }
            int objectEnd = objectEnd(arrayText, objectStart);
            if (objectEnd < 0) {
                break;
            }
            try {
                operations.add(operationFromJson(new JSONObject(arrayText.substring(objectStart, objectEnd + 1))));
            } catch (Exception ignored) {
                // Partial stream inspection is best-effort: malformed complete objects are handled by
                // the final parser once the full response arrives.
            }
            index = objectEnd + 1;
        }
        return operations;
    }

    private static TaskOperations fromJsonObject(JSONObject json) throws Exception {
        JSONArray operationsJson = json.optJSONArray("operations");
        if (operationsJson == null || operationsJson.length() == 0) {
            TaskOperations blocked = blockedOperations(json);
            if (blocked != null) {
                return blocked;
            }
            throw new IllegalArgumentException("Task operation list is empty.");
        }
        List<FileOperation> operations = new ArrayList<>();
        for (int i = 0; i < operationsJson.length(); i++) {
            operations.add(operationFromJson(operationsJson.getJSONObject(i)));
        }
        return new TaskOperations(json.optString("summary", "").trim(), operations);
    }

    private static TaskOperations blockedOperations(JSONObject json) {
        if (!json.optBoolean("blocked", false)) {
            return null;
        }
        String reason = json.optString("blockedReason", "").trim();
        if (reason.isEmpty()) {
            return null;
        }
        return new TaskOperations(
                json.optString("summary", "").trim(),
                Collections.emptyList(),
                true,
                reason,
                json.optString("prerequisiteWork", "").trim());
    }

    private static TaskOperations fromLenientJson(String jsonText, Exception parseError) throws Exception {
        if (QUOTED_OPERATION_OBJECT_PATTERN.matcher(jsonText).find()) {
            List<JSONObject> operationObjects = operationObjectsFromMalformedArray(jsonText);
            List<FileOperation> operations = new ArrayList<>();
            for (JSONObject operationObject : operationObjects) {
                operations.add(operationFromJson(operationObject));
            }
            if (!operations.isEmpty()) {
                return new TaskOperations(summaryFromText(jsonText), operations);
            }
        }
        // Defense in depth: salvage the operations that arrived before a truncation point (the common
        // "Unterminated array" when an oversized response is cut off). Returning the complete files lets
        // partial progress survive and the correction pass fill the rest, instead of discarding the whole
        // batch and looping to retry-exhaustion.
        List<FileOperation> salvaged = completedOperations(jsonText);
        if (!salvaged.isEmpty()) {
            return new TaskOperations(summaryFromText(jsonText), salvaged);
        }
        throw parseException(parseError);
    }

    private static IllegalArgumentException parseException(Exception parseError) {
        String message = parseError == null || parseError.getMessage() == null
                ? "unknown parse error"
                : parseError.getMessage();
        return new IllegalArgumentException("Task operation response JSON could not be parsed: " + message, parseError);
    }

    private static FileOperation operationFromJson(JSONObject operationJson) {
        String action = operationJson.optString("action", "").trim();
        if (!"write".equals(action) && !"delete".equals(action) && !"drop".equals(action) && !"edit".equals(action)) {
            throw new IllegalArgumentException("Unsupported file operation action: " + action);
        }
        String path = PathValidator.normalizeGeneratedPath(operationJson.optString("path", ""));
        if ("edit".equals(action)) {
            return new FileOperation(action, path, "", operationJson.optString("find", ""), operationJson.optString("replace", ""));
        }
        return new FileOperation(action, path, "drop".equals(action) ? "" : operationJson.optString("content", ""));
    }

    private static List<JSONObject> operationObjectsFromMalformedArray(String jsonText) throws Exception {
        List<JSONObject> operations = new ArrayList<>();
        int key = jsonText.indexOf("\"operations\"");
        if (key < 0) {
            return operations;
        }
        int start = jsonText.indexOf('[', key);
        int end = jsonText.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return operations;
        }
        String arrayText = jsonText.substring(start + 1, end);
        int index = 0;
        while (index < arrayText.length()) {
            int objectStart = arrayText.indexOf('{', index);
            if (objectStart < 0) {
                break;
            }
            int objectEnd = objectEnd(arrayText, objectStart);
            if (objectEnd < 0) {
                break;
            }
            operations.add(new JSONObject(arrayText.substring(objectStart, objectEnd + 1)));
            index = objectEnd + 1;
        }
        return operations;
    }

    private static int objectEnd(String text, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String summaryFromText(String jsonText) {
        Matcher matcher = SUMMARY_PATTERN.matcher(jsonText == null ? "" : jsonText);
        if (!matcher.find()) {
            return "";
        }
        try {
            return new JSONArray("[\"" + matcher.group(1) + "\"]").getString(0).trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String extractJson(String raw) {
        String text = raw == null ? "" : raw.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("Task operation response did not contain a JSON object.");
        }
        return text.substring(start, end + 1);
    }
}
