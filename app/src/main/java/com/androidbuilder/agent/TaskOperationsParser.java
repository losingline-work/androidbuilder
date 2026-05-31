package com.androidbuilder.agent;

import com.androidbuilder.model.FileOperation;
import com.androidbuilder.model.TaskOperations;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class TaskOperationsParser {
    private TaskOperationsParser() {
    }

    public static TaskOperations fromJson(String raw) throws Exception {
        JSONObject json = new JSONObject(extractJson(raw));
        JSONArray operationsJson = json.optJSONArray("operations");
        if (operationsJson == null || operationsJson.length() == 0) {
            throw new IllegalArgumentException("Task operation list is empty.");
        }
        List<FileOperation> operations = new ArrayList<>();
        for (int i = 0; i < operationsJson.length(); i++) {
            JSONObject operationJson = operationsJson.getJSONObject(i);
            String action = operationJson.optString("action", "").trim();
            if (!"write".equals(action) && !"delete".equals(action)) {
                throw new IllegalArgumentException("Unsupported file operation action: " + action);
            }
            String path = PathValidator.normalizeGeneratedPath(operationJson.optString("path", ""));
            operations.add(new FileOperation(action, path, operationJson.optString("content", "")));
        }
        return new TaskOperations(json.optString("summary", "").trim(), operations);
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
