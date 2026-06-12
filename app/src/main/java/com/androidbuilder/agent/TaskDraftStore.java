package com.androidbuilder.agent;

import com.androidbuilder.model.FileOperation;
import com.androidbuilder.model.TaskOperations;
import com.androidbuilder.util.FileUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;

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
        if (taskId <= 0 || draft == null || draft.operations == null || draft.operations.isEmpty()) {
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
            return TaskOperationsParser.fromJson(FileUtils.readText(file));
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
        return json;
    }
}
