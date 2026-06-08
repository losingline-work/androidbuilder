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
        // Canonical rules live in the agent layer so timeline filtering and cloud-history pruning
        // never drift apart.
        return com.androidbuilder.agent.ConversationContextPolicy.isStatusChatter(role, content);
    }
}
