package com.androidbuilder.agent;

import com.androidbuilder.model.TaskManifest;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TaskOperationsPromptPolicyTest {
    @Test
    public void taskOperationsPromptUsesFencedFormatWithWorkedExample() {
        String prompt = OpenAiClient.taskOperationsSystemPromptForTest(false);

        // Fenced raw-file protocol (NOT JSON) with the markers and a worked example.
        assertTrue(prompt.contains("FENCED"));
        assertTrue(prompt.contains("===FILE"));
        assertTrue(prompt.contains("===END==="));
        assertTrue(prompt.contains("===EDIT"));
        assertTrue(prompt.contains("===DELETE"));
        assertTrue(prompt.contains("raw with NO escaping"));
        // The worked example is present so weak models see a concrete valid reply.
        assertTrue(prompt.contains("Worked example"));
        assertTrue(prompt.contains("Add home strings and wire the title"));
        // The JSON contract language is gone from the ops prompt.
        assertFalse(prompt.contains("compact JSON with summary and operations"));
    }

    @Test
    public void taskOperationsPromptRejectsEmptyReply() {
        String prompt = OpenAiClient.taskOperationsSystemPromptForTest(false);

        assertTrue(prompt.contains("Do not return an empty reply"));
        assertTrue(prompt.contains("at least one ===FILE=== or ===DELETE=== block"));
    }

    @Test
    public void taskOperationsPromptOffersBlockedExitForMissingPrerequisites() {
        String prompt = OpenAiClient.taskOperationsSystemPromptForTest(false);

        assertTrue(prompt.contains("===BLOCKED==="));
        assertTrue(prompt.contains("===PREREQ==="));
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
    public void singleShotUserPromptEndsWithDeliverableReminder() {
        String prompt = OpenAiClient.taskOperationsUserPromptForTest(
                "plan", "Add HomeFragment", "Create app/src/main/java/com/x/HomeFragment.java",
                "current tree here", "", "");
        int instructionIdx = prompt.indexOf("Add HomeFragment");
        int reminderIdx = prompt.indexOf("FINAL REMINDER");
        // The reminder must exist and be the LAST thing the model reads (recency bias).
        assertTrue(reminderIdx > instructionIdx);
        assertTrue(prompt.contains("Every named deliverable file"));
        assertTrue(prompt.trim().endsWith("write or edit operation."));
    }

    @Test
    public void batchUserPromptRepeatsPathsAfterFrozenApiContext() {
        java.util.List<TaskManifest.Entry> batch = java.util.Arrays.asList(
                new TaskManifest.Entry("app/src/main/res/layout/activity_main.xml", "write", "home screen"),
                new TaskManifest.Entry("app/src/main/res/values/strings.xml", "write", "labels"));
        String prompt = OpenAiClient.taskOperationsBatchUserPromptForTest(
                "plan", "UI", "build the UI", "tree", "", "",
                batch, "FROZEN: SomeClass.someMethod()");
        int frozenIdx = prompt.indexOf("FROZEN: SomeClass.someMethod()");
        int reminderIdx = prompt.indexOf("FINAL REMINDER — produce one complete file operation");
        int pathIdx = prompt.lastIndexOf("app/src/main/res/layout/activity_main.xml");
        // The path list is repeated as the last thing, AFTER the (potentially huge) frozen-API context.
        assertTrue(reminderIdx > frozenIdx);
        assertTrue(pathIdx > frozenIdx);
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
