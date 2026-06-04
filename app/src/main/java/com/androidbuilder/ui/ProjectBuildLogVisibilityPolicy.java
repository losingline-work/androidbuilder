package com.androidbuilder.ui;

import com.androidbuilder.model.BuildJobRecord;

import java.util.Locale;

public final class ProjectBuildLogVisibilityPolicy {
    private ProjectBuildLogVisibilityPolicy() {
    }

    public static boolean shouldShow(BuildJobRecord job, String messageContent) {
        if (job == null) {
            return false;
        }
        String phase = job.phase == null ? "" : job.phase.toLowerCase(Locale.ROOT);
        String status = job.status == null ? "" : job.status.toLowerCase(Locale.ROOT);
        String content = messageContent == null ? "" : messageContent;
        if ("finished".equals(phase) && ("success".equals(status) || "failed".equals(status) || "built".equals(status))) {
            return true;
        }
        if (content.contains("构建完成") || content.contains("Build complete") || content.contains("Build result")) {
            return true;
        }
        if (job.logsPath == null || job.logsPath.trim().isEmpty()) {
            return false;
        }
        if (phase.contains("embedded") || phase.contains("termux") || phase.contains("artifact_received") || phase.contains("repair")) {
            return true;
        }
        return content.contains("正在根据构建日志修复") ||
                content.contains("已完成构建修复") ||
                content.contains("修复失败") ||
                content.contains("Repairing the current source from the build log") ||
                content.contains("Build repair complete") ||
                content.contains("Repair failed");
    }
}
