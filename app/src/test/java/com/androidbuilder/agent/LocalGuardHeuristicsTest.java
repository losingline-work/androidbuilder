package com.androidbuilder.agent;

import com.androidbuilder.model.FileOperation;
import com.androidbuilder.model.TaskOperations;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LocalGuardHeuristicsTest {
    @Test
    public void preflightDoesNotFlagArrowTokenInComment() {
        // RC4-A: a '->' that appears only inside a Javadoc/comment is harmless (it is not lambda code)
        // and the old "delete the arrow from your comment" hint was unsatisfiable. The local preflight
        // must stay silent; the merge-time AndroidSourceGuard (comment/string-stripped) owns real lambda
        // policy.
        TaskOperations operations = new TaskOperations(
                "Add format helper",
                Collections.singletonList(new FileOperation(
                        "write",
                        "app/src/main/java/com/example/FormatUtils.java",
                        "class FormatUtils { /** e.g. 1 -> 2 */ }")));

        LocalGuardResult result = LocalGuardHeuristics.reviewOperations("", operations);

        assertTrue(result.summary, !result.usable);
    }

    @Test
    public void preflightCatchesMissingDrawableReferenceBeforeWrite() {
        TaskOperations operations = new TaskOperations(
                "Add icon resolver",
                Collections.singletonList(new FileOperation(
                        "write",
                        "app/src/main/java/com/example/IconRes.java",
                        "class IconRes { int id() { return R.drawable.ic_food; } }")));

        LocalGuardResult result = LocalGuardHeuristics.reviewOperations("", operations);

        assertTrue(result.usable);
        assertEquals(LocalGuardResult.Decision.REWRITE, result.decision);
        assertTrue(result.additionalInstruction.contains("R.drawable.ic_food"));
        assertTrue(result.additionalInstruction.contains("app/src/main/res/drawable/ic_food.xml"));
    }

    @Test
    public void preflightCatchesMissingXmlValueResourceBeforeWrite() {
        TaskOperations operations = new TaskOperations(
                "Add tab indicator",
                Collections.singletonList(new FileOperation(
                        "write",
                        "app/src/main/res/drawable/tab_indicator.xml",
                        "<shape xmlns:android=\"http://schemas.android.com/apk/res/android\">"
                                + "<solid android:color=\"@color/tab_selected\"/>"
                                + "</shape>")));

        LocalGuardResult result = LocalGuardHeuristics.reviewOperations("", operations);

        assertTrue(result.usable);
        assertEquals(LocalGuardResult.Decision.REWRITE, result.decision);
        assertTrue(result.additionalInstruction.contains("@color/tab_selected"));
        assertTrue(result.additionalInstruction.contains("tab_indicator.xml"));
        assertTrue(result.additionalInstruction.contains("app/src/main/res/values/colors.xml"));
    }

    @Test
    public void preflightAcceptsXmlValueResourceAddedInSameOperations() {
        TaskOperations operations = new TaskOperations(
                "Add tab indicator",
                Arrays.asList(
                        new FileOperation(
                                "write",
                                "app/src/main/res/drawable/tab_indicator.xml",
                                "<shape xmlns:android=\"http://schemas.android.com/apk/res/android\">"
                                        + "<solid android:color=\"@color/tab_selected\"/>"
                                        + "</shape>"),
                        new FileOperation(
                                "write",
                                "app/src/main/res/values/colors.xml",
                                "<resources><color name=\"tab_selected\">#2196F3</color></resources>")));

        LocalGuardResult result = LocalGuardHeuristics.reviewOperations("", operations);

        assertTrue(result.summary, !result.usable);
    }

    @Test
    public void preflightGroupsManyMissingDrawablesIntoOneShortHint() {
        TaskOperations operations = new TaskOperations(
                "Add icon resolver",
                Collections.singletonList(new FileOperation(
                        "write",
                        "app/src/main/java/com/example/IconRes.java",
                        "class IconRes { int food() { return R.drawable.ic_food; } int traffic() { return R.drawable.ic_traffic; } int other() { return R.drawable.ic_other; } }")));

        LocalGuardResult result = LocalGuardHeuristics.reviewOperations("", operations);

        assertTrue(result.usable);
        assertTrue(result.additionalInstruction.contains("IconRes.java references missing drawable resources"));
        assertTrue(result.additionalInstruction.contains("ic_food, ic_traffic, ic_other"));
        assertTrue(result.additionalInstruction.contains("getIdentifier"));
        assertEquals(1, count(result.additionalInstruction, "IconRes.java references"));
    }

    @Test
    public void preflightCatchesDaoCallWithoutDeclaration() {
        TaskOperations operations = new TaskOperations(
                "Add backup",
                Collections.singletonList(new FileOperation(
                        "write",
                        "app/src/main/java/com/example/JsonBackup.java",
                        "class JsonBackup { void export(RecordDao recordDao) { recordDao.listAll(); } }")));
        String snapshot = "--- app/src/main/java/com/example/db/RecordDao.java ---\n"
                + "class RecordDao { long insert(Record record) { return 1; } }\n";

        LocalGuardResult result = LocalGuardHeuristics.reviewOperations(snapshot, operations);

        assertTrue(result.usable);
        assertEquals(LocalGuardResult.Decision.REWRITE, result.decision);
        assertTrue(result.additionalInstruction.contains("RecordDao.listAll()"));
        assertTrue(result.additionalInstruction.contains("JsonBackup.java"));
    }

    @Test
    public void preflightDoesNotFlagDaoMethodWhenDaoOnlyInMergedDraft() {
        // Caller-only correction: the model re-sends only TransactionRepository.java and relies on the
        // merged previousDraft to still carry TransactionDao.java. AgentService merges before the guard
        // runs, so the guard must see the DAO that lives in the accumulated draft - even when the
        // snapshot is truncated below the DAO's section - and must NOT emit a phantom rewrite.
        TaskOperations previousDraft = new TaskOperations(
                "initial draft",
                Arrays.asList(
                        new FileOperation(
                                "write",
                                "app/src/main/java/com/example/db/TransactionDao.java",
                                "package com.example.db;\n"
                                        + "public interface TransactionDao {\n"
                                        + "    java.util.List<Transaction> getRecent(int limit);\n"
                                        + "    long insert(Transaction t);\n"
                                        + "}\n"),
                        new FileOperation(
                                "write",
                                "app/src/main/java/com/example/repo/TransactionRepository.java",
                                "package com.example.repo;\n"
                                        + "public class TransactionRepository { /* stale */ }\n")));
        TaskOperations correction = new TaskOperations(
                "fix repository",
                Collections.singletonList(new FileOperation(
                        "write",
                        "app/src/main/java/com/example/repo/TransactionRepository.java",
                        "package com.example.repo;\n"
                                + "import com.example.db.TransactionDao;\n"
                                + "public class TransactionRepository {\n"
                                + "    private final TransactionDao transactionDao;\n"
                                + "    TransactionRepository(TransactionDao d) { this.transactionDao = d; }\n"
                                + "    java.util.List<Transaction> recent() { return transactionDao.getRecent(10); }\n"
                                + "}\n")));
        TaskOperations merged = TaskOperationsMergePolicy.merge(previousDraft, correction);
        // Snapshot truncated below the DAO's section (DAO declaration not present in snapshot text).
        String snapshot = "--- app/src/main/java/com/example/ui/MainActivity.java ---\n"
                + "package com.example.ui; class MainActivity {}\n";

        LocalGuardResult result = LocalGuardHeuristics.reviewOperations(snapshot, merged);

        assertTrue(result.summary, !result.usable);
    }

    @Test
    public void preflightDoesNotFlagDaoMethodWhenDaoDeclarationNotVisible() {
        // Defense in depth: even if only the caller is handed to the guard (no merge, snapshot truncated
        // below the DAO), the guard cannot prove the method is missing, so it must stay silent rather
        // than emit a phantom "method not declared" rewrite.
        TaskOperations operations = new TaskOperations(
                "fix repository",
                Collections.singletonList(new FileOperation(
                        "write",
                        "app/src/main/java/com/example/repo/TransactionRepository.java",
                        "package com.example.repo;\n"
                                + "import com.example.db.TransactionDao;\n"
                                + "public class TransactionRepository {\n"
                                + "    private final TransactionDao transactionDao;\n"
                                + "    TransactionRepository(TransactionDao d) { this.transactionDao = d; }\n"
                                + "    java.util.List<Transaction> recent() { return transactionDao.getRecent(10); }\n"
                                + "}\n")));
        String snapshot = "--- app/src/main/java/com/example/ui/MainActivity.java ---\n"
                + "package com.example.ui; class MainActivity {}\n";

        LocalGuardResult result = LocalGuardHeuristics.reviewOperations(snapshot, operations);

        assertTrue(result.summary, !result.usable);
    }

    @Test
    public void reviewOperationsNoLongerFlagsArrowInCommentsOrStrings() {
        // RC4-A: a '->' surviving only in a comment, Javadoc, or string literal must NOT trigger a
        // rewrite hint (the old raw-content check demanded deleting arrows from comments - an
        // unsatisfiable instruction). The merge-time AndroidSourceGuard, which scans comment/string-
        // stripped code, remains the sole authority on real lambda syntax.
        TaskOperations operations = new TaskOperations(
                "icon map",
                Collections.singletonList(new FileOperation(
                        "write",
                        "app/src/main/java/com/example/util/IconCatalog.java",
                        "package com.example.util;\n"
                                + "/** Maps category -> icon key. */\n"
                                + "public final class IconCatalog {\n"
                                + "    // resolves key -> drawable name\n"
                                + "    static final String EXAMPLE = \"a -> b\";\n"
                                + "    private IconCatalog() {}\n"
                                + "}\n")));

        LocalGuardResult result = LocalGuardHeuristics.reviewOperations("", operations);

        assertTrue(result.summary, !result.usable);
    }

    @Test
    public void preflightDoesNotFlagDaoMethodWhenDaoSectionIsTruncated() {
        // RC2: the DAO's snapshot section is present but cut off by the snapshot budget (ends with the
        // truncation marker). The model may have written the method below the cut, and for an
        // uncommitted task the on-disk DAO can be a stale sibling-task version. The guard cannot prove
        // the method is missing here, so it must NOT emit a phantom "not declared" rewrite. The
        // merge-time AndroidSourceGuard still validates the full assembled tree.
        TaskOperations operations = new TaskOperations(
                "fix repository",
                Collections.singletonList(new FileOperation(
                        "write",
                        "app/src/main/java/com/example/repo/TransactionRepository.java",
                        "package com.example.repo;\n"
                                + "import com.example.db.TransactionDao;\n"
                                + "public class TransactionRepository {\n"
                                + "    private final TransactionDao transactionDao;\n"
                                + "    TransactionRepository(TransactionDao d) { this.transactionDao = d; }\n"
                                + "    java.util.List<Transaction> recent() { return transactionDao.listInRange(0, 1); }\n"
                                + "}\n")));
        String snapshot = "--- app/src/main/java/com/example/db/TransactionDao.java ---\n"
                + "package com.example.db;\n"
                + "public interface TransactionDao { long insert(Transaction t);\n"
                + "...[truncated]";

        LocalGuardResult result = LocalGuardHeuristics.reviewOperations(snapshot, operations);

        assertTrue(result.summary, !result.usable);
    }

    @Test
    public void preflightDoesNotFlagDaoMethodWhenDaoOnlyInApiDigest() {
        // RC2: the DAO is known only from the budgeted Java-API digest tail (a lossy signature
        // summary), not a full-text section. Absence cannot be proven from the digest, so no phantom.
        TaskOperations operations = new TaskOperations(
                "fix repository",
                Collections.singletonList(new FileOperation(
                        "write",
                        "app/src/main/java/com/example/repo/TransactionRepository.java",
                        "package com.example.repo;\n"
                                + "import com.example.db.TransactionDao;\n"
                                + "public class TransactionRepository {\n"
                                + "    private final TransactionDao transactionDao;\n"
                                + "    TransactionRepository(TransactionDao d) { this.transactionDao = d; }\n"
                                + "    java.util.List<Transaction> recent() { return transactionDao.listInRange(0, 1); }\n"
                                + "}\n")));
        String snapshot = "--- Java API digest (non-focused source files) ---\n"
                + "com/example/db/TransactionDao.java: insert(Transaction), update(Transaction)\n";

        LocalGuardResult result = LocalGuardHeuristics.reviewOperations(snapshot, operations);

        assertTrue(result.summary, !result.usable);
    }

    @Test
    public void preflightStillFlagsGenuinelyAbsentDaoMethodWhenDaoIsVisible() {
        // The DAO declaration is visible as a full-text (non-truncated) section but lacks the method,
        // so this is a genuinely-absent method and must still be flagged - the RC2 hardening must not
        // weaken detection when the guard can actually see the DAO's complete declarations.
        TaskOperations operations = new TaskOperations(
                "add backup",
                Collections.singletonList(new FileOperation(
                        "write",
                        "app/src/main/java/com/example/repo/TransactionRepository.java",
                        "package com.example.repo;\n"
                                + "import com.example.db.TransactionDao;\n"
                                + "public class TransactionRepository {\n"
                                + "    private final TransactionDao transactionDao;\n"
                                + "    TransactionRepository(TransactionDao d) { this.transactionDao = d; }\n"
                                + "    java.util.List<Transaction> recent() { return transactionDao.getRecent(10); }\n"
                                + "}\n")));
        String snapshot = "--- app/src/main/java/com/example/db/TransactionDao.java ---\n"
                + "package com.example.db;\n"
                + "public interface TransactionDao { long insert(Transaction t); }\n";

        LocalGuardResult result = LocalGuardHeuristics.reviewOperations(snapshot, operations);

        assertTrue(result.usable);
        assertEquals(LocalGuardResult.Decision.REWRITE, result.decision);
        assertTrue(result.additionalInstruction.contains("TransactionDao.getRecent()"));
        assertTrue(result.additionalInstruction.contains("TransactionRepository.java"));
    }

    @Test
    public void policyFailureFastHintForMissingDrawableKeepsExactResource() {
        LocalGuardResult result = LocalGuardHeuristics.rewritePolicyFailure(
                "Generated source policy blocked missing drawable resource: R.drawable.ic_food in IconRes.java.");

        assertTrue(result.usable);
        assertEquals(LocalGuardResult.Decision.REWRITE, result.decision);
        assertTrue(result.additionalInstruction.contains("R.drawable.ic_food"));
        assertTrue(result.additionalInstruction.contains("IconRes.java"));
        assertTrue(result.additionalInstruction.contains("app/src/main/res/drawable/ic_food.xml"));
    }

    @Test
    public void policyFailureFastHintForMissingXmlColorKeepsExactResource() {
        LocalGuardResult result = LocalGuardHeuristics.rewritePolicyFailure(
                "Generated source policy blocked missing XML resource reference: @color/primary in styles.xml.");

        assertTrue(result.usable);
        assertEquals(LocalGuardResult.Decision.REWRITE, result.decision);
        assertTrue(result.additionalInstruction.contains("@color/primary"));
        assertTrue(result.additionalInstruction.contains("styles.xml"));
        assertTrue(result.additionalInstruction.contains("app/src/main/res/values/colors.xml"));
    }

    private static int count(String text, String pattern) {
        int count = 0;
        int start = 0;
        while (true) {
            int index = text.indexOf(pattern, start);
            if (index < 0) {
                return count;
            }
            count++;
            start = index + pattern.length();
        }
    }
}
