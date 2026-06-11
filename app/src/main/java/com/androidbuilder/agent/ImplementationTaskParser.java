package com.androidbuilder.agent;

import com.androidbuilder.model.HermesTaskContract;
import com.androidbuilder.model.ProjectTaskRecord;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class ImplementationTaskParser {
    private ImplementationTaskParser() {
    }

    public static List<ProjectTaskRecord> fromJson(String raw) throws Exception {
        String jsonText = extractJson(raw);
        JSONObject json = parseJsonObject(jsonText);
        JSONArray tasksJson = json.optJSONArray("tasks");
        if (tasksJson == null || tasksJson.length() == 0) {
            throw new IllegalArgumentException("Implementation task list is empty.");
        }
        List<ProjectTaskRecord> tasks = new ArrayList<>();
        for (int i = 0; i < tasksJson.length(); i++) {
            JSONObject taskJson = tasksJson.getJSONObject(i);
            String title = taskJson.optString("title", "").trim();
            String instruction = taskJson.optString("instruction", "").trim();
            if (title.isEmpty() || instruction.isEmpty()) {
                throw new IllegalArgumentException("Implementation task requires title and instruction.");
            }
            HermesTaskContract contract = HermesTaskContractCodec.fromJson(taskJson);
            instruction = HermesTaskContractCodec.appendToInstruction(instruction, contract);
            tasks.add(new ProjectTaskRecord(0, 0, i, title, instruction, "pending", "", 0, 0, 0, 0));
        }
        return tasks;
    }

    private static JSONObject parseJsonObject(String jsonText) throws Exception {
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

    private static String escapeBareQuotesInsideStrings(String jsonText) {
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

    private static String extractJson(String raw) {
        String text = raw == null ? "" : raw.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("Task response did not contain a JSON object.");
        }
        return text.substring(start, end + 1);
    }
}
