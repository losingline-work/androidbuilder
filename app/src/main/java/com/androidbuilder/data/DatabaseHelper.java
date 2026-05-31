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

    private static final String DB_NAME = "android_builder.db";
    private static final int DB_VERSION = 4;

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
}
