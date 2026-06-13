package com.androidbuilder.ui;

import com.androidbuilder.model.BuildJobRecord;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ProjectRunningLogPolicyTest {
    @Test
    public void liveJobStatusesAreRecognized() {
        assertTrue(ProjectRunningLogPolicy.isLiveJob(job("generating")));
        assertTrue(ProjectRunningLogPolicy.isLiveJob(job("building")));
        assertTrue(ProjectRunningLogPolicy.isLiveJob(job("queued")));
        assertFalse(ProjectRunningLogPolicy.isLiveJob(job("failed")));
        assertFalse(ProjectRunningLogPolicy.isLiveJob(job("generated")));
        assertFalse(ProjectRunningLogPolicy.isLiveJob(null));
    }

    @Test
    public void tailShowsMostRecentNarrationWhenLogIsLong() {
        StringBuilder logs = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            logs.append("noisy guard line ").append(i).append('\n');
        }
        logs.append("✍️ 生成第 15/25 批：BillListFragment.java\n");
        logs.append("🔍 审查生成的代码\n");

        String tail = ProjectRunningLogPolicy.tail(logs.toString(), 200, true);

        assertTrue(tail.contains("✍️ 生成第 15/25 批"));
        assertTrue(tail.contains("🔍 审查生成的代码"));
        assertTrue(tail.contains("更早的日志见上方"));
        assertFalse(tail.contains("noisy guard line 10\n"));
    }

    @Test
    public void tailReturnsWholeLogWhenShort() {
        String logs = "📋 准备文件清单\n✍️ 生成第 1/3 批\n";
        assertTrue(ProjectRunningLogPolicy.tail(logs, 4500, true).equals(logs));
    }

    @Test
    public void tailStartsAtALineBoundary() {
        String logs = "AAAA-first-fragment\nsecond full line\nthird full line\n";
        String tail = ProjectRunningLogPolicy.tail(logs, 30, false);
        assertFalse(tail.contains("AAAA-first-fragment"));
        assertTrue(tail.contains("third full line"));
    }

    private static BuildJobRecord job(String status) {
        return new BuildJobRecord(1, 1, status, "phase", "", "", "", 0, 0, 0);
    }
}
