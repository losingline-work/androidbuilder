package com.androidbuilder.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.androidbuilder.model.ArtifactRecord;
import com.androidbuilder.model.AiConversationRecord;
import com.androidbuilder.model.BuildJobRecord;
import com.androidbuilder.model.ChatMessage;
import com.androidbuilder.model.HermesAgentRunRecord;
import com.androidbuilder.model.HermesExecutionRunRecord;
import com.androidbuilder.model.ProjectPlanRecord;
import com.androidbuilder.model.ProjectRecord;
import com.androidbuilder.model.ProjectTaskRecord;
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

    public synchronized AiConversationRecord addAiConversation(
            long projectId,
            String source,
            String title,
            String requestText,
            String responseText,
            String status,
            String metadata,
            Long linkedBuildJobId) {
        ContentValues values = new ContentValues();
        values.put("project_id", projectId);
        values.put("source", source == null ? "" : source);
        values.put("title", title == null ? "" : title);
        values.put("request_text", requestText == null ? "" : requestText);
        values.put("response_text", responseText == null ? "" : responseText);
        values.put("status", status == null ? "" : status);
        values.put("metadata", metadata == null ? "" : metadata);
        if (linkedBuildJobId == null) {
            values.putNull("linked_build_job_id");
        } else {
            values.put("linked_build_job_id", linkedBuildJobId);
        }
        values.put("created_at", System.currentTimeMillis());
        long id = helper.getWritableDatabase().insertOrThrow(DatabaseHelper.TABLE_AI_CONVERSATIONS, null, values);
        touchProject(projectId);
        return getAiConversation(id);
    }

    public synchronized List<AiConversationRecord> listAiConversations(long projectId) {
        List<AiConversationRecord> rows = new ArrayList<>();
        try (Cursor cursor = helper.getReadableDatabase().query(DatabaseHelper.TABLE_AI_CONVERSATIONS, null, "project_id = ?", new String[]{String.valueOf(projectId)}, null, null, "created_at ASC")) {
            while (cursor.moveToNext()) {
                rows.add(readAiConversation(cursor));
            }
        }
        return rows;
    }

    /**
     * Lightweight AI-conversation rows for the log LIST: request/response text is truncated at the SQL
     * level to {@code previewChars} so a project with hundreds of multi-megabyte records can be listed
     * without loading every full body into memory (which OOM-crashed the log screen on large projects).
     * Metadata is kept whole (it is small and the duration summary needs it). Fetch full text on demand
     * with {@link #getAiConversation(long)}.
     */
    public synchronized List<AiConversationRecord> listAiConversationPreviews(long projectId, int previewChars) {
        int limit = Math.max(1, previewChars);
        String[] columns = {
                "id", "project_id", "source", "title",
                "substr(request_text, 1, " + limit + ") AS request_text",
                "substr(response_text, 1, " + limit + ") AS response_text",
                "status", "metadata", "linked_build_job_id", "created_at"
        };
        List<AiConversationRecord> rows = new ArrayList<>();
        try (Cursor cursor = helper.getReadableDatabase().query(DatabaseHelper.TABLE_AI_CONVERSATIONS, columns, "project_id = ?", new String[]{String.valueOf(projectId)}, null, null, "created_at ASC")) {
            while (cursor.moveToNext()) {
                rows.add(readAiConversation(cursor));
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

    public synchronized ProjectPlanRecord latestProjectPlan(long projectId) {
        try (Cursor cursor = helper.getReadableDatabase().query(DatabaseHelper.TABLE_PROJECT_PLANS, null, "project_id = ?", new String[]{String.valueOf(projectId)}, null, null, "updated_at DESC", "1")) {
            if (cursor.moveToFirst()) {
                return readProjectPlan(cursor);
            }
        }
        return null;
    }

    public synchronized ProjectPlanRecord saveProjectPlan(long projectId, String content, String status, Long linkedBuildJobId) {
        ProjectPlanRecord current = latestProjectPlan(projectId);
        long now = System.currentTimeMillis();
        ContentValues values = new ContentValues();
        values.put("project_id", projectId);
        values.put("content", content == null ? "" : content);
        values.put("status", status == null ? "idle" : status);
        if (linkedBuildJobId == null) {
            values.putNull("linked_build_job_id");
        } else {
            values.put("linked_build_job_id", linkedBuildJobId);
        }
        values.put("updated_at", now);
        if (current == null) {
            values.put("created_at", now);
            helper.getWritableDatabase().insertOrThrow(DatabaseHelper.TABLE_PROJECT_PLANS, null, values);
        } else {
            helper.getWritableDatabase().update(DatabaseHelper.TABLE_PROJECT_PLANS, values, "id = ?", new String[]{String.valueOf(current.id)});
        }
        touchProject(projectId);
        return latestProjectPlan(projectId);
    }

    public synchronized void updateProjectPlanStatus(long projectId, String status, Long linkedBuildJobId) {
        ProjectPlanRecord current = latestProjectPlan(projectId);
        saveProjectPlan(projectId, current == null ? "" : current.content, status, linkedBuildJobId);
    }

    public synchronized void replaceProjectTasks(long projectId, List<ProjectTaskRecord> tasks) {
        SQLiteDatabase db = helper.getWritableDatabase();
        long now = System.currentTimeMillis();
        db.delete(DatabaseHelper.TABLE_PROJECT_TASKS, "project_id = ?", new String[]{String.valueOf(projectId)});
        deleteTaskDrafts(projectId);
        for (int i = 0; i < tasks.size(); i++) {
            ProjectTaskRecord task = tasks.get(i);
            ContentValues values = new ContentValues();
            values.put("project_id", projectId);
            values.put("sort_order", i);
            values.put("title", task.title);
            values.put("instruction", task.instruction);
            values.put("status", "pending");
            values.put("result_summary", "");
            values.put("created_at", now);
            values.put("updated_at", now);
            values.put("started_at", 0);
            values.put("completed_at", 0);
            db.insertOrThrow(DatabaseHelper.TABLE_PROJECT_TASKS, null, values);
        }
        touchProject(projectId);
    }

    public synchronized void clearProjectTasks(long projectId) {
        helper.getWritableDatabase().delete(DatabaseHelper.TABLE_PROJECT_TASKS, "project_id = ?", new String[]{String.valueOf(projectId)});
        deleteTaskDrafts(projectId);
        touchProject(projectId);
    }

    public synchronized List<ProjectTaskRecord> listProjectTasks(long projectId) {
        List<ProjectTaskRecord> rows = new ArrayList<>();
        try (Cursor cursor = helper.getReadableDatabase().query(DatabaseHelper.TABLE_PROJECT_TASKS, null, "project_id = ?", new String[]{String.valueOf(projectId)}, null, null, "sort_order ASC")) {
            while (cursor.moveToNext()) {
                rows.add(readProjectTask(cursor));
            }
        }
        return rows;
    }

    public synchronized ProjectTaskRecord nextPendingProjectTask(long projectId) {
        try (Cursor cursor = helper.getReadableDatabase().query(DatabaseHelper.TABLE_PROJECT_TASKS, null, "project_id = ? AND status IN (?, ?)", new String[]{String.valueOf(projectId), "failed", "pending"}, null, null, "CASE status WHEN 'failed' THEN 0 ELSE 1 END, sort_order ASC", "1")) {
            if (cursor.moveToFirst()) {
                return readProjectTask(cursor);
            }
        }
        return null;
    }

    public synchronized void updateProjectTask(long id, String status, String resultSummary) {
        ProjectTaskRecord current = getProjectTask(id);
        long now = System.currentTimeMillis();
        ContentValues values = new ContentValues();
        values.put("status", status);
        values.put("result_summary", resultSummary == null ? "" : resultSummary);
        values.put("updated_at", now);
        if ("running".equals(status)) {
            values.put("started_at", now);
            values.put("completed_at", 0);
        } else if ("done".equals(status) || "failed".equals(status)) {
            values.put("started_at", current == null || current.startedAt == 0 ? now : current.startedAt);
            values.put("completed_at", now);
        }
        helper.getWritableDatabase().update(DatabaseHelper.TABLE_PROJECT_TASKS, values, "id = ?", new String[]{String.valueOf(id)});
    }

    public synchronized ProjectTaskRecord getProjectTask(long id) {
        try (Cursor cursor = helper.getReadableDatabase().query(DatabaseHelper.TABLE_PROJECT_TASKS, null, "id = ?", new String[]{String.valueOf(id)}, null, null, null)) {
            if (cursor.moveToFirst()) {
                return readProjectTask(cursor);
            }
        }
        return null;
    }

    public synchronized HermesExecutionRunRecord createHermesExecutionRun(
            long projectId, long buildJobId, String mode, int maxParallel, String baseSourceHash) {
        long now = System.currentTimeMillis();
        ContentValues values = new ContentValues();
        values.put("project_id", projectId);
        values.put("build_job_id", buildJobId);
        values.put("status", "running");
        values.put("mode", cleanOrDefault(mode, "parallel"));
        values.put("max_parallel", Math.max(1, maxParallel));
        values.put("base_source_hash", clean(baseSourceHash));
        values.put("created_at", now);
        values.put("updated_at", now);
        long id = helper.getWritableDatabase().insertOrThrow(DatabaseHelper.TABLE_HERMES_EXECUTION_RUNS, null, values);
        return getHermesExecutionRun(id);
    }

    public synchronized void updateHermesExecutionRun(long id, String status, String baseSourceHash) {
        ContentValues values = new ContentValues();
        values.put("status", clean(status));
        values.put("base_source_hash", clean(baseSourceHash));
        values.put("updated_at", System.currentTimeMillis());
        helper.getWritableDatabase().update(DatabaseHelper.TABLE_HERMES_EXECUTION_RUNS, values, "id = ?", new String[]{String.valueOf(id)});
    }

    public synchronized HermesExecutionRunRecord getHermesExecutionRun(long id) {
        try (Cursor cursor = helper.getReadableDatabase().query(DatabaseHelper.TABLE_HERMES_EXECUTION_RUNS, null, "id = ?", new String[]{String.valueOf(id)}, null, null, null)) {
            if (cursor.moveToFirst()) {
                return readHermesExecutionRun(cursor);
            }
        }
        return null;
    }

    public synchronized List<HermesExecutionRunRecord> listRunningHermesExecutionRuns(long projectId) {
        List<HermesExecutionRunRecord> rows = new ArrayList<>();
        try (Cursor cursor = helper.getReadableDatabase().query(
                DatabaseHelper.TABLE_HERMES_EXECUTION_RUNS,
                null,
                "project_id = ? AND status = ?",
                new String[]{String.valueOf(projectId), "running"},
                null,
                null,
                "created_at ASC")) {
            while (cursor.moveToNext()) {
                rows.add(readHermesExecutionRun(cursor));
            }
        }
        return rows;
    }

    public synchronized HermesAgentRunRecord createHermesAgentRun(
            long executionRunId, long projectTaskId, int batchIndex, int agentIndex,
            String status, String workDir, String baseSourceHash, String lockedPathsJson) {
        String cleanStatus = cleanOrDefault(status, "pending");
        long now = System.currentTimeMillis();
        ContentValues values = new ContentValues();
        values.put("execution_run_id", executionRunId);
        values.put("project_task_id", projectTaskId);
        values.put("batch_index", batchIndex);
        values.put("agent_index", agentIndex);
        values.put("status", cleanStatus);
        values.put("work_dir", clean(workDir));
        values.put("base_source_hash", clean(baseSourceHash));
        values.put("merged_source_hash", "");
        values.put("locked_paths_json", cleanOrDefault(lockedPathsJson, "[]"));
        values.put("summary", "");
        values.put("error_summary", "");
        values.put("started_at", "running".equals(cleanStatus) ? now : 0);
        values.put("completed_at", 0);
        long id = helper.getWritableDatabase().insertOrThrow(DatabaseHelper.TABLE_HERMES_AGENT_RUNS, null, values);
        return getHermesAgentRun(id);
    }

    public synchronized void updateHermesAgentRun(
            long id, String status, String mergedSourceHash, String summary, String errorSummary) {
        HermesAgentRunRecord current = getHermesAgentRun(id);
        String cleanStatus = clean(status);
        long now = System.currentTimeMillis();
        ContentValues values = new ContentValues();
        values.put("status", cleanStatus);
        values.put("merged_source_hash", clean(mergedSourceHash));
        values.put("summary", clean(summary));
        values.put("error_summary", clean(errorSummary));
        if ("running".equals(cleanStatus)) {
            values.put("started_at", now);
            values.put("completed_at", 0);
        } else if ("done".equals(cleanStatus) || "failed".equals(cleanStatus)) {
            values.put("started_at", current == null || current.startedAt == 0 ? now : current.startedAt);
            values.put("completed_at", now);
        }
        helper.getWritableDatabase().update(DatabaseHelper.TABLE_HERMES_AGENT_RUNS, values, "id = ?", new String[]{String.valueOf(id)});
    }

    public synchronized HermesAgentRunRecord getHermesAgentRun(long id) {
        try (Cursor cursor = helper.getReadableDatabase().query(DatabaseHelper.TABLE_HERMES_AGENT_RUNS, null, "id = ?", new String[]{String.valueOf(id)}, null, null, null)) {
            if (cursor.moveToFirst()) {
                return readHermesAgentRun(cursor);
            }
        }
        return null;
    }

    public synchronized List<HermesAgentRunRecord> listHermesAgentRunsForProject(long projectId) {
        return listHermesAgentRunsForProjectByStatuses(projectId, null);
    }

    public synchronized List<HermesAgentRunRecord> listActiveHermesAgentRuns(long projectId) {
        return listHermesAgentRunsForProjectByStatuses(projectId, new String[]{"pending", "running", "merge_pending"});
    }

    public synchronized List<HermesAgentRunRecord> listRunningHermesAgentRuns(long projectId) {
        return listHermesAgentRunsForProjectByStatuses(projectId, new String[]{"running"});
    }

    public synchronized boolean recoverInterruptedWork(long projectId, String message) {
        boolean recovered = false;
        String summary = message == null ? "" : message;
        ProjectPlanRecord plan = latestProjectPlan(projectId);
        if (plan != null && "coding".equals(plan.status)) {
            updateProjectPlanStatus(projectId, "planned", plan.linkedBuildJobId);
            recovered = true;
        } else if (plan != null && "planning".equals(plan.status)) {
            updateProjectPlanStatus(projectId, "idle", null);
            recovered = true;
        }
        for (ProjectTaskRecord task : listProjectTasks(projectId)) {
            if ("running".equals(task.status)) {
                updateProjectTask(task.id, "failed", summary);
                recovered = true;
            }
        }
        for (HermesAgentRunRecord run : listActiveHermesAgentRuns(projectId)) {
            updateHermesAgentRun(run.id, "failed", "", "", summary);
            recovered = true;
        }
        for (HermesExecutionRunRecord run : listRunningHermesExecutionRuns(projectId)) {
            updateHermesExecutionRun(run.id, "failed", run.baseSourceHash);
            recovered = true;
        }
        BuildJobRecord job = latestBuildJob(projectId);
        if (job != null && ("queued".equals(job.status) || "generating".equals(job.status) || "building".equals(job.status))) {
            updateBuildJob(job.id, "failed", "interrupted", job.logsPath, job.apkPath, summary, job.retryCount);
            recovered = true;
        }
        return recovered;
    }

    public synchronized BuildJobRecord createBuildJob(long projectId) {
        ContentValues values = new ContentValues();
        values.put("project_id", projectId);
        values.put("status", "queued");
        values.put("phase", "waiting");
        long now = System.currentTimeMillis();
        values.put("created_at", now);
        values.put("updated_at", now);
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

    public synchronized BuildJobRecord latestBuildJobWithApk(long projectId) {
        try (Cursor cursor = helper.getReadableDatabase().query(
                DatabaseHelper.TABLE_BUILD_JOBS,
                null,
                "project_id = ? AND apk_path IS NOT NULL",
                new String[]{String.valueOf(projectId)},
                null,
                null,
                "created_at DESC",
                "1")) {
            if (cursor.moveToFirst()) {
                return readBuildJob(cursor);
            }
        }
        return null;
    }

    public synchronized BuildJobRecord latestFailedBuildJobWithLog(long projectId) {
        try (Cursor cursor = helper.getReadableDatabase().query(
                DatabaseHelper.TABLE_BUILD_JOBS,
                null,
                "project_id = ? AND status = ? AND logs_path IS NOT NULL",
                new String[]{String.valueOf(projectId), "failed"},
                null,
                null,
                "created_at DESC",
                "1")) {
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
        values.put("updated_at", System.currentTimeMillis());
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

    private void deleteTaskDrafts(long projectId) {
        FileUtils.deleteRecursively(new File(projectRoot(projectId), "task-drafts"));
    }

    private void touchProject(long projectId) {
        ContentValues values = new ContentValues();
        values.put("updated_at", System.currentTimeMillis());
        helper.getWritableDatabase().update(DatabaseHelper.TABLE_PROJECTS, values, "id = ?", new String[]{String.valueOf(projectId)});
    }

    private List<HermesAgentRunRecord> listHermesAgentRunsForProjectByStatuses(long projectId, String[] statuses) {
        List<HermesAgentRunRecord> rows = new ArrayList<>();
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT runs.* FROM ");
        sql.append(DatabaseHelper.TABLE_HERMES_AGENT_RUNS);
        sql.append(" runs INNER JOIN ");
        sql.append(DatabaseHelper.TABLE_HERMES_EXECUTION_RUNS);
        sql.append(" executions ON runs.execution_run_id = executions.id WHERE executions.project_id = ?");
        List<String> args = new ArrayList<>();
        args.add(String.valueOf(projectId));
        if (statuses != null && statuses.length > 0) {
            sql.append(" AND runs.status IN (");
            for (int i = 0; i < statuses.length; i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                sql.append("?");
                args.add(statuses[i]);
            }
            sql.append(")");
        }
        sql.append(" ORDER BY runs.batch_index ASC, runs.agent_index ASC, runs.id ASC");
        try (Cursor cursor = helper.getReadableDatabase().rawQuery(sql.toString(), args.toArray(new String[0]))) {
            while (cursor.moveToNext()) {
                rows.add(readHermesAgentRun(cursor));
            }
        }
        return rows;
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private String cleanOrDefault(String value, String defaultValue) {
        String cleanValue = clean(value);
        return cleanValue.isEmpty() ? defaultValue : cleanValue;
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

    /** The full AI-conversation record (untruncated request/response), or null if it is gone. */
    public synchronized AiConversationRecord getAiConversation(long id) {
        try (Cursor cursor = helper.getReadableDatabase().query(DatabaseHelper.TABLE_AI_CONVERSATIONS, null, "id = ?", new String[]{String.valueOf(id)}, null, null, null)) {
            if (!cursor.moveToFirst()) {
                return null;
            }
            return readAiConversation(cursor);
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

    private AiConversationRecord readAiConversation(Cursor cursor) {
        int linkedIndex = cursor.getColumnIndexOrThrow("linked_build_job_id");
        Long linked = cursor.isNull(linkedIndex) ? null : cursor.getLong(linkedIndex);
        return new AiConversationRecord(
                cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                cursor.getLong(cursor.getColumnIndexOrThrow("project_id")),
                cursor.getString(cursor.getColumnIndexOrThrow("source")),
                cursor.getString(cursor.getColumnIndexOrThrow("title")),
                cursor.getString(cursor.getColumnIndexOrThrow("request_text")),
                cursor.getString(cursor.getColumnIndexOrThrow("response_text")),
                cursor.getString(cursor.getColumnIndexOrThrow("status")),
                cursor.getString(cursor.getColumnIndexOrThrow("metadata")),
                linked,
                cursor.getLong(cursor.getColumnIndexOrThrow("created_at"))
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
                cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                cursor.getLong(cursor.getColumnIndexOrThrow("updated_at"))
        );
    }

    private ProjectPlanRecord readProjectPlan(Cursor cursor) {
        int linkedIndex = cursor.getColumnIndexOrThrow("linked_build_job_id");
        Long linked = cursor.isNull(linkedIndex) ? null : cursor.getLong(linkedIndex);
        return new ProjectPlanRecord(
                cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                cursor.getLong(cursor.getColumnIndexOrThrow("project_id")),
                cursor.getString(cursor.getColumnIndexOrThrow("content")),
                cursor.getString(cursor.getColumnIndexOrThrow("status")),
                linked,
                cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                cursor.getLong(cursor.getColumnIndexOrThrow("updated_at"))
        );
    }

    private ProjectTaskRecord readProjectTask(Cursor cursor) {
        return new ProjectTaskRecord(
                cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                cursor.getLong(cursor.getColumnIndexOrThrow("project_id")),
                cursor.getInt(cursor.getColumnIndexOrThrow("sort_order")),
                cursor.getString(cursor.getColumnIndexOrThrow("title")),
                cursor.getString(cursor.getColumnIndexOrThrow("instruction")),
                cursor.getString(cursor.getColumnIndexOrThrow("status")),
                cursor.getString(cursor.getColumnIndexOrThrow("result_summary")),
                cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")),
                cursor.getLong(cursor.getColumnIndexOrThrow("started_at")),
                cursor.getLong(cursor.getColumnIndexOrThrow("completed_at"))
        );
    }

    private HermesExecutionRunRecord readHermesExecutionRun(Cursor cursor) {
        return new HermesExecutionRunRecord(
                cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                cursor.getLong(cursor.getColumnIndexOrThrow("project_id")),
                cursor.getLong(cursor.getColumnIndexOrThrow("build_job_id")),
                cursor.getString(cursor.getColumnIndexOrThrow("status")),
                cursor.getString(cursor.getColumnIndexOrThrow("mode")),
                cursor.getInt(cursor.getColumnIndexOrThrow("max_parallel")),
                cursor.getString(cursor.getColumnIndexOrThrow("base_source_hash")),
                cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                cursor.getLong(cursor.getColumnIndexOrThrow("updated_at"))
        );
    }

    private HermesAgentRunRecord readHermesAgentRun(Cursor cursor) {
        return new HermesAgentRunRecord(
                cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                cursor.getLong(cursor.getColumnIndexOrThrow("execution_run_id")),
                cursor.getLong(cursor.getColumnIndexOrThrow("project_task_id")),
                cursor.getInt(cursor.getColumnIndexOrThrow("batch_index")),
                cursor.getInt(cursor.getColumnIndexOrThrow("agent_index")),
                cursor.getString(cursor.getColumnIndexOrThrow("status")),
                cursor.getString(cursor.getColumnIndexOrThrow("work_dir")),
                cursor.getString(cursor.getColumnIndexOrThrow("base_source_hash")),
                cursor.getString(cursor.getColumnIndexOrThrow("merged_source_hash")),
                cursor.getString(cursor.getColumnIndexOrThrow("locked_paths_json")),
                cursor.getString(cursor.getColumnIndexOrThrow("summary")),
                cursor.getString(cursor.getColumnIndexOrThrow("error_summary")),
                cursor.getLong(cursor.getColumnIndexOrThrow("started_at")),
                cursor.getLong(cursor.getColumnIndexOrThrow("completed_at"))
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
