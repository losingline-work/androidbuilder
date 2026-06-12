package com.androidbuilder.agent;

import com.androidbuilder.model.TaskManifest;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

final class TaskManifestParser {
    private TaskManifestParser() {
    }

    static TaskManifest fromJson(String raw) throws Exception {
        JSONObject json = new JSONObject(extractJson(raw));
        TaskManifest blocked = blockedManifest(json);
        if (blocked != null) {
            return blocked;
        }
        JSONArray filesJson = json.optJSONArray("files");
        if (filesJson == null || filesJson.length() == 0) {
            throw new IllegalArgumentException("Task manifest file list is empty.");
        }
        Map<String, TaskManifest.Entry> byPath = new LinkedHashMap<>();
        for (int i = 0; i < filesJson.length(); i++) {
            TaskManifest.Entry entry = entryFromJson(filesJson.getJSONObject(i));
            byPath.remove(entry.path);
            byPath.put(entry.path, entry);
        }
        return new TaskManifest(json.optString("summary", "").trim(), new ArrayList<>(byPath.values()), false, "", "");
    }

    private static TaskManifest blockedManifest(JSONObject json) {
        if (!json.optBoolean("blocked", false)) {
            return null;
        }
        String reason = json.optString("blockedReason", "").trim();
        if (reason.isEmpty()) {
            return null;
        }
        return new TaskManifest(
                json.optString("summary", "").trim(),
                new ArrayList<TaskManifest.Entry>(),
                true,
                reason,
                json.optString("prerequisiteWork", "").trim());
    }

    private static TaskManifest.Entry entryFromJson(JSONObject json) {
        String action = json.optString("action", "").trim();
        if (!"write".equals(action) && !"delete".equals(action)) {
            throw new IllegalArgumentException("Unsupported task manifest action: " + action);
        }
        String path = CanonicalPathPolicy.canonicalize(json.optString("path", ""));
        String intent = json.optString("intent", "").trim();
        return new TaskManifest.Entry(path, action, intent);
    }

    private static String extractJson(String raw) {
        String text = raw == null ? "" : raw.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("Task manifest response did not contain a JSON object.");
        }
        return text.substring(start, end + 1);
    }
}
