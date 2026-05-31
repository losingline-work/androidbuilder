package com.androidbuilder.agent;

import com.androidbuilder.model.ProjectTaskRecord;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class ImplementationTaskParser {
    private ImplementationTaskParser() {
    }

    public static List<ProjectTaskRecord> fromJson(String raw) throws Exception {
        JSONObject json = new JSONObject(extractJson(raw));
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
            tasks.add(new ProjectTaskRecord(0, 0, i, title, instruction, "pending", "", 0, 0, 0, 0));
        }
        return tasks;
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
