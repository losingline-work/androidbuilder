package com.androidbuilder.data;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public class DatabaseHelper extends SQLiteOpenHelper {
    static final String TABLE_PROJECTS = "projects";
    static final String TABLE_MESSAGES = "messages";
    static final String TABLE_BUILD_JOBS = "build_jobs";
    static final String TABLE_ARTIFACTS = "artifacts";

    private static final String DB_NAME = "android_builder.db";
    private static final int DB_VERSION = 1;

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
        db.execSQL("CREATE INDEX idx_messages_project ON messages(project_id, created_at)");
        db.execSQL("CREATE INDEX idx_jobs_project ON build_jobs(project_id, created_at)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS artifacts");
        db.execSQL("DROP TABLE IF EXISTS build_jobs");
        db.execSQL("DROP TABLE IF EXISTS messages");
        db.execSQL("DROP TABLE IF EXISTS projects");
        onCreate(db);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
    }
}
