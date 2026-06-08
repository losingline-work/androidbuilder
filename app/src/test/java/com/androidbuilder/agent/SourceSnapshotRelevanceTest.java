package com.androidbuilder.agent;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SourceSnapshotRelevanceTest {
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
}
