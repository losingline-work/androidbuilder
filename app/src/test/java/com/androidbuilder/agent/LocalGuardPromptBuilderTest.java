package com.androidbuilder.agent;

import com.androidbuilder.model.FileOperation;
import com.androidbuilder.model.TaskOperations;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LocalGuardPromptBuilderTest {
    @Test
    public void preflightPromptCallsOutDaoListAllAndBackupCallers() {
        TaskOperations operations = new TaskOperations(
                "Add backup",
                Collections.singletonList(new FileOperation(
                        "write",
                        "app/src/main/java/com/example/JsonBackup.java",
                        "class JsonBackup { void export(RecordDao dao) { dao.listAll(); } }")));

        String prompt = LocalGuardPromptBuilder.reviewOperationsPrompt(
                "# Plan",
                "Backup",
                "Add JSON backup.",
                "class RecordDao { long insert(Record record) { return 1; } }",
                operations);

        assertTrue(prompt.contains("RecordDao.listAll()"));
        assertTrue(prompt.contains("JsonBackup.java"));
        assertTrue(prompt.contains("DAO method"));
        assertTrue(prompt.contains("existing DAO method"));
    }

    @Test
    public void triagePromptKeepsLogTailAndAsksForFocusedFix() {
        StringBuilder log = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            log.append("> Task :app:earlyNoise ").append(i).append('\n');
        }
        log.append("e: CategoryManageActivity.java:42: error: cannot find symbol method listAll()\n");
        log.append("BUILD FAILED in 12s\n");

        String prompt = LocalGuardPromptBuilder.triageBuildFailurePrompt(
                log.toString(),
                "--- CategoryDao.java ---\nclass CategoryDao { long insert(Category c) { return 1; } }");

        // The tail (real error + BUILD FAILED) is retained, the early noise is dropped.
        assertTrue(prompt.contains("BUILD FAILED"));
        assertTrue(prompt.contains("cannot find symbol"));
        assertFalse(prompt.contains("earlyNoise 0"));
        // Contract + triage guidance present.
        assertTrue(prompt.contains("additionalInstruction"));
        assertTrue(prompt.contains("root cause"));
    }

    @Test
    public void preflightPromptForbidsLambdaSyntaxInAnyJavaFile() {
        TaskOperations operations = new TaskOperations(
                "Add date utils",
                Collections.singletonList(new FileOperation(
                        "write",
                        "app/src/main/java/com/example/DateUtils.java",
                        "class DateUtils { Runnable r = () -> {}; }")));

        String prompt = LocalGuardPromptBuilder.reviewOperationsPrompt(
                "# Plan",
                "Date utils",
                "Add date helpers.",
                "",
                operations);

        assertTrue(prompt.contains("DateUtils.java"));
        assertTrue(prompt.contains("->"));
        assertTrue(prompt.contains("anonymous inner classes"));
        assertTrue(prompt.contains("decision=rewrite"));
    }

    @Test
    public void policyFailurePromptRepeatsExactMissingMethodAndLambdaRules() {
        String missingMethodPrompt = LocalGuardPromptBuilder.policyFailurePrompt(
                "Add backup.",
                "Generated source policy blocked missing method: RecordDao.listAll() in JsonBackup.java. Add the method or update the caller to use an existing API.",
                "class RecordDao { long insert(Record record) { return 1; } }",
                2);
        String lambdaPrompt = LocalGuardPromptBuilder.policyFailurePrompt(
                "Add date helpers.",
                "Generated source policy blocked Java lambda syntax in DateUtils.java. Use anonymous listener classes instead of ->.",
                "class DateUtils { Runnable r = () -> {}; }",
                2);

        assertTrue(missingMethodPrompt.contains("RecordDao.listAll()"));
        assertTrue(missingMethodPrompt.contains("JsonBackup.java"));
        assertTrue(missingMethodPrompt.contains("existing DAO method"));
        assertTrue(lambdaPrompt.contains("DateUtils.java"));
        assertTrue(lambdaPrompt.contains("anonymous inner classes"));
        assertTrue(lambdaPrompt.contains("Do not use ->"));
    }

    @Test
    public void preflightPromptStaysCompactForSmallLocalModels() {
        List<FileOperation> operations = new ArrayList<>();
        operations.add(new FileOperation(
                "write",
                "app/src/main/java/com/example/JsonBackup.java",
                repeat("class JsonBackup { void export(RecordDao recordDao) { recordDao.listAll(); } }\n", 80)));
        operations.add(new FileOperation(
                "write",
                "app/src/main/java/com/example/IconRes.java",
                repeat("class IconRes { int id() { return R.drawable.ic_food; } }\n", 80)));
        operations.add(new FileOperation(
                "write",
                "app/src/main/java/com/example/FormatUtils.java",
                repeat("class FormatUtils { /* example 1 -> 2 */ }\n", 80)));

        String prompt = LocalGuardPromptBuilder.reviewOperationsPrompt(
                repeat("# Plan\n", 1200),
                "Utilities",
                repeat("Add utility files.\n", 400),
                repeat("--- app/src/main/java/com/example/db/RecordDao.java ---\nclass RecordDao { long insert(Record r) { return 1; } }\n", 200),
                new TaskOperations("Add utilities", operations));

        assertTrue("prompt length was " + prompt.length(), prompt.length() <= 9000);
        assertTrue(prompt.contains("Operation digest"));
        assertTrue(prompt.contains("JsonBackup.java"));
        assertTrue(prompt.contains("RecordDao.listAll()"));
        assertTrue(prompt.contains("R.drawable.ic_food"));
        assertTrue(prompt.contains("->"));
    }

    @Test
    public void policyFailurePromptStaysCompactAndKeepsExactError() {
        String error = "Generated source policy blocked missing drawable resource: R.drawable.ic_food in IconRes.java.";

        String prompt = LocalGuardPromptBuilder.policyFailurePrompt(
                repeat("Add utility files.\n", 400),
                error,
                repeat("--- app/src/main/java/com/example/util/IconRes.java ---\nclass IconRes { int id() { return R.drawable.ic_food; } }\n", 200),
                3);

        assertTrue("prompt length was " + prompt.length(), prompt.length() <= 7000);
        assertTrue(prompt.contains(error));
        assertTrue(prompt.contains("R.drawable.ic_food"));
        assertTrue(prompt.contains("IconRes.java"));
        assertTrue(prompt.contains("Source API digest"));
    }

    private static String repeat(String value, int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            builder.append(value);
        }
        return builder.toString();
    }
}
