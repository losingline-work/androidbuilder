package com.androidbuilder.backend;

import com.androidbuilder.util.FileUtils;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class OfflineMavenInstallerTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private InputStream zip(String... pathThenContentPairs) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (int i = 0; i < pathThenContentPairs.length; i += 2) {
                zip.putNextEntry(new ZipEntry(pathThenContentPairs[i]));
                zip.write(pathThenContentPairs[i + 1].getBytes("UTF-8"));
                zip.closeEntry();
            }
        }
        return new ByteArrayInputStream(bytes.toByteArray());
    }

    @Test
    public void extractLaysOutMavenRepoFiles() throws Exception {
        File dir = temporaryFolder.newFolder("offline-maven");
        OfflineMavenInstaller.extract(zip(
                "com/github/PhilJay/MPAndroidChart/v3.1.0/MPAndroidChart-v3.1.0.aar", "AAR",
                "com/github/PhilJay/MPAndroidChart/v3.1.0/MPAndroidChart-v3.1.0.pom", "<pom/>"), dir);

        assertEquals("AAR", FileUtils.readText(
                new File(dir, "com/github/PhilJay/MPAndroidChart/v3.1.0/MPAndroidChart-v3.1.0.aar")));
        assertTrue(new File(dir, "com/github/PhilJay/MPAndroidChart/v3.1.0/MPAndroidChart-v3.1.0.pom").isFile());
    }

    @Test
    public void extractRejectsZipSlipEntries() throws Exception {
        File dir = temporaryFolder.newFolder("offline-maven");
        assertThrows(Exception.class, () -> OfflineMavenInstaller.extract(zip("../escape.txt", "x"), dir));
    }
}
