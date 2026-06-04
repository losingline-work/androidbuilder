package com.androidbuilder.agent;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class VersionUpgradePolicyTest {
    @Test
    public void promptRequiresVersionCodeAndVersionNameUpdatesWhenRequested() {
        String prompt = VersionUpgradePolicy.prompt();

        assertTrue(prompt.contains("versionCode"));
        assertTrue(prompt.contains("versionName"));
        assertTrue(prompt.contains("greater than the current"));
        assertTrue(prompt.contains("requested version"));
        assertTrue(prompt.contains("Do not downgrade"));
    }

    @Test
    public void codingPromptsIncludeVersionUpgradeRule() {
        String rule = VersionUpgradePolicy.prompt();

        assertTrue(OpenAiClient.projectFilesSystemPromptForTest(false).contains(rule));
        assertTrue(OpenAiClient.taskOperationsSystemPromptForTest(false).contains(rule));
    }

    @Test
    public void planningPromptIncludesVersionUpgradeRule() {
        assertTrue(OpenAiClient.planSystemPromptForTest(false).contains("Version upgrade rule"));
        assertTrue(OpenAiClient.planSystemPromptForTest(true).contains("versionCode"));
    }
}
