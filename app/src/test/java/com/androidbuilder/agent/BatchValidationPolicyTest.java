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
    public void allowsUnplannedValuesFileForResourceSelfHeal() throws Exception {
        // After scope expansion a Java/XML batch self-heals a missing value resource by adding the
        // values file that declares it; that additive file must not be rejected as "unplanned".
        String error = BatchValidationPolicy.review(
                Arrays.asList(
                        new FileOperation("write", "app/src/main/java/com/example/BillListFragment.java",
                                "package com.example;\nclass BillListFragment { int t() { return R.string.bill_summary_title; } }"),
                        new FileOperation("write", "app/src/main/res/values/strings.xml",
                                "<resources><string name=\"bill_summary_title\">Summary</string></resources>")),
                Collections.singletonList("app/src/main/java/com/example/BillListFragment.java"),
                HermesTaskContract.empty(),
                ResourceSymbolsOverlay.empty(),
                temporaryFolder.newFolder("source"));

        assertNull(error);
    }

    @Test
    public void allowsUnplannedIdsFileAndRecognizesValueDeclaredIds() throws Exception {
        // ids declared via <item type="id"> in a self-heal values file satisfy later R.id refs.
        String error = BatchValidationPolicy.review(
                Arrays.asList(
                        new FileOperation("write", "app/src/main/res/values/ids.xml",
                                "<resources><item type=\"id\" name=\"account_manage_title\"/></resources>"),
                        new FileOperation("write", "app/src/main/java/com/example/AccountManageActivity.java",
                                "package com.example;\nclass AccountManageActivity { int t() { return R.id.account_manage_title; } }")),
                Collections.singletonList("app/src/main/java/com/example/AccountManageActivity.java"),
                HermesTaskContract.empty(),
                ResourceSymbolsOverlay.empty(),
                temporaryFolder.newFolder("source"));

        assertNull(error);
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
