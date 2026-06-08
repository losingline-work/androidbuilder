package com.androidbuilder.agent;

import com.androidbuilder.util.FileUtils;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

public class LocalGuardModelStoreTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void importsGgufModelIntoStablePrivatePath() throws Exception {
        File filesDir = temporaryFolder.newFolder("files");
        byte[] content = "gguf-demo".getBytes(StandardCharsets.UTF_8);

        LocalGuardModelStore.ImportedModel model = LocalGuardModelStore.saveImportedModel(
                filesDir,
                "tiny-guard.gguf",
                new ByteArrayInputStream(content));

        assertEquals("tiny-guard.gguf", model.name);
        assertEquals(content.length, model.size);
        assertEquals("model.gguf", model.file.getName());
        assertTrue(model.file.getAbsolutePath().contains("local-guard"));
        assertEquals("gguf-demo", FileUtils.readText(model.file));
    }

    @Test
    public void rejectsNonGgufModelNames() throws Exception {
        File filesDir = temporaryFolder.newFolder("files");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                LocalGuardModelStore.saveImportedModel(filesDir, "model.bin", new ByteArrayInputStream(new byte[]{1, 2, 3})));

        assertEquals("Local guard model must be a .gguf file.", error.getMessage());
    }

    @Test
    public void clearModelRemovesPrivateDirectory() throws Exception {
        File filesDir = temporaryFolder.newFolder("files");
        LocalGuardModelStore.saveImportedModel(filesDir, "tiny.gguf", new ByteArrayInputStream(new byte[]{1}));

        LocalGuardModelStore.clear(filesDir);

        assertTrue(!LocalGuardModelStore.modelFile(filesDir).exists());
    }
}
