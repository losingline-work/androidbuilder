package com.androidbuilder.agent;

import com.androidbuilder.model.FileOperation;
import com.androidbuilder.model.TaskOperations;
import com.androidbuilder.util.FileUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class TaskDraftStore {
    // Draft JSON measures ~6.7KB per file in practice; a legitimate manifest can plan up to
    // TaskManifest.MAX_FILES (120) files (~800KB). The previous 300KB cap silently destroyed
    // exactly the largest partial results.
    static final int MAX_BYTES = 2 * 1024 * 1024;

    private final File directory;

    TaskDraftStore(File projectRoot) {
        this.directory = new File(projectRoot, "task-drafts");
    }

    void save(long taskId, TaskOperations draft) {
        if (taskId <= 0 || draft == null || isEmptyDraft(draft)) {
            return;
        }
        File file = fileForTask(taskId);
        try {
            String json = toJson(draft).toString();
            if (json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
                // Keep whatever draft is already on disk: an oversized new draft must not
                // destroy the previously saved (smaller) progress.
                return;
            }
            FileUtils.writeText(file, json);
        } catch (Exception ignored) {
            // Serialization failure: leave any existing draft untouched; a stale draft is
            // strictly better than no draft for the next dispatch.
        }
    }

    TaskOperations load(long taskId) {
        if (taskId <= 0) {
            return null;
        }
        File file = fileForTask(taskId);
        if (!file.exists()) {
            return null;
        }
        if (file.length() > MAX_BYTES) {
            delete(taskId);
            return null;
        }
        try {
            return fromJson(FileUtils.readText(file));
        } catch (Exception ignored) {
            delete(taskId);
            return null;
        }
    }

    void delete(long taskId) {
        if (taskId <= 0) {
            return;
        }
        File file = fileForTask(taskId);
        if (file.exists()) {
            file.delete();
        }
    }

    void deleteAll() {
        FileUtils.deleteRecursively(directory);
    }

    File fileForTest(long taskId) {
        return fileForTask(taskId);
    }

    private File fileForTask(long taskId) {
        return new File(directory, "task-" + taskId + ".json");
    }

    private static boolean isEmptyDraft(TaskOperations draft) {
        return (draft.operations == null || draft.operations.isEmpty())
                && (draft.manifestJson == null || draft.manifestJson.trim().isEmpty())
                && !draft.blocked;
    }

    private static JSONObject toJson(TaskOperations draft) throws Exception {
        JSONObject json = new JSONObject();
        json.put("summary", draft.summary == null ? "" : draft.summary);
        JSONArray operations = new JSONArray();
        for (FileOperation operation : draft.operations) {
            JSONObject item = new JSONObject();
            item.put("action", operation.action == null ? "" : operation.action.trim());
            item.put("path", PathValidator.normalizeGeneratedPath(operation.path));
            item.put("content", operation.content == null ? "" : operation.content);
            if ("edit".equals(operation.action)) {
                item.put("find", operation.find == null ? "" : operation.find);
                item.put("replace", operation.replace == null ? "" : operation.replace);
            }
            operations.put(item);
        }
        json.put("operations", operations);
        if (draft.manifestJson != null && !draft.manifestJson.trim().isEmpty()) {
            json.put("manifestJson", draft.manifestJson.trim());
        }
        if (draft.acceptedPaths != null && !draft.acceptedPaths.isEmpty()) {
            JSONArray paths = new JSONArray();
            for (String path : draft.acceptedPaths) {
                paths.put(PathValidator.normalizeGeneratedPath(path));
            }
            json.put("acceptedPaths", paths);
        }
        return json;
    }

    private static TaskOperations fromJson(String raw) throws Exception {
        JSONObject json = new JSONObject(raw == null ? "" : raw);
        JSONArray operationsJson = json.optJSONArray("operations");
        List<FileOperation> operations = new ArrayList<>();
        if (operationsJson != null) {
            for (int i = 0; i < operationsJson.length(); i++) {
                operations.add(operationFromJson(operationsJson.getJSONObject(i)));
            }
        }
        return new TaskOperations(
                json.optString("summary", ""),
                operations,
                json.optBoolean("blocked", false),
                json.optString("blockedReason", ""),
                json.optString("prerequisiteWork", ""),
                json.optString("manifestJson", ""),
                stringArray(json.optJSONArray("acceptedPaths")));
    }

    private static FileOperation operationFromJson(JSONObject operationJson) {
        String action = operationJson.optString("action", "").trim();
        String path = PathValidator.normalizeGeneratedPath(operationJson.optString("path", ""));
        if ("edit".equals(action)) {
            return new FileOperation(action, path, "", operationJson.optString("find", ""), operationJson.optString("replace", ""));
        }
        return new FileOperation(action, path, "drop".equals(action) ? "" : operationJson.optString("content", ""));
    }

    private static List<String> stringArray(JSONArray array) {
        List<String> values = new ArrayList<>();
        if (array == null) {
            return values;
        }
        for (int i = 0; i < array.length(); i++) {
            String value = array.optString(i, "").trim();
            if (!value.isEmpty()) {
                values.add(PathValidator.normalizeGeneratedPath(value));
            }
        }
        return values;
    }
}
