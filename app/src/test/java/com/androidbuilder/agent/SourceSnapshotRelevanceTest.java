package com.androidbuilder.agent;

import com.androidbuilder.util.FileUtils;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SourceSnapshotRelevanceTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void javaAndLayoutRankAboveOtherResourcesAndConfigs() {
        int java = AgentService.relevanceScore("app/src/main/java/com/example/MainActivity.java");
        int layout = AgentService.relevanceScore("app/src/main/res/layout/activity_main.xml");
        int manifest = AgentService.relevanceScore("app/src/main/AndroidManifest.xml");
        int values = AgentService.relevanceScore("app/src/main/res/values/strings.xml");
        int gradle = AgentService.relevanceScore("app/build.gradle");
        int other = AgentService.relevanceScore("app/src/main/assets/data.txt");

        assertTrue(java < layout);
        assertTrue(layout < manifest);
        assertTrue(manifest < values);
        assertTrue(values < gradle);
        assertTrue(gradle < other);
        // Java is the single most relevant for cross-file API consistency.
        assertEquals(0, java);
    }

    @Test
    public void identifiesTextSourceFiles() {
        assertTrue(AgentService.isTextSourceFile("MainActivity.java"));
        assertTrue(AgentService.isTextSourceFile("activity_main.xml"));
        assertTrue(AgentService.isTextSourceFile("build.gradle"));
        assertFalse(AgentService.isTextSourceFile("ic_launcher.png"));
        assertFalse(AgentService.isTextSourceFile("app-debug.apk"));
    }

    @Test
    public void sourceSnapshotUsesFullFocusApiDigestAndResourceIndexLayers() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/src/main/java/com/example/Focus.java",
                "package com.example;\nclass Focus { public void fullTextOnly() { int value = 1; } }\n");
        write(root, "app/src/main/java/com/example/Other.java",
                "package com.example;\nclass Other { public void apiOnly() { int hiddenBody = 2; } }\n");
        write(root, "app/src/main/res/layout/activity_main.xml",
                "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\" android:id=\"@+id/title\" />");

        String snapshot = AgentService.sourceSnapshotForTest(root, "Generated source policy blocked Focus.java.");

        assertTrue(snapshot.contains("--- app/src/main/java/com/example/Focus.java ---"));
        assertTrue(snapshot.contains("fullTextOnly"));
        assertFalse(snapshot.contains("--- app/src/main/java/com/example/Other.java ---"));
        assertTrue(snapshot.contains("--- Java API digest (non-focused source files) ---"));
        assertTrue(snapshot.contains("class Other { void apiOnly(); }"));
        assertFalse(snapshot.contains("hiddenBody"));
        assertTrue(snapshot.contains("--- resource index (complete, authoritative) ---"));
        assertTrue(snapshot.contains("R.id: title"));
    }

    private static void write(File root, String path, String content) throws Exception {
        FileUtils.writeText(new File(root, path), content);
    }
}
