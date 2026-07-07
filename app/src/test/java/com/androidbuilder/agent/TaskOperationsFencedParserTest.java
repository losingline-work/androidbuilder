package com.androidbuilder.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.androidbuilder.model.FileOperation;
import com.androidbuilder.model.TaskOperations;

import org.junit.Test;

import java.util.List;

public class TaskOperationsFencedParserTest {

    @Test
    public void parsesWriteBlockWithRawContent() {
        String reply = "===SUMMARY===\nAdd strings\n"
                + "===FILE app/src/main/res/values/strings.xml===\n"
                + "<resources>\n    <string name=\"app_name\">Ledger</string>\n</resources>\n"
                + "===END===\n";

        TaskOperations ops = TaskOperationsFencedParser.parse(reply);

        assertEquals("Add strings", ops.summary);
        assertEquals(1, ops.operations.size());
        FileOperation op = ops.operations.get(0);
        assertEquals("write", op.action);
        assertEquals("app/src/main/res/values/strings.xml", op.path);
        // Raw content is preserved verbatim, trailing newline included — no escaping applied.
        assertEquals("<resources>\n    <string name=\"app_name\">Ledger</string>\n</resources>\n", op.content);
    }

    @Test
    public void rawContentNeedsNoEscapingForQuotesBackslashesAndJson() {
        // The exact payload that destroys a JSON reply (unescaped quotes/backslashes/JSON) survives here.
        String body = "String s = \"a \\\"quote\\\" and C:\\\\path and ${var}\";\n"
                + "String j = \"{\\\"k\\\":\\\"v\\\"}\";\n";
        String reply = "===FILE app/src/main/java/com/x/Weird.java===\n" + body + "===END===\n";

        TaskOperations ops = TaskOperationsFencedParser.parse(reply);

        assertEquals(1, ops.operations.size());
        assertEquals(body, ops.operations.get(0).content);
    }

    @Test
    public void parsesEditFindReplaceBlock() {
        String reply = "===EDIT app/src/main/java/com/x/HomeActivity.java===\n"
                + "===FIND===\n        setTitle(\"Home\");\n"
                + "===REPLACE===\n        setTitle(getString(R.string.home_title));\n"
                + "===END===\n";

        TaskOperations ops = TaskOperationsFencedParser.parse(reply);

        assertEquals(1, ops.operations.size());
        FileOperation op = ops.operations.get(0);
        assertEquals("edit", op.action);
        assertEquals("        setTitle(\"Home\");\n", op.find);
        assertEquals("        setTitle(getString(R.string.home_title));\n", op.replace);
    }

    @Test
    public void parsesSelfClosingDeleteAndDrop() {
        String reply = "===FILE a.txt===\nx\n===END===\n"
                + "===DELETE app/src/main/res/layout/old.xml===\n"
                + "===DROP app/src/main/java/com/x/Stale.java===\n";

        List<FileOperation> ops = TaskOperationsFencedParser.parse(reply).operations;

        assertEquals(3, ops.size());
        assertEquals("delete", ops.get(1).action);
        assertEquals("app/src/main/res/layout/old.xml", ops.get(1).path);
        assertEquals("drop", ops.get(2).action);
    }

    @Test
    public void parsesBlockedWithPrerequisite() {
        String reply = "===SUMMARY===\ncannot proceed\n"
                + "===BLOCKED===\nThe DAO does not exist yet\n"
                + "===PREREQ===\nCreate RecordDao first\n===END===\n";

        TaskOperations ops = TaskOperationsFencedParser.parse(reply);

        assertTrue(ops.blocked);
        assertEquals("The DAO does not exist yet", ops.blockedReason);
        assertEquals("Create RecordDao first", ops.prerequisiteWork);
    }

    @Test
    public void truncationDropsOnlyTheUnclosedTrailingBlock() {
        // Two complete blocks then an oversized third file cut off before ===END===.
        String reply = "===FILE a.txt===\naaa\n===END===\n"
                + "===FILE b.txt===\nbbb\n===END===\n"
                + "===FILE c.txt===\nthis file was cut off mid";

        List<FileOperation> salvaged = TaskOperationsFencedParser.completedOperations(reply);

        assertEquals(2, salvaged.size());
        assertEquals("a.txt", salvaged.get(0).path);
        assertEquals("b.txt", salvaged.get(1).path);
        // parse() also yields the two closed blocks (the partial third is simply carried forward later).
        assertEquals(2, TaskOperationsFencedParser.parse(reply).operations.size());
    }

    @Test
    public void stripsOneOuterMarkdownFence() {
        String reply = "```\n===FILE a.txt===\nhi\n===END===\n```";

        TaskOperations ops = TaskOperationsFencedParser.parse(reply);

        assertEquals(1, ops.operations.size());
        assertEquals("hi\n", ops.operations.get(0).content);
    }

    @Test
    public void handlesCrlfLineEndings() {
        String reply = "===FILE a.txt===\r\nhi\r\n===END===\r\n";

        TaskOperations ops = TaskOperationsFencedParser.parse(reply);

        assertEquals(1, ops.operations.size());
        assertEquals("hi\n", ops.operations.get(0).content);
    }

    @Test
    public void incidentalTripleEqualsInsideContentIsNotAMarker() {
        // A content line that looks fenced but is not a KNOWN keyword stays content.
        String reply = "===FILE a.md===\n=== Section ===\nbody\n===END===\n";

        TaskOperations ops = TaskOperationsFencedParser.parse(reply);

        assertEquals(1, ops.operations.size());
        assertEquals("=== Section ===\nbody\n", ops.operations.get(0).content);
    }

    @Test
    public void skipsUnsafePathBlockButKeepsGoodOnes() {
        // A leading-slash (absolute) path is unsafe; that block is skipped, the good one survives — unlike the
        // JSON parser which fails the whole reply on one bad path.
        String reply = "===FILE /etc/passwd===\nowned\n===END===\n"
                + "===FILE app/build.gradle===\nplugins {}\n===END===\n";

        TaskOperations ops = TaskOperationsFencedParser.parse(reply);

        assertEquals(1, ops.operations.size());
        assertEquals("app/build.gradle", ops.operations.get(0).path);
    }

    @Test
    public void emptyOrNoClosedBlockThrows() {
        assertThrows(IllegalArgumentException.class, () -> TaskOperationsFencedParser.parse(""));
        assertThrows(IllegalArgumentException.class,
                () -> TaskOperationsFencedParser.parse("===FILE a.txt===\nnever closed"));
    }

    @Test
    public void isFencedDetectsMarkersAndIgnoresJson() {
        assertTrue(TaskOperationsFencedParser.isFenced("===FILE a.txt===\nx\n===END===\n"));
        assertTrue(TaskOperationsFencedParser.isFenced("===SUMMARY===\nhi\n"));
        assertFalse(TaskOperationsFencedParser.isFenced(
                "{\"summary\":\"x\",\"operations\":[{\"action\":\"write\",\"path\":\"a\",\"content\":\"y\"}]}"));
    }
}
