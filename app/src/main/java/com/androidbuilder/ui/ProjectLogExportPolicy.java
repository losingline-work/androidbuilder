package com.androidbuilder.ui;

import com.androidbuilder.model.BuildJobRecord;
import com.androidbuilder.model.ProjectLogEntry;

import java.io.File;
import java.util.List;

final class ProjectLogExportPolicy {
    private ProjectLogExportPolicy() {
    }

    static boolean canExportBuildLog(BuildJobRecord job) {
        if (job == null || job.logsPath == null || job.logsPath.trim().isEmpty()) {
            return false;
        }
        File log = new File(job.logsPath);
        return log.exists() && log.isFile();
    }

    static String buildLogExportName(BuildJobRecord job) {
        long projectId = job == null ? 0 : job.projectId;
        long jobId = job == null ? 0 : job.id;
        return "androidbuilder-project-" + projectId + "-job-" + jobId + "-build.log";
    }

    static String projectLogExportName(long projectId) {
        return "androidbuilder-project-" + projectId + "-logs.txt";
    }

    static String exportMimeType(String name) {
        return "text/plain";
    }

    static String projectLogsExportText(List<ProjectLogEntry> entries) {
        return projectLogsExportText(entries, false, "");
    }

    static String projectLogsExportText(List<ProjectLogEntry> entries, boolean chinese) {
        return projectLogsExportText(entries, chinese, "");
    }

    static String projectLogsExportText(List<ProjectLogEntry> entries, boolean chinese, String buildStamp) {
        int count = entries == null ? 0 : entries.size();
        StringBuilder text = new StringBuilder(chinese ? "app 制造机项目日志\n记录数：" : "Android Builder Project Logs\nEntries: ")
                .append(count);
        if (buildStamp != null && !buildStamp.trim().isEmpty()) {
            text.append(chinese ? "\n构建版本：" : "\nBuild: ").append(buildStamp.trim());
        }
        if (entries == null) {
            return text.toString();
        }
        for (ProjectLogEntry entry : entries) {
            if (entry == null) {
                continue;
            }
            text.append("\n\n---\n")
                    .append(entry.kind.name())
                    .append(" #")
                    .append(entry.sourceId)
                    .append("\n")
                    .append(entry.title)
                    .append("\n")
                    .append(entry.subtitle)
                    .append("\n\n")
                    .append(entry.copyText == null || entry.copyText.trim().isEmpty() ? entry.body : entry.copyText);
        }
        return text.toString();
    }
}
