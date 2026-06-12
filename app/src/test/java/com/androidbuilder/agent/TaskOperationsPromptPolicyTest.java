package com.androidbuilder.agent;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class TaskOperationsPromptPolicyTest {
    @Test
    public void taskOperationsPromptRejectsEmptyOperations() {
        String prompt = OpenAiClient.taskOperationsSystemPromptForTest(false);

        assertTrue(prompt.contains("Do not return an empty operations array"));
        assertTrue(prompt.contains("at least one write or delete"));
    }

    @Test
    public void taskOperationsPromptOffersBlockedExitForMissingPrerequisites() {
        String prompt = OpenAiClient.taskOperationsSystemPromptForTest(false);

        assertTrue(prompt.contains("blocked"));
        assertTrue(prompt.contains("blockedReason"));
        assertTrue(prompt.contains("prerequisiteWork"));
        assertTrue(prompt.contains("missing prerequisite"));
    }

    @Test
    public void promptsAskForSmallFocusedFiles() {
        assertTrue(OpenAiClient.taskOperationsSystemPromptForTest(false).contains("under about 250 lines"));
        assertTrue(OpenAiClient.projectFilesSystemPromptForTest(false).contains("under about 250 lines"));
    }

    @Test
    public void taskOperationsPromptAllowsSmallBatchesForCoarseResourcePhases() {
        String prompt = OpenAiClient.taskOperationsSystemPromptForTest(false);

        assertTrue(prompt.contains("Simple tasks should still prefer one or two file operations"));
        assertTrue(prompt.contains("resource or layout phase may return a small cohesive batch"));
    }

    @Test
    public void promptsCallOutCommonGeneratedJavaApiMismatches() {
        String projectPrompt = OpenAiClient.projectFilesSystemPromptForTest(false);
        String taskPrompt = OpenAiClient.taskOperationsSystemPromptForTest(false);

        assertTrue(projectPrompt.contains("item.categoryName"));
        assertTrue(projectPrompt.contains("new CategoryDAO(dbHelper)"));
        assertTrue(taskPrompt.contains("item.percent"));
        assertTrue(taskPrompt.contains("CategoryDAO(Context)"));
    }

    @Test
    public void promptsRejectDependencyProvidedAppbarBehaviorResources() {
        String projectPrompt = OpenAiClient.projectFilesSystemPromptForTest(false);
        String taskPrompt = OpenAiClient.taskOperationsSystemPromptForTest(false);

        assertTrue(projectPrompt.contains("appbar_scrolling_view_behavior"));
        assertTrue(projectPrompt.contains("CoordinatorLayout"));
        assertTrue(taskPrompt.contains("appbar_scrolling_view_behavior"));
        assertTrue(taskPrompt.contains("LinearLayout"));
    }

    @Test
    public void promptsRequireDatabaseLayerContractsToStaySynchronized() {
        String projectPrompt = OpenAiClient.projectFilesSystemPromptForTest(false);
        String taskPrompt = OpenAiClient.taskOperationsSystemPromptForTest(false);

        assertTrue(projectPrompt.contains("DBHelper.COL_"));
        assertTrue(projectPrompt.contains("DAO method"));
        assertTrue(projectPrompt.contains("RecordDao.listAll()"));
        assertTrue(taskPrompt.contains("DBHelper.COL_"));
        assertTrue(taskPrompt.contains("update(Record)"));
        assertTrue(taskPrompt.contains("RecordDao.listAll()"));
    }
}
