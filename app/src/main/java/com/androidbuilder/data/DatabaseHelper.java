package com.androidbuilder.data;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public class DatabaseHelper extends SQLiteOpenHelper {
    static final String TABLE_PROJECTS = "projects";
    static final String TABLE_MESSAGES = "messages";
    static final String TABLE_BUILD_JOBS = "build_jobs";
    static final String TABLE_ARTIFACTS = "artifacts";
    static final String TABLE_PROJECT_PLANS = "project_plans";
    static final String TABLE_PROJECT_TASKS = "project_tasks";
    static final String TABLE_PROJECT_MILESTONES = "project_milestones";
    static final String TABLE_AI_CONVERSATIONS = "ai_conversations";
    static final String TABLE_HERMES_EXECUTION_RUNS = "hermes_execution_runs";
    static final String TABLE_HERMES_AGENT_RUNS = "hermes_agent_runs";

    private static final String DB_NAME = "android_builder.db";
    private static final int DB_VERSION = 9;

    public DatabaseHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE projects (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT NOT NULL," +
                "package_name TEXT NOT NULL," +
                "description TEXT NOT NULL DEFAULT ''," +
                "created_at INTEGER NOT NULL," +
                "updated_at INTEGER NOT NULL," +
                "last_build_status TEXT NOT NULL DEFAULT 'idle'" +
                ")");
        db.execSQL("CREATE TABLE messages (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "project_id INTEGER NOT NULL," +
                "role TEXT NOT NULL," +
                "content TEXT NOT NULL," +
                "created_at INTEGER NOT NULL," +
                "linked_build_job_id INTEGER," +
                "FOREIGN KEY(project_id) REFERENCES projects(id) ON DELETE CASCADE" +
                ")");
        db.execSQL("CREATE TABLE build_jobs (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "project_id INTEGER NOT NULL," +
                "status TEXT NOT NULL," +
                "phase TEXT NOT NULL," +
                "logs_path TEXT," +
                "apk_path TEXT," +
                "error_summary TEXT," +
                "retry_count INTEGER NOT NULL DEFAULT 0," +
                "created_at INTEGER NOT NULL," +
                "updated_at INTEGER NOT NULL," +
                "FOREIGN KEY(project_id) REFERENCES projects(id) ON DELETE CASCADE" +
                ")");
        db.execSQL("CREATE TABLE artifacts (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "project_id INTEGER NOT NULL," +
                "build_job_id INTEGER NOT NULL," +
                "type TEXT NOT NULL," +
                "path TEXT NOT NULL," +
                "created_at INTEGER NOT NULL," +
                "FOREIGN KEY(project_id) REFERENCES projects(id) ON DELETE CASCADE," +
                "FOREIGN KEY(build_job_id) REFERENCES build_jobs(id) ON DELETE CASCADE" +
                ")");
        createProjectPlansTable(db);
        createProjectTasksTable(db);
        createProjectMilestonesTable(db);
        createAiConversationsTable(db);
        createHermesExecutionRunsTable(db);
        createHermesAgentRunsTable(db);
        db.execSQL("CREATE INDEX idx_messages_project ON messages(project_id, created_at)");
        db.execSQL("CREATE INDEX idx_jobs_project ON build_jobs(project_id, created_at)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            createProjectPlansTable(db);
        }
        if (oldVersion < 3) {
            createProjectTasksTable(db);
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE build_jobs ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0");
            db.execSQL("UPDATE build_jobs SET updated_at = created_at WHERE updated_at = 0");
            if (oldVersion >= 3) {
                db.execSQL("ALTER TABLE project_tasks ADD COLUMN started_at INTEGER NOT NULL DEFAULT 0");
                db.execSQL("ALTER TABLE project_tasks ADD COLUMN completed_at INTEGER NOT NULL DEFAULT 0");
            }
        }
        if (oldVersion < 5) {
            createAiConversationsTable(db);
        }
        if (oldVersion < 6) {
            createHermesExecutionRunsTable(db);
            createHermesAgentRunsTable(db);
        }
        if (oldVersion < 7) {
            createProjectMilestonesTable(db);
        }
        if (oldVersion < 8) {
            db.execSQL("ALTER TABLE project_milestones ADD COLUMN tasks_json TEXT NOT NULL DEFAULT ''");
        }
        if (oldVersion < 9) {
            db.execSQL("ALTER TABLE project_milestones ADD COLUMN simplify_attempts INTEGER NOT NULL DEFAULT 0");
        }
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
    }

    private void createProjectPlansTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS project_plans (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "project_id INTEGER NOT NULL," +
                "content TEXT NOT NULL DEFAULT ''," +
                "status TEXT NOT NULL DEFAULT 'idle'," +
                "linked_build_job_id INTEGER," +
                "created_at INTEGER NOT NULL," +
                "updated_at INTEGER NOT NULL," +
                "FOREIGN KEY(project_id) REFERENCES projects(id) ON DELETE CASCADE," +
                "FOREIGN KEY(linked_build_job_id) REFERENCES build_jobs(id) ON DELETE SET NULL" +
                ")");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_plans_project ON project_plans(project_id, updated_at)");
    }

    private void createProjectTasksTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS project_tasks (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "project_id INTEGER NOT NULL," +
                "sort_order INTEGER NOT NULL," +
                "title TEXT NOT NULL," +
                "instruction TEXT NOT NULL," +
                "status TEXT NOT NULL DEFAULT 'pending'," +
                "result_summary TEXT NOT NULL DEFAULT ''," +
                "created_at INTEGER NOT NULL," +
                "updated_at INTEGER NOT NULL," +
                "started_at INTEGER NOT NULL DEFAULT 0," +
                "completed_at INTEGER NOT NULL DEFAULT 0," +
                "FOREIGN KEY(project_id) REFERENCES projects(id) ON DELETE CASCADE" +
                ")");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tasks_project ON project_tasks(project_id, sort_order)");
    }

    private void createProjectMilestonesTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS project_milestones (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "project_id INTEGER NOT NULL," +
                "order_index INTEGER NOT NULL," +
                "title TEXT NOT NULL," +
                "description TEXT NOT NULL DEFAULT ''," +
                "slice TEXT NOT NULL DEFAULT ''," +
                "status TEXT NOT NULL DEFAULT 'pending'," +
                "checkpoint_path TEXT NOT NULL DEFAULT ''," +
                "build_job_id INTEGER NOT NULL DEFAULT 0," +
                "repair_rounds INTEGER NOT NULL DEFAULT 0," +
                "tasks_json TEXT NOT NULL DEFAULT ''," +
                "simplify_attempts INTEGER NOT NULL DEFAULT 0," +
                "created_at INTEGER NOT NULL," +
                "updated_at INTEGER NOT NULL," +
                "FOREIGN KEY(project_id) REFERENCES projects(id) ON DELETE CASCADE" +
                ")");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_milestones_project ON project_milestones(project_id, order_index)");
    }

    private void createAiConversationsTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS ai_conversations (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "project_id INTEGER NOT NULL," +
                "source TEXT NOT NULL," +
                "title TEXT NOT NULL DEFAULT ''," +
                "request_text TEXT NOT NULL DEFAULT ''," +
                "response_text TEXT NOT NULL DEFAULT ''," +
                "status TEXT NOT NULL DEFAULT ''," +
                "metadata TEXT NOT NULL DEFAULT ''," +
                "linked_build_job_id INTEGER," +
                "created_at INTEGER NOT NULL," +
                "FOREIGN KEY(project_id) REFERENCES projects(id) ON DELETE CASCADE," +
                "FOREIGN KEY(linked_build_job_id) REFERENCES build_jobs(id) ON DELETE SET NULL" +
                ")");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_ai_conversations_project ON ai_conversations(project_id, created_at)");
    }

    private void createHermesExecutionRunsTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS hermes_execution_runs (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "project_id INTEGER NOT NULL," +
                "build_job_id INTEGER NOT NULL," +
                "status TEXT NOT NULL DEFAULT 'running'," +
                "mode TEXT NOT NULL DEFAULT 'parallel'," +
                "max_parallel INTEGER NOT NULL DEFAULT 1," +
                "base_source_hash TEXT NOT NULL DEFAULT ''," +
                "created_at INTEGER NOT NULL," +
                "updated_at INTEGER NOT NULL," +
                "FOREIGN KEY(project_id) REFERENCES projects(id) ON DELETE CASCADE," +
                "FOREIGN KEY(build_job_id) REFERENCES build_jobs(id) ON DELETE CASCADE" +
                ")");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_hermes_execution_runs_project ON hermes_execution_runs(project_id, status, created_at)");
    }

    private void createHermesAgentRunsTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS hermes_agent_runs (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "execution_run_id INTEGER NOT NULL," +
                "project_task_id INTEGER NOT NULL," +
                "batch_index INTEGER NOT NULL," +
                "agent_index INTEGER NOT NULL," +
                "status TEXT NOT NULL DEFAULT 'pending'," +
                "work_dir TEXT NOT NULL DEFAULT ''," +
                "base_source_hash TEXT NOT NULL DEFAULT ''," +
                "merged_source_hash TEXT NOT NULL DEFAULT ''," +
                "locked_paths_json TEXT NOT NULL DEFAULT '[]'," +
                "summary TEXT NOT NULL DEFAULT ''," +
                "error_summary TEXT NOT NULL DEFAULT ''," +
                "started_at INTEGER NOT NULL DEFAULT 0," +
                "completed_at INTEGER NOT NULL DEFAULT 0," +
                "FOREIGN KEY(execution_run_id) REFERENCES hermes_execution_runs(id) ON DELETE CASCADE," +
                "FOREIGN KEY(project_task_id) REFERENCES project_tasks(id) ON DELETE CASCADE" +
                ")");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_hermes_agent_runs_execution ON hermes_agent_runs(execution_run_id, batch_index, agent_index)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_hermes_agent_runs_task ON hermes_agent_runs(project_task_id, status)");
    }
}
