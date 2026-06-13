package com.androidbuilder.agent;

import com.androidbuilder.model.TaskManifest;

import java.util.List;

/**
 * Human-readable narration of what a task is doing, written to the job log so the build-log card
 * surfaces a plain-language trail ("planning files", "writing batch 3/8: ...", "reviewing",
 * "merging") instead of only technical records.
 */
public final class BatchNarrationPolicy {
    private static final int MAX_SUMMARY_CHARS = 200;
    private static final int MAX_NAMED_FILES = 6;

    private BatchNarrationPolicy() {
    }

    public static String manifestLine(String summary, int fileCount, int batchCount, boolean chinese) {
        StringBuilder line = new StringBuilder(chinese ? "📋 准备文件清单：" : "📋 Planning files: ");
        line.append(chinese ? fileCount + " 个文件，分 " + batchCount + " 批" : fileCount + " file(s) in " + batchCount + " batch(es)");
        String trimmed = compact(summary);
        if (!trimmed.isEmpty()) {
            line.append(chinese ? "。本步要做：" : ". Goal: ").append(trimmed);
        }
        return line.append('\n').toString();
    }

    public static String batchLine(int batchNumber, int batchTotal, List<TaskManifest.Entry> batch, boolean chinese) {
        String files = fileNames(batch);
        StringBuilder line = new StringBuilder(chinese ? "✍️ 生成第 " : "✍️ Writing batch ");
        line.append(batchNumber).append('/').append(batchTotal);
        if (chinese) {
            line.append(" 批");
        }
        if (!files.isEmpty()) {
            line.append(chinese ? "：" : ": ").append(files);
        }
        return line.append('\n').toString();
    }

    public static String reviewingLine(boolean chinese) {
        return (chinese ? "🔍 审查生成的代码" : "🔍 Reviewing generated code") + "\n";
    }

    public static String mergingLine(boolean chinese) {
        return (chinese ? "🔗 合并到项目并校验" : "🔗 Merging into the project") + "\n";
    }

    private static String fileNames(List<TaskManifest.Entry> batch) {
        if (batch == null || batch.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        int shown = 0;
        for (TaskManifest.Entry entry : batch) {
            if (entry == null || entry.path == null) {
                continue;
            }
            if (shown >= MAX_NAMED_FILES) {
                builder.append(builder.length() == 0 ? "" : "、").append("…");
                break;
            }
            if (builder.length() > 0) {
                builder.append('、');
            }
            builder.append(baseName(entry.path));
            shown++;
        }
        return builder.toString();
    }

    private static String baseName(String path) {
        String value = path;
        int slash = value.lastIndexOf('/');
        if (slash >= 0) {
            value = value.substring(slash + 1);
        }
        return value;
    }

    private static String compact(String value) {
        String text = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        if (text.length() <= MAX_SUMMARY_CHARS) {
            return text;
        }
        return text.substring(0, MAX_SUMMARY_CHARS) + "…";
    }
}
