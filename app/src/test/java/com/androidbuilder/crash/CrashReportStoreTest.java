package com.androidbuilder.crash;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CrashReportStoreTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void writesAndReadsLatestCrashPerPackage() throws Exception {
        File base = temporaryFolder.newFolder("files");
        CrashReportStore.write(base, "com.generated.app", "first crash");
        CrashReportStore.write(base, "com.generated.app", "java.lang.ClassCastException ...");

        assertTrue(CrashReportStore.has(base, "com.generated.app"));
        assertEquals("java.lang.ClassCastException ...", CrashReportStore.read(base, "com.generated.app")); // latest wins
        // different package is independent
        assertFalse(CrashReportStore.has(base, "com.other.app"));
    }

    @Test
    public void clearRemovesTheCapturedCrash() throws Exception {
        File base = temporaryFolder.newFolder("files");
        CrashReportStore.write(base, "com.generated.app", "boom");
        CrashReportStore.clear(base, "com.generated.app");

        assertFalse(CrashReportStore.has(base, "com.generated.app"));
        assertEquals("", CrashReportStore.read(base, "com.generated.app"));
    }

    @Test
    public void ignoresBlankCrashAndMissingPackage() throws Exception {
        File base = temporaryFolder.newFolder("files");
        CrashReportStore.write(base, "com.generated.app", "   ");
        assertFalse(CrashReportStore.has(base, "com.generated.app"));
        assertEquals("", CrashReportStore.read(base, "com.missing"));
    }
}
