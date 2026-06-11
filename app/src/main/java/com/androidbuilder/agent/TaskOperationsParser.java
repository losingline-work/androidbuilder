package com.androidbuilder.agent;

import com.androidbuilder.model.FileOperation;
import com.androidbuilder.model.TaskOperations;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
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
        return fromJsonObject(json);
    }

    private static TaskOperations fromJsonObject(JSONObject json) throws Exception {
        JSONArray operationsJson = json.optJSONArray("operations");
        if (operationsJson == null || operationsJson.length() == 0) {
            throw new IllegalArgumentException("Task operation list is empty.");
        }
        List<FileOperation> operations = new ArrayList<>();
        for (int i = 0; i < operationsJson.length(); i++) {
            operations.add(operationFromJson(operationsJson.getJSONObject(i)));
        }
        return new TaskOperations(json.optString("summary", "").trim(), operations);
    }

    private static TaskOperations fromLenientJson(String jsonText, Exception parseError) throws Exception {
        if (!QUOTED_OPERATION_OBJECT_PATTERN.matcher(jsonText).find()) {
            throw parseError;
        }
        List<JSONObject> operationObjects = operationObjectsFromMalformedArray(jsonText);
        if (operationObjects.isEmpty()) {
            throw parseError;
        }
        List<FileOperation> operations = new ArrayList<>();
        for (JSONObject operationObject : operationObjects) {
            operations.add(operationFromJson(operationObject));
        }
        if (operations.isEmpty()) {
            throw new IllegalArgumentException("Task operation list is empty.");
        }
        return new TaskOperations(summaryFromText(jsonText), operations);
    }

    private static FileOperation operationFromJson(JSONObject operationJson) {
        String action = operationJson.optString("action", "").trim();
        if (!"write".equals(action) && !"delete".equals(action)) {
            throw new IllegalArgumentException("Unsupported file operation action: " + action);
        }
        String path = PathValidator.normalizeGeneratedPath(operationJson.optString("path", ""));
        return new FileOperation(action, path, operationJson.optString("content", ""));
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
