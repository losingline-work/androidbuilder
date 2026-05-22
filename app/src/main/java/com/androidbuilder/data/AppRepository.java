package com.androidbuilder.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.androidbuilder.model.ArtifactRecord;
import com.androidbuilder.model.BuildJobRecord;
import com.androidbuilder.model.ChatMessage;
import com.androidbuilder.model.ProjectRecord;
import com.androidbuilder.util.FileUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class AppRepository {
    private final Context context;
    private final DatabaseHelper helper;

    public AppRepository(Context context) {
        this.context = context.getApplicationContext();
        this.helper = new DatabaseHelper(this.context);
    }

    public synchronized ProjectRecord createProject(String name, String packageName, String description) {
        long now = System.currentTimeMillis();
        ContentValues values = new ContentValues();
        values.put("name", name);
        values.put("package_name", packageName);
        values.put("description", description);
        values.put("created_at", now);
        values.put("updated_at", now);
        values.put("last_build_status", "idle");
        long id = helper.getWritableDatabase().insertOrThrow(DatabaseHelper.TABLE_PROJECTS, null, values);
        return getProject(id);
    }

    public synchronized void renameProject(long id, String name) {
        ContentValues values = new ContentValues();
        values.put("name", name);
        values.put("updated_at", System.currentTimeMillis());
        helper.getWritableDatabase().update(DatabaseHelper.TABLE_PROJECTS, values, "id = ?", new String[]{String.valueOf(id)});
    }

    public synchronized void updateProjectMetadata(long id, String packageName, String description) {
        ContentValues values = new ContentValues();
        values.put("package_name", packageName);
        values.put("description", description);
        values.put("updated_at", System.currentTimeMillis());
        helper.getWritableDatabase().update(DatabaseHelper.TABLE_PROJECTS, values, "id = ?", new String[]{String.valueOf(id)});
    }

    public synchronized void updateProjectBuildStatus(long id, String status) {
        ContentValues values = new ContentValues();
        values.put("last_build_status", status);
        values.put("updated_at", System.currentTimeMillis());
        helper.getWritableDatabase().update(DatabaseHelper.TABLE_PROJECTS, values, "id = ?", new String[]{String.valueOf(id)});
    }

    public synchronized void deleteProject(long id) {
        helper.getWritableDatabase().delete(DatabaseHelper.TABLE_PROJECTS, "id = ?", new String[]{String.valueOf(id)});
        FileUtils.deleteRecursively(projectRoot(id));
    }

    public synchronized ProjectRecord getProject(long id) {
        try (Cursor cursor = helper.getReadableDatabase().query(DatabaseHelper.TABLE_PROJECTS, null, "id = ?", new String[]{String.valueOf(id)}, null, null, null)) {
            if (cursor.moveToFirst()) {
                return readProject(cursor);
            }
        }
        return null;
    }

    public synchronized List<ProjectRecord> listProjects(String query) {
        String selection = null;
        String[] args = null;
        if (query != null && !query.trim().isEmpty()) {
            selection = "name LIKE ? OR description LIKE ? OR package_name LIKE ?";
            String like = "%" + query.trim() + "%";
            args = new String[]{like, like, like};
        }
        List<ProjectRecord> rows = new ArrayList<>();
        try (Cursor cursor = helper.getReadableDatabase().query(DatabaseHelper.TABLE_PROJECTS, null, selection, args, null, null, "updated_at DESC")) {
            while (cursor.moveToNext()) {
                rows.add(readProject(cursor));
            }
        }
        return rows;
    }

    public synchronized ChatMessage addMessage(long projectId, String role, String content, Long linkedBuildJobId) {
        ContentValues values = new ContentValues();
        values.put("project_id", projectId);
        values.put("role", role);
        values.put("content", content);
        values.put("created_at", System.currentTimeMillis());
        if (linkedBuildJobId != null) {
            values.put("linked_build_job_id", linkedBuildJobId);
        }
        long id = helper.getWritableDatabase().insertOrThrow(DatabaseHelper.TABLE_MESSAGES, null, values);
        touchProject(projectId);
        return getMessage(id);
    }

    public synchronized List<ChatMessage> listMessages(long projectId) {
        List<ChatMessage> rows = new ArrayList<>();
        try (Cursor cursor = helper.getReadableDatabase().query(DatabaseHelper.TABLE_MESSAGES, null, "project_id = ?", new String[]{String.valueOf(projectId)}, null, null, "created_at ASC")) {
            while (cursor.moveToNext()) {
                rows.add(readMessage(cursor));
            }
        }
        return rows;
    }

    public synchronized void deleteMessage(long messageId) {
        ChatMessage message = getMessage(messageId);
        helper.getWritableDatabase().delete(DatabaseHelper.TABLE_MESSAGES, "id = ?", new String[]{String.valueOf(messageId)});
        if (message != null) {
            touchProject(message.projectId);
        }
    }

    public synchronized BuildJobRecord createBuildJob(long projectId) {
        ContentValues values = new ContentValues();
        values.put("project_id", projectId);
        values.put("status", "queued");
        values.put("phase", "waiting");
        values.put("created_at", System.currentTimeMillis());
        long id = helper.getWritableDatabase().insertOrThrow(DatabaseHelper.TABLE_BUILD_JOBS, null, values);
        updateProjectBuildStatus(projectId, "queued");
        return getBuildJob(id);
    }

    public synchronized BuildJobRecord getBuildJob(long id) {
        try (Cursor cursor = helper.getReadableDatabase().query(DatabaseHelper.TABLE_BUILD_JOBS, null, "id = ?", new String[]{String.valueOf(id)}, null, null, null)) {
            if (cursor.moveToFirst()) {
                return readBuildJob(cursor);
            }
        }
        return null;
    }

    public synchronized BuildJobRecord latestBuildJob(long projectId) {
        try (Cursor cursor = helper.getReadableDatabase().query(DatabaseHelper.TABLE_BUILD_JOBS, null, "project_id = ?", new String[]{String.valueOf(projectId)}, null, null, "created_at DESC", "1")) {
            if (cursor.moveToFirst()) {
                return readBuildJob(cursor);
            }
        }
        return null;
    }

    public synchronized void updateBuildJob(long id, String status, String phase, String logsPath, String apkPath, String errorSummary, int retryCount) {
        BuildJobRecord current = getBuildJob(id);
        ContentValues values = new ContentValues();
        values.put("status", status);
        values.put("phase", phase);
        values.put("logs_path", logsPath);
        values.put("apk_path", apkPath);
        values.put("error_summary", errorSummary);
        values.put("retry_count", retryCount);
        helper.getWritableDatabase().update(DatabaseHelper.TABLE_BUILD_JOBS, values, "id = ?", new String[]{String.valueOf(id)});
        if (current != null) {
            updateProjectBuildStatus(current.projectId, status);
        }
    }

    public synchronized ArtifactRecord addArtifact(long projectId, long buildJobId, String type, String path) {
        ContentValues values = new ContentValues();
        values.put("project_id", projectId);
        values.put("build_job_id", buildJobId);
        values.put("type", type);
        values.put("path", path);
        values.put("created_at", System.currentTimeMillis());
        long id = helper.getWritableDatabase().insertOrThrow(DatabaseHelper.TABLE_ARTIFACTS, null, values);
        try (Cursor cursor = helper.getReadableDatabase().query(DatabaseHelper.TABLE_ARTIFACTS, null, "id = ?", new String[]{String.valueOf(id)}, null, null, null)) {
            cursor.moveToFirst();
            return readArtifact(cursor);
        }
    }

    public File projectRoot(long projectId) {
        return new File(context.getFilesDir(), "projects/" + projectId);
    }

    public File sourceDir(long projectId) {
        return new File(projectRoot(projectId), "source");
    }

    public File jobDir(long projectId, long jobId) {
        return new File(projectRoot(projectId), "jobs/" + jobId);
    }

    private void touchProject(long projectId) {
        ContentValues values = new ContentValues();
        values.put("updated_at", System.currentTimeMillis());
        helper.getWritableDatabase().update(DatabaseHelper.TABLE_PROJECTS, values, "id = ?", new String[]{String.valueOf(projectId)});
    }

    private ProjectRecord readProject(Cursor cursor) {
        return new ProjectRecord(
                cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                cursor.getString(cursor.getColumnIndexOrThrow("name")),
                cursor.getString(cursor.getColumnIndexOrThrow("package_name")),
                cursor.getString(cursor.getColumnIndexOrThrow("description")),
                cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")),
                cursor.getString(cursor.getColumnIndexOrThrow("last_build_status"))
        );
    }

    private ChatMessage getMessage(long id) {
        try (Cursor cursor = helper.getReadableDatabase().query(DatabaseHelper.TABLE_MESSAGES, null, "id = ?", new String[]{String.valueOf(id)}, null, null, null)) {
            cursor.moveToFirst();
            return readMessage(cursor);
        }
    }

    private ChatMessage readMessage(Cursor cursor) {
        int linkedIndex = cursor.getColumnIndexOrThrow("linked_build_job_id");
        Long linked = cursor.isNull(linkedIndex) ? null : cursor.getLong(linkedIndex);
        return new ChatMessage(
                cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                cursor.getLong(cursor.getColumnIndexOrThrow("project_id")),
                cursor.getString(cursor.getColumnIndexOrThrow("role")),
                cursor.getString(cursor.getColumnIndexOrThrow("content")),
                cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                linked
        );
    }

    private BuildJobRecord readBuildJob(Cursor cursor) {
        return new BuildJobRecord(
                cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                cursor.getLong(cursor.getColumnIndexOrThrow("project_id")),
                cursor.getString(cursor.getColumnIndexOrThrow("status")),
                cursor.getString(cursor.getColumnIndexOrThrow("phase")),
                cursor.getString(cursor.getColumnIndexOrThrow("logs_path")),
                cursor.getString(cursor.getColumnIndexOrThrow("apk_path")),
                cursor.getString(cursor.getColumnIndexOrThrow("error_summary")),
                cursor.getInt(cursor.getColumnIndexOrThrow("retry_count")),
                cursor.getLong(cursor.getColumnIndexOrThrow("created_at"))
        );
    }

    private ArtifactRecord readArtifact(Cursor cursor) {
        return new ArtifactRecord(
                cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                cursor.getLong(cursor.getColumnIndexOrThrow("project_id")),
                cursor.getLong(cursor.getColumnIndexOrThrow("build_job_id")),
                cursor.getString(cursor.getColumnIndexOrThrow("type")),
                cursor.getString(cursor.getColumnIndexOrThrow("path")),
                cursor.getLong(cursor.getColumnIndexOrThrow("created_at"))
        );
    }
}
