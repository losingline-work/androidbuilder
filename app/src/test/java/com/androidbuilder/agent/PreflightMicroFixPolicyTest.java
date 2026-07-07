package com.androidbuilder.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.androidbuilder.model.FileOperation;
import com.androidbuilder.model.TaskOperations;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class PreflightMicroFixPolicyTest {

    private static TaskOperationsPreflight.Finding finding(String path) {
        return new TaskOperationsPreflight.Finding(new FileOperation("write", path, "x"), "bad " + path);
    }

    @Test
    public void eligibleForOneToThreeDistinctFiles() {
        assertTrue(PreflightMicroFixPolicy.eligible(Arrays.asList(finding("a.xml"))));
        assertTrue(PreflightMicroFixPolicy.eligible(Arrays.asList(finding("a.xml"), finding("b.xml"), finding("c.xml"))));
        // Two findings on the SAME file still count as one file → eligible.
        assertTrue(PreflightMicroFixPolicy.eligible(Arrays.asList(finding("a.xml"), finding("a.xml"))));
    }

    @Test
    public void ineligibleWhenEmptyOrTooManyFiles() {
        assertFalse(PreflightMicroFixPolicy.eligible(new ArrayList<>()));
        assertFalse(PreflightMicroFixPolicy.eligible(null));
        assertFalse(PreflightMicroFixPolicy.eligible(Arrays.asList(
                finding("a.xml"), finding("b.xml"), finding("c.xml"), finding("d.xml"))));
    }

    @Test
    public void pathsAreDistinctAndOrdered() {
        List<String> paths = PreflightMicroFixPolicy.paths(Arrays.asList(
                finding("b.xml"), finding("a.xml"), finding("b.xml")));
        assertEquals(Arrays.asList("b.xml", "a.xml"), paths);
    }

    @Test
    public void extractFilePicksMatchingPathFromFencedReply() {
        String reply = "===FILE app/src/main/res/values/strings.xml===\n<resources />\n===END===\n";

        FileOperation op = PreflightMicroFixPolicy.extractFile(reply, "app/src/main/res/values/strings.xml");

        assertEquals("write", op.action);
        assertEquals("<resources />\n", op.content);
    }

    @Test
    public void extractFileFallsBackToSoleWriteWhenPathDiffers() {
        // Model returned the file under a slightly different path but it's the only write → accept it.
        String reply = "===FILE strings.xml===\n<resources />\n===END===\n";

        FileOperation op = PreflightMicroFixPolicy.extractFile(reply, "app/src/main/res/values/strings.xml");

        assertEquals("strings.xml", op.path);
    }

    @Test
    public void extractFileReturnsNullOnGarbageOrAmbiguity() {
        assertNull(PreflightMicroFixPolicy.extractFile("not a file", "a.xml"));
        // Two writes, neither matching the path → ambiguous → null.
        String twoFiles = "===FILE x.xml===\n<x/>\n===END===\n===FILE y.xml===\n<y/>\n===END===\n";
        assertNull(PreflightMicroFixPolicy.extractFile(twoFiles, "a.xml"));
    }

    @Test
    public void extractFileAlsoAcceptsJsonReply() {
        String json = "{\"summary\":\"fix\",\"operations\":[{\"action\":\"write\",\"path\":\"a.xml\",\"content\":\"<a/>\"}]}";

        FileOperation op = PreflightMicroFixPolicy.extractFile(json, "a.xml");

        assertEquals("<a/>", op.content);
    }
}
