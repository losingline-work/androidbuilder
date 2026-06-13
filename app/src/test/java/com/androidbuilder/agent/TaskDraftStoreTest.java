package com.androidbuilder.agent;

import com.androidbuilder.model.FileOperation;
import com.androidbuilder.model.TaskOperations;
import com.androidbuilder.util.FileUtils;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

public class TaskDraftStoreTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void saveLoadAndDeleteRoundTrip() throws Exception {
        TaskDraftStore store = new TaskDraftStore(temporaryFolder.newFolder("project"));
        TaskOperations draft = new TaskOperations("summary", Arrays.asList(
                new FileOperation("write", "app/src/main/java/Foo.java", "class Foo {}\n")));

        store.save(42, draft);
        TaskOperations loaded = store.load(42);

        assertEquals("summary", loaded.summary);
        assertEquals(1, loaded.operations.size());
        assertEquals("app/src/main/java/Foo.java", loaded.operations.get(0).path);

        store.delete(42);
        assertNull(store.load(42));
    }

    @Test
    public void corruptDraftIsDeletedOnLoad() throws Exception {
        TaskDraftStore store = new TaskDraftStore(temporaryFolder.newFolder("project"));
        File file = store.fileForTest(7);
        FileUtils.writeText(file, "not json");

        assertNull(store.load(7));
        assertFalse(file.exists());
    }

    @Test
    public void oversizedDraftIsNotPersisted() throws Exception {
        TaskDraftStore store = new TaskDraftStore(temporaryFolder.newFolder("project"));
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < TaskDraftStore.MAX_BYTES + 10 * 1024; i++) {
            content.append('x');
        }
        TaskOperations draft = new TaskOperations("large", Arrays.asList(
                new FileOperation("write", "app/src/main/java/Large.java", content.toString())));

        store.save(9, draft);

        assertNull(store.load(9));
        assertFalse(store.fileForTest(9).exists());
    }

    @Test
    public void oversizedDraftDoesNotDestroyPreviousDraft() throws Exception {
        TaskDraftStore store = new TaskDraftStore(temporaryFolder.newFolder("project"));
        TaskOperations small = new TaskOperations("small", Arrays.asList(
                new FileOperation("write", "app/src/main/java/Small.java", "class Small {}")));
        store.save(11, small);
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < TaskDraftStore.MAX_BYTES + 10 * 1024; i++) {
            content.append('x');
        }
        TaskOperations oversized = new TaskOperations("large", Arrays.asList(
                new FileOperation("write", "app/src/main/java/Large.java", content.toString())));

        store.save(11, oversized);

        TaskOperations loaded = store.load(11);
        assertEquals(1, loaded.operations.size());
        assertEquals("app/src/main/java/Small.java", loaded.operations.get(0).path);
    }

    @Test
    public void largeDraftWithinNewCapPersists() throws Exception {
        TaskDraftStore store = new TaskDraftStore(temporaryFolder.newFolder("project"));
        // ~90 files x ~7KB each ~= 630KB: a realistic large-manifest draft that the previous
        // 300KB cap silently destroyed.
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 7 * 1024; i++) {
            content.append('y');
        }
        java.util.List<FileOperation> operations = new java.util.ArrayList<>();
        for (int i = 0; i < 90; i++) {
            operations.add(new FileOperation("write", "app/src/main/res/drawable/ic_" + i + ".xml", content.toString()));
        }

        store.save(12, new TaskOperations("large manifest draft", operations));

        assertEquals(90, store.load(12).operations.size());
    }

    @Test
    public void saveLoadPreservesManifestResumeMetadata() throws Exception {
        TaskDraftStore store = new TaskDraftStore(temporaryFolder.newFolder("project"));
        TaskOperations draft = new TaskOperations(
                "partial",
                Arrays.asList(new FileOperation("write", "app/src/main/res/values/strings.xml",
                        "<resources><string name=\"app_name\">App</string></resources>")),
                false,
                "",
                "",
                "{\"summary\":\"partial\",\"files\":[{\"path\":\"app/src/main/res/values/strings.xml\",\"action\":\"write\",\"intent\":\"strings\"},{\"path\":\"app/src/main/java/com/example/MainActivity.java\",\"action\":\"write\",\"intent\":\"activity\"}]}",
                Arrays.asList("app/src/main/res/values/strings.xml"));

        store.save(13, draft);

        TaskOperations loaded = store.load(13);
        assertEquals(draft.manifestJson, loaded.manifestJson);
        assertEquals(Arrays.asList("app/src/main/res/values/strings.xml"), loaded.acceptedPaths);
    }

    @Test
    public void saveLoadAllowsManifestDraftBeforeAnyBatchAccepted() throws Exception {
        TaskDraftStore store = new TaskDraftStore(temporaryFolder.newFolder("project"));
        TaskOperations draft = new TaskOperations(
                "manifest only",
                java.util.Collections.<FileOperation>emptyList(),
                false,
                "",
                "",
                "{\"summary\":\"partial\",\"files\":[{\"path\":\"app/src/main/java/com/example/MainActivity.java\",\"action\":\"write\",\"intent\":\"activity\"}]}",
                java.util.Collections.<String>emptyList());

        store.save(14, draft);

        TaskOperations loaded = store.load(14);
        assertEquals(0, loaded.operations.size());
        assertEquals(draft.manifestJson, loaded.manifestJson);
    }
}
