package com.androidbuilder.agent;

import com.androidbuilder.model.TaskManifest;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BatchNarrationPolicyTest {
    @Test
    public void manifestLineSummarizesGoalAndCounts() {
        String line = BatchNarrationPolicy.manifestLine(
                "Java source wiring for the Mine tab: three new files only.", 25, 8, true);

        assertTrue(line.contains("📋 准备文件清单"));
        assertTrue(line.contains("25 个文件，分 8 批"));
        assertTrue(line.contains("Java source wiring for the Mine tab"));
        assertTrue(line.endsWith("\n"));
    }

    @Test
    public void manifestLineOmitsGoalWhenSummaryEmpty() {
        String line = BatchNarrationPolicy.manifestLine("", 3, 1, true);

        assertTrue(line.contains("3 个文件，分 1 批"));
        assertFalse(line.contains("本步要做"));
    }

    @Test
    public void batchLineListsFileBasenames() {
        List<TaskManifest.Entry> batch = Arrays.asList(
                new TaskManifest.Entry("app/src/main/java/com/example/ui/BillListFragment.java", "write", ""),
                new TaskManifest.Entry("app/src/main/java/com/example/ui/BillAdapter.java", "write", ""));

        String line = BatchNarrationPolicy.batchLine(3, 8, batch, true);

        assertTrue(line.contains("✍️ 生成第 3/8 批"));
        assertTrue(line.contains("BillListFragment.java、BillAdapter.java"));
        assertFalse(line.contains("app/src/main"));
    }

    @Test
    public void batchLineTruncatesLongFileLists() {
        java.util.List<TaskManifest.Entry> batch = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            batch.add(new TaskManifest.Entry("app/src/main/res/drawable/ic_" + i + ".xml", "write", ""));
        }

        String line = BatchNarrationPolicy.batchLine(1, 3, batch, true);

        assertTrue(line.contains("…"));
    }

    @Test
    public void phaseLinesAreHumanReadable() {
        assertEquals("🔍 审查生成的代码\n", BatchNarrationPolicy.reviewingLine(true));
        assertEquals("🔗 合并到项目并校验\n", BatchNarrationPolicy.mergingLine(true));
        assertEquals("🔍 Reviewing generated code\n", BatchNarrationPolicy.reviewingLine(false));
    }
}
