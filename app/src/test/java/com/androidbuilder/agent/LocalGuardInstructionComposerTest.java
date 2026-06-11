package com.androidbuilder.agent;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LocalGuardInstructionComposerTest {
    @Test
    public void preflightRewriteAddsLocalHintToOriginalInstruction() {
        String instruction = LocalGuardInstructionComposer.forPreflightRewrite(
                "Create category DAO.",
                "Update DBHelper and CategoryDao together.");

        assertTrue(instruction.startsWith("Create category DAO."));
        assertTrue(instruction.contains("Deterministic preflight"));
        assertTrue(instruction.contains("Update DBHelper and CategoryDao together."));
    }

    @Test
    public void preflightRewritePreservesExistingRetryContext() {
        String current = "Create category DAO.\n\nPrevious output was rejected by local validation on attempt 2: missing method.";

        String instruction = LocalGuardInstructionComposer.forPreflightRewrite(
                current,
                "Add RecordDao.listAll() or update JsonBackup.java.");

        assertTrue(instruction.contains("Previous output was rejected by local validation"));
        assertTrue(instruction.contains("RecordDao.listAll()"));
    }

    @Test
    public void policyRewriteAugmentsDeterministicInstruction() {
        String base = PolicyRewriteInstruction.create("Repair database", "Generated source policy blocked missing method: DBHelper.getWritableDatabase() in CategoryDao.java.", 2);

        String instruction = LocalGuardInstructionComposer.forPolicyRewrite(base, "Use SQLiteOpenHelper-compatible DBHelper API.");

        assertTrue(instruction.startsWith(base));
        assertTrue(instruction.contains("Deterministic policy-error hint"));
        assertTrue(instruction.contains("Use SQLiteOpenHelper-compatible DBHelper API."));
    }

    @Test
    public void emptyHintKeepsExistingInstruction() {
        assertEquals("Keep existing", LocalGuardInstructionComposer.forPolicyRewrite("Keep existing", ""));
        assertEquals("Keep existing", LocalGuardInstructionComposer.forPreflightRewrite("Keep existing", " "));
    }
}
