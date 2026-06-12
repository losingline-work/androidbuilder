package com.androidbuilder.agent;

import com.androidbuilder.model.FileOperation;
import com.androidbuilder.model.HermesTaskContract;
import com.androidbuilder.util.FileUtils;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class BatchValidationPolicyTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void rejectsEditOperationsInBatchResponse() throws Exception {
        String error = BatchValidationPolicy.review(
                Collections.singletonList(new FileOperation("edit", "app/src/main/res/layout/activity_main.xml", "", "a", "b")),
                Collections.singletonList("app/src/main/res/layout/activity_main.xml"),
                HermesTaskContract.empty(),
                ResourceSymbolsOverlay.empty(),
                temporaryFolder.newFolder("source"));

        assertTrue(error.contains("must be full write or delete"));
        assertTrue(error.contains("got edit"));
    }

    @Test
    public void rejectsUnplannedFilesInBatchResponse() throws Exception {
        String error = BatchValidationPolicy.review(
                Collections.singletonList(new FileOperation("write", "app/src/main/res/layout/extra.xml", "<LinearLayout />")),
                Collections.singletonList("app/src/main/res/layout/activity_main.xml"),
                HermesTaskContract.empty(),
                ResourceSymbolsOverlay.empty(),
                temporaryFolder.newFolder("source"));

        assertTrue(error.contains("unplanned file app/src/main/res/layout/extra.xml"));
    }

    @Test
    public void rejectsMissingManifestFilesInBatchResponse() throws Exception {
        String error = BatchValidationPolicy.review(
                Collections.singletonList(new FileOperation("write", "app/src/main/res/layout/activity_main.xml", "<LinearLayout />")),
                Arrays.asList("app/src/main/res/layout/activity_main.xml", "app/src/main/res/values/strings.xml"),
                HermesTaskContract.empty(),
                ResourceSymbolsOverlay.empty(),
                temporaryFolder.newFolder("source"));

        assertTrue(error.contains("missing planned file app/src/main/res/values/strings.xml"));
    }

    @Test
    public void javaBatchCanReferenceIdFromAcceptedOverlay() throws Exception {
        ResourceSymbolsOverlay overlay = ResourceSymbolsOverlay.empty();
        overlay.absorb(Collections.singletonList(new FileOperation("write", "app/src/main/res/layout/activity_main.xml",
                "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\"><TextView android:id=\"@+id/title\" /></LinearLayout>")));

        String error = BatchValidationPolicy.review(
                Collections.singletonList(new FileOperation("write", "app/src/main/java/com/example/MainActivity.java",
                        "package com.example;\nclass MainActivity { int title() { return R.id.title; } }")),
                Collections.singletonList("app/src/main/java/com/example/MainActivity.java"),
                HermesTaskContract.empty(),
                overlay,
                temporaryFolder.newFolder("source"));

        assertNull(error);
    }

    @Test
    public void javaBatchRejectsMissingResources() throws Exception {
        String error = BatchValidationPolicy.review(
                Collections.singletonList(new FileOperation("write", "app/src/main/java/com/example/MainActivity.java",
                        "package com.example;\nclass MainActivity { int title() { return R.id.missing_title; } }")),
                Collections.singletonList("app/src/main/java/com/example/MainActivity.java"),
                HermesTaskContract.empty(),
                ResourceSymbolsOverlay.empty(),
                temporaryFolder.newFolder("source"));

        assertTrue(error.contains("Batch validation: missing XML id R.id.missing_title"));
    }

    @Test
    public void javaBatchCanReferenceExistingSourceResources() throws Exception {
        File source = temporaryFolder.newFolder("source");
        FileUtils.writeText(new File(source, "app/src/main/res/values/strings.xml"),
                "<resources><string name=\"app_name\">App</string></resources>");

        String error = BatchValidationPolicy.review(
                Collections.singletonList(new FileOperation("write", "app/src/main/java/com/example/MainActivity.java",
                        "package com.example;\nclass MainActivity { int label() { return R.string.app_name; } }")),
                Collections.singletonList("app/src/main/java/com/example/MainActivity.java"),
                HermesTaskContract.empty(),
                ResourceSymbolsOverlay.empty(),
                source);

        assertNull(error);
    }
}
