package com.androidbuilder.agent;

import com.androidbuilder.model.ChatMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * Canonical detector for redundant assistant "status chatter" and helper for building the
 * conversation context sent to the cloud model.
 *
 * <p>The UI timeline visibility policy delegates to {@link #isStatusChatter(String, String)} so the
 * "what counts as chatter" rules live in exactly one place. The same predicate is reused to prune
 * the history we forward to the cloud planner, so the planner sees real requirements instead of
 * build/task status noise.
 */
public final class ConversationContextPolicy {
    private ConversationContextPolicy() {
    }

    public static boolean isStatusChatter(String role, String content) {
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
        // Build start / success chatter.
        if (text.contains("Embedded build started") || text.contains("Termux build started") ||
                text.contains("已启动内置构建") || text.contains("已启动 Termux 构建") ||
                text.contains("Build complete: success") || text.contains("构建完成：成功")) {
            return true;
        }
        // Repair start / completion chatter.
        if (text.contains("Repairing the current source") || text.contains("正在根据构建日志修复") ||
                text.contains("Build repair complete") || text.contains("已完成构建修复")) {
            return true;
        }
        // Per-task execute chatter.
        return text.contains("Executing next step:") || text.contains("执行下一步：") ||
                text.contains("Done: ") || text.contains("已完成：") ||
                text.contains("All plan tasks are done") || text.contains("所有计划任务已完成") ||
                text.contains("Split into implementation tasks") || text.contains("已拆分为执行任务");
    }

    /**
     * History for the cloud planner: drop status chatter and keep only the most recent
     * {@code maxMessages} entries so long projects do not bloat or dilute the prompt.
     */
    public static List<ChatMessage> planningHistory(List<ChatMessage> all, int maxMessages) {
        List<ChatMessage> kept = new ArrayList<>();
        if (all == null) {
            return kept;
        }
        for (ChatMessage message : all) {
            if (message == null || isStatusChatter(message.role, message.content)) {
                continue;
            }
            kept.add(message);
        }
        if (maxMessages > 0 && kept.size() > maxMessages) {
            return new ArrayList<>(kept.subList(kept.size() - maxMessages, kept.size()));
        }
        return kept;
    }

    /**
     * The user's recent stated requirements/clarifications, oldest-to-newest within the window,
     * capped to {@code maxChars}. Gives the coding/repair phase the intent that lives in chat but
     * may never have been folded into the approved plan text.
     */
    public static String recentUserRequirements(List<ChatMessage> all, int maxMessages, int maxChars) {
        if (all == null || all.isEmpty()) {
            return "";
        }
        List<String> userMessages = new ArrayList<>();
        for (ChatMessage message : all) {
            if (message != null && "user".equals(message.role)
                    && message.content != null && !message.content.trim().isEmpty()) {
                userMessages.add(message.content.trim());
            }
        }
        if (userMessages.isEmpty()) {
            return "";
        }
        int from = maxMessages > 0 && userMessages.size() > maxMessages ? userMessages.size() - maxMessages : 0;
        StringBuilder builder = new StringBuilder();
        for (int i = from; i < userMessages.size(); i++) {
            if (builder.length() > 0) {
                builder.append("\n");
            }
            builder.append("- ").append(userMessages.get(i));
        }
        String text = builder.toString();
        if (maxChars > 0 && text.length() > maxChars) {
            text = text.substring(0, maxChars) + "\n...[truncated]";
        }
        return text;
    }
}
