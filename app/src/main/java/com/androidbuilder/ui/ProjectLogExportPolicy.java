package com.androidbuilder.ui;

import com.androidbuilder.model.BuildJobRecord;
import com.androidbuilder.model.ProjectLogEntry;

import java.io.File;
import java.io.IOException;
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
        StringBuilder text = new StringBuilder();
        try {
            writeProjectLogs(text, entries, chinese, buildStamp);
        } catch (IOException ignored) {
            // StringBuilder never throws; the checked signature is for the streaming file case.
        }
        return text.toString();
    }

    /**
     * Streams the export entry-by-entry into {@code out} instead of building the whole log in a
     * single in-memory String. A large project's logs (hundreds of AI records, each carrying a full
     * request/response body) total tens of megabytes; materializing them as one String OOM-crashed
     * the app. Peak memory here is bounded by a single entry, not the sum.
     */
    static void writeProjectLogs(Appendable out, List<ProjectLogEntry> entries, boolean chinese, String buildStamp) throws IOException {
        int count = entries == null ? 0 : entries.size();
        out.append(chinese ? "app 制造机项目日志\n记录数：" : "Android Builder Project Logs\nEntries: ")
                .append(Integer.toString(count));
        if (buildStamp != null && !buildStamp.trim().isEmpty()) {
            out.append(chinese ? "\n构建版本：" : "\nBuild: ").append(buildStamp.trim());
        }
        if (entries == null) {
            return;
        }
        for (ProjectLogEntry entry : entries) {
            if (entry == null) {
                continue;
            }
            out.append("\n\n---\n")
                    .append(entry.kind.name())
                    .append(" #")
                    .append(Long.toString(entry.sourceId))
                    .append("\n")
                    .append(nullToEmpty(entry.title))
                    .append("\n")
                    .append(nullToEmpty(entry.subtitle))
                    .append("\n\n")
                    .append(entry.copyText == null || entry.copyText.trim().isEmpty()
                            ? nullToEmpty(entry.body) : entry.copyText);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
