package com.androidbuilder.agent;

import com.androidbuilder.util.FileUtils;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HermesScratchCleanupTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void deletesEverythingOnFullSuccess() throws Exception {
        File agentsRoot = temporaryFolder.newFolder("agents");
        File agentRoot = new File(agentsRoot, "task-1-agent-0");
        FileUtils.writeText(new File(agentRoot, "source/Foo.java"), "class Foo {}\n");
        FileUtils.writeText(new File(agentRoot, "agent.log"), "ok\n");

        HermesScratchCleanup.afterMerge(agentsRoot, true);

        assertFalse(agentsRoot.exists());
    }

    @Test
    public void keepsAgentLogsOnPartialFailure() throws Exception {
        File agentsRoot = temporaryFolder.newFolder("agents");
        File first = new File(agentsRoot, "task-1-agent-0");
        File second = new File(agentsRoot, "task-2-agent-1");
        FileUtils.writeText(new File(first, "source/Foo.java"), "class Foo {}\n");
        FileUtils.writeText(new File(first, "agent.log"), "first\n");
        FileUtils.writeText(new File(second, "source/Bar.java"), "class Bar {}\n");
        FileUtils.writeText(new File(second, "agent.log"), "second\n");

        HermesScratchCleanup.afterMerge(agentsRoot, false);

        assertTrue(agentsRoot.exists());
        assertFalse(new File(first, "source").exists());
        assertFalse(new File(second, "source").exists());
        assertTrue(new File(first, "agent.log").isFile());
        assertTrue(new File(second, "agent.log").isFile());
    }

    @Test
    public void toleratesMissingRoot() {
        HermesScratchCleanup.afterMerge(new File(temporaryFolder.getRoot(), "missing"), false);
    }
}
