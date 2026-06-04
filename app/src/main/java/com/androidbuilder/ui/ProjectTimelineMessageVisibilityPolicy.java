package com.androidbuilder.ui;

/**
 * Decides whether a chat message is redundant "status chatter" that the timeline can hide,
 * because the same information is already shown by the TASK rows or the BUILD_LOG row.
 *
 * <p>This is a display-only filter: the message stays in the database, it is just not rendered
 * as its own row. Substantive messages (user prompts, plan content, generated-source notes,
 * capability assessment, and every error/failure notice) are always kept.
 */
public final class ProjectTimelineMessageVisibilityPolicy {
    private ProjectTimelineMessageVisibilityPolicy() {
    }

    public static boolean isChatter(String role, String content) {
        if (role == null || !"assistant".equals(role)) {
            return false;
        }
        String text = content == null ? "" : content;

        // KEEP-wins: failure notices look superficially similar to hidden status chatter,
        // so exclude them before the generic matches below.
        if (text.contains("Repair failed") || text.contains("修复失败") ||
                text.contains("Build complete: failed") || text.contains("构建完成：失败")) {
            return false;
        }

        // Build start / success chatter — the BUILD_LOG row already shows running/success state.
        if (text.contains("Embedded build started") || text.contains("Termux build started") ||
                text.contains("已启动内置构建") || text.contains("已启动 Termux 构建") ||
                text.contains("Build complete: success") || text.contains("构建完成：成功")) {
            return true;
        }

        // Repair start / completion chatter — the REPAIR_RECORD row carries the state and log.
        if (text.contains("Repairing the current source") || text.contains("正在根据构建日志修复") ||
                text.contains("Build repair complete") || text.contains("已完成构建修复")) {
            return true;
        }

        // Per-task execute chatter — the TASK rows already show pending/running/done per task.
        if (text.contains("Executing next step:") || text.contains("执行下一步：") ||
                text.contains("Done: ") || text.contains("已完成：") ||
                text.contains("All plan tasks are done") || text.contains("所有计划任务已完成") ||
                text.contains("Split into implementation tasks") || text.contains("已拆分为执行任务")) {
            return true;
        }

        return false;
    }
}
