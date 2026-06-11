package com.androidbuilder.agent;

import com.androidbuilder.model.ChatMessage;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ConversationContextPolicyTest {
    private static ChatMessage msg(String role, String content, long createdAt) {
        return new ChatMessage(createdAt, 1, role, content, createdAt, null);
    }

    @Test
    public void isStatusChatterMatchesTimelinePolicyRules() {
        assertTrue(ConversationContextPolicy.isStatusChatter("assistant", "Executing next step: build login"));
        assertTrue(ConversationContextPolicy.isStatusChatter("assistant", "Executing next parallel batch: A, B"));
        assertTrue(ConversationContextPolicy.isStatusChatter("assistant", "并行执行下一批：A、B"));
        assertTrue(ConversationContextPolicy.isStatusChatter("assistant", "构建完成：成功，APK 已生成。"));
        assertFalse(ConversationContextPolicy.isStatusChatter("assistant", "Build complete: failed. javac error"));
        assertFalse(ConversationContextPolicy.isStatusChatter("user", "Executing next step: anything I typed"));
        assertFalse(ConversationContextPolicy.isStatusChatter("assistant", "# Engineering Plan\n- screen"));
    }

    @Test
    public void planningHistoryDropsChatterAndWindows() {
        List<ChatMessage> all = new ArrayList<>(Arrays.asList(
                msg("user", "Build a budget app", 1),
                msg("assistant", "# Engineering Plan\n- screens", 2),
                msg("assistant", "Executing next step: A", 3),
                msg("assistant", "Done: A. Continue with the next step.", 4),
                msg("user", "Add a settings page", 5),
                msg("assistant", "Build complete: success. APK is ready.", 6)));

        List<ChatMessage> kept = ConversationContextPolicy.planningHistory(all, 16);

        assertEquals(3, kept.size());
        assertEquals("Build a budget app", kept.get(0).content);
        assertEquals("# Engineering Plan\n- screens", kept.get(1).content);
        assertEquals("Add a settings page", kept.get(2).content);
    }

    @Test
    public void planningHistoryKeepsOnlyMostRecentWindow() {
        List<ChatMessage> all = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            all.add(msg("user", "req " + i, i));
        }

        List<ChatMessage> kept = ConversationContextPolicy.planningHistory(all, 3);

        assertEquals(3, kept.size());
        assertEquals("req 8", kept.get(0).content);
        assertEquals("req 10", kept.get(2).content);
    }

    @Test
    public void recentUserRequirementsCollectsRecentUserMessages() {
        List<ChatMessage> all = Arrays.asList(
                msg("user", "Build a budget app", 1),
                msg("assistant", "# plan", 2),
                msg("user", "Use a dark theme", 3),
                msg("assistant", "Executing next step: A", 4),
                msg("user", "Add CSV export", 5));

        String requirements = ConversationContextPolicy.recentUserRequirements(all, 6, 1500);

        assertTrue(requirements.contains("Build a budget app"));
        assertTrue(requirements.contains("Use a dark theme"));
        assertTrue(requirements.contains("Add CSV export"));
        assertFalse(requirements.contains("# plan"));
        assertFalse(requirements.contains("Executing next step"));
    }

    @Test
    public void recentUserRequirementsEmptyWhenNoUserMessages() {
        List<ChatMessage> all = Arrays.asList(
                msg("assistant", "# plan", 1),
                msg("assistant", "Done: A.", 2));

        assertEquals("", ConversationContextPolicy.recentUserRequirements(all, 6, 1500));
        assertEquals("", ConversationContextPolicy.recentUserRequirements(null, 6, 1500));
    }
}
