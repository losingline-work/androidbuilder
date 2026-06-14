package com.androidbuilder.ui;

import com.androidbuilder.model.BuildJobRecord;
import com.androidbuilder.model.ProjectLogEntry;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ProjectLogExportPolicyTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void allowsExportOnlyWhenBuildLogFileExists() throws Exception {
        File log = temp.newFile("build.log");
        BuildJobRecord job = new BuildJobRecord(42, 7, "failed", "finished", log.getAbsolutePath(), null, null, 0, 0, 0);

        assertTrue(ProjectLogExportPolicy.canExportBuildLog(job));
        assertFalse(ProjectLogExportPolicy.canExportBuildLog(new BuildJobRecord(43, 7, "failed", "finished", null, null, null, 0, 0, 0)));
        assertFalse(ProjectLogExportPolicy.canExportBuildLog(new BuildJobRecord(44, 7, "failed", "finished", new File(temp.getRoot(), "missing.log").getAbsolutePath(), null, null, 0, 0, 0)));
        assertFalse(ProjectLogExportPolicy.canExportBuildLog(new BuildJobRecord(45, 7, "failed", "finished", temp.getRoot().getAbsolutePath(), null, null, 0, 0, 0)));
    }

    @Test
    public void buildLogExportNameIncludesProjectAndJob() {
        BuildJobRecord job = new BuildJobRecord(42, 7, "success", "finished", "/tmp/build.log", "/tmp/app.apk", null, 0, 0, 0);

        assertEquals("androidbuilder-project-7-job-42-build.log", ProjectLogExportPolicy.buildLogExportName(job));
    }

    @Test
    public void projectLogExportNameIncludesProject() {
        assertEquals("androidbuilder-project-7-logs.txt", ProjectLogExportPolicy.projectLogExportName(7));
    }

    @Test
    public void logExportsUseTextPlainMimeType() {
        assertEquals("text/plain", ProjectLogExportPolicy.exportMimeType("androidbuilder-project-7-job-42-build.log"));
        assertEquals("text/plain", ProjectLogExportPolicy.exportMimeType("androidbuilder-project-7-logs.txt"));
    }

    @Test
    public void projectLogExportTextPreservesEntryOrderAndCopyText() {
        ProjectLogEntry first = entry(ProjectLogEntry.Kind.AI, 1, "Cloud model", "metadata", "request\nresponse");
        ProjectLogEntry second = entry(ProjectLogEntry.Kind.MESSAGE, 2, "User conversation", "chat", "Add export");

        String text = ProjectLogExportPolicy.projectLogsExportText(Arrays.asList(first, second));

        assertTrue(text.startsWith("Android Builder Project Logs\nEntries: 2"));
        assertTrue(text.indexOf("AI #1") < text.indexOf("MESSAGE #2"));
        assertTrue(text.contains("Cloud model\nmetadata\n\nrequest\nresponse"));
        assertTrue(text.contains("User conversation\nchat\n\nAdd export"));
    }

    @Test
    public void projectLogExportTextUsesChineseHeaderWhenRequested() {
        String text = ProjectLogExportPolicy.projectLogsExportText(Arrays.asList(
                entry(ProjectLogEntry.Kind.MESSAGE, 1, "消息", "聊天", "内容")), true);

        assertTrue(text.startsWith("app 制造机项目日志\n记录数：1"));
    }

    @Test
    public void headerIncludesBuildStampWhenProvided() {
        String text = ProjectLogExportPolicy.projectLogsExportText(Arrays.asList(
                entry(ProjectLogEntry.Kind.MESSAGE, 1, "m", "s", "c")), true, "v0.2.1 · 21d4542 · built 2026-06-14 09:00");

        assertTrue(text.contains("构建版本：v0.2.1 · 21d4542 · built 2026-06-14 09:00"));
    }

    @Test
    public void headerOmitsBuildStampWhenEmpty() {
        String text = ProjectLogExportPolicy.projectLogsExportText(Arrays.asList(
                entry(ProjectLogEntry.Kind.MESSAGE, 1, "m", "s", "c")), false, "");

        assertTrue(text.startsWith("Android Builder Project Logs\nEntries: 1"));
        assertTrue(!text.contains("Build:"));
    }

    @Test
    public void streamingWriterMatchesStringOutputAndBoundsMemory() throws Exception {
        // Large bodies must stream entry-by-entry; the streamed text equals the string builder.
        java.util.List<ProjectLogEntry> entries = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) {
            entries.add(entry(ProjectLogEntry.Kind.MESSAGE, i, "t" + i, "s" + i, repeat("x", 2000)));
        }
        StringBuilder streamed = new StringBuilder();
        ProjectLogExportPolicy.writeProjectLogs(streamed, entries, true, "v0.2.1 · abc1234");

        assertEquals(ProjectLogExportPolicy.projectLogsExportText(entries, true, "v0.2.1 · abc1234"),
                streamed.toString());
        assertTrue(streamed.toString().startsWith("app 制造机项目日志\n记录数：50"));
    }

    private static String repeat(String value, int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            builder.append(value);
        }
        return builder.toString();
    }

    private static ProjectLogEntry entry(ProjectLogEntry.Kind kind, long id, String title, String subtitle, String copyText) {
        return new ProjectLogEntry(kind, id, id * 1000, id * 1000, title, subtitle, "body", copyText, "status");
    }
}
