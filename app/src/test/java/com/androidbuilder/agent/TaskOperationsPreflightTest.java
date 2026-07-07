package com.androidbuilder.agent;

import com.androidbuilder.model.FileOperation;
import com.androidbuilder.model.HermesReview;
import com.androidbuilder.model.TaskOperations;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TaskOperationsPreflightTest {
    @Test
    public void allowsManyFilesInOneFoundationalTask() {
        List<FileOperation> operations = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            operations.add(new FileOperation("write", "app/src/main/res/drawable/item_" + i + ".xml",
                    "<shape xmlns:android=\"http://schemas.android.com/apk/res/android\" />"));
        }

        HermesReview review = TaskOperationsPreflight.review(new TaskOperations("foundation", operations), "");

        assertEquals(HermesReview.Decision.OK, review.decision);
    }

    @Test
    public void rewritesOnlyAbsurdlyLargeOperationLists() {
        List<FileOperation> operations = new ArrayList<>();
        for (int i = 0; i < TaskOperationsPreflight.MAX_OPERATIONS_PER_TASK + 1; i++) {
            operations.add(new FileOperation("write", "app/src/main/res/drawable/item_" + i + ".xml",
                    "<shape xmlns:android=\"http://schemas.android.com/apk/res/android\" />"));
        }

        HermesReview review = TaskOperationsPreflight.review(new TaskOperations("too many", operations), "");

        assertEquals(HermesReview.Decision.REWRITE, review.decision);
        assertTrue(review.summary.contains("Unusually many file operations"));
        assertTrue(review.rewriteInstruction.contains("cap " + TaskOperationsPreflight.MAX_OPERATIONS_PER_TASK));
        assertTrue(review.rewriteInstruction.contains("Trim"));
        assertTrue(review.rewriteInstruction.contains("defer"));
        assertTrue(!review.rewriteInstruction.contains("Split this into smaller tasks"));
    }

    @Test
    public void allowsCoarseResourceBatchesUpToSixtyOperations() {
        List<FileOperation> operations = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            operations.add(new FileOperation("write", "app/src/main/res/drawable/item_" + i + ".xml",
                    "<shape xmlns:android=\"http://schemas.android.com/apk/res/android\" />"));
        }

        HermesReview review = TaskOperationsPreflight.review(new TaskOperations("resource batch", operations), "");

        assertEquals(HermesReview.Decision.OK, review.decision);
    }

    @Test
    public void doesNotFlagMissingResourcesAgainstTruncatedSnapshot() {
        // Resource existence is AndroidSourceGuard's job on the full tree; the preflight must not
        // false-rewrite a reference to a resource that simply is not in the truncated snapshot.
        TaskOperations operations = ops(new FileOperation("write", "app/src/main/res/layout/activity_main.xml",
                "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\" "
                        + "android:layout_width=\"match_parent\" android:layout_height=\"match_parent\">"
                        + "<TextView android:layout_width=\"wrap_content\" android:layout_height=\"wrap_content\" "
                        + "android:text=\"@string/nav_home\" />"
                        + "</LinearLayout>"));

        HermesReview review = TaskOperationsPreflight.review(operations, "");

        assertEquals(HermesReview.Decision.OK, review.decision);
    }

    @Test
    public void doesNotFlagFrameworkAndroidRReferences() {
        String snapshot = "--- app/build.gradle ---\nandroid { namespace \"com.generated.app\" }\n";
        TaskOperations operations = ops(new FileOperation("write", "app/src/main/java/com/generated/app/ui/HomeActivity.java",
                "package com.generated.app.ui;\n"
                        + "import android.app.Activity;\n"
                        + "public class HomeActivity extends Activity {\n"
                        + "  int home() { return android.R.id.home; }\n"
                        + "}\n"));

        HermesReview review = TaskOperationsPreflight.review(operations, snapshot);

        assertEquals(HermesReview.Decision.OK, review.decision);
    }

    @Test
    public void rewritesMalformedXmlBeforeHermes() {
        TaskOperations operations = ops(new FileOperation("write", "app/src/main/res/layout/activity_main.xml",
                "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\"><TextView>"));

        HermesReview review = TaskOperationsPreflight.review(operations, "");

        assertEquals(HermesReview.Decision.REWRITE, review.decision);
        assertTrue(review.summary.contains("Malformed XML"));
        assertTrue(review.summary.contains("activity_main.xml"));
    }

    @Test
    public void rewritesSubpackageJavaThatUsesRWithoutImport() {
        String snapshot = "--- app/build.gradle ---\nandroid { namespace \"com.generated.app\" }\n";
        TaskOperations operations = ops(new FileOperation("write", "app/src/main/java/com/generated/app/ui/MainActivity.java",
                "package com.generated.app.ui;\n"
                        + "import android.app.Activity;\n"
                        + "import android.os.Bundle;\n"
                        + "public class MainActivity extends Activity {\n"
                        + "  protected void onCreate(Bundle state) { setContentView(R.layout.activity_main); }\n"
                        + "}\n"));

        HermesReview review = TaskOperationsPreflight.review(operations, snapshot);

        assertEquals(HermesReview.Decision.REWRITE, review.decision);
        assertTrue(review.summary.contains("missing R import"));
        assertTrue(review.rewriteInstruction.contains("import com.generated.app.R"));
    }

    @Test
    public void rewritesSubpackageJavaWhenKtsNamespaceUsesEquals() {
        String snapshot = "--- app/build.gradle.kts ---\nandroid { namespace = \"com.generated.app\" }\n";
        TaskOperations operations = ops(new FileOperation("write", "app/src/main/java/com/generated/app/ui/MainActivity.java",
                "package com.generated.app.ui;\n"
                        + "public class MainActivity {\n"
                        + "  Class<?> rClass() { return R.class; }\n"
                        + "}\n"));

        HermesReview review = TaskOperationsPreflight.review(operations, snapshot);

        assertEquals(HermesReview.Decision.REWRITE, review.decision);
        assertTrue(review.summary.contains("missing R import"));
    }

    @Test
    public void okWhenXmlJavaAndResourcesAreConsistent() {
        TaskOperations operations = new TaskOperations("focused", java.util.Arrays.asList(
                new FileOperation("write", "app/src/main/res/values/strings.xml",
                        "<resources><string name=\"nav_home\">Home</string></resources>"),
                new FileOperation("write", "app/src/main/res/layout/activity_main.xml",
                        "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\" "
                                + "android:layout_width=\"match_parent\" android:layout_height=\"match_parent\">"
                                + "<TextView android:layout_width=\"wrap_content\" android:layout_height=\"wrap_content\" "
                                + "android:text=\"@string/nav_home\" />"
                                + "</LinearLayout>"),
                new FileOperation("write", "app/src/main/java/com/generated/app/ui/MainActivity.java",
                        "package com.generated.app.ui;\n"
                                + "import com.generated.app.R;\n"
                                + "class MainActivity { void bind() { int id = R.layout.activity_main; } }\n")));

        HermesReview review = TaskOperationsPreflight.review(operations,
                "--- app/build.gradle ---\nandroid { namespace \"com.generated.app\" }\n");

        assertEquals(HermesReview.Decision.OK, review.decision);
    }

    @Test
    public void okWhenLayoutReferencesIdDeclaredInSamePatch() {
        TaskOperations operations = ops(new FileOperation("write", "app/src/main/res/layout/activity_main.xml",
                "<RelativeLayout xmlns:android=\"http://schemas.android.com/apk/res/android\" "
                        + "android:layout_width=\"match_parent\" android:layout_height=\"match_parent\">"
                        + "<TextView android:id=\"@+id/title\" android:layout_width=\"wrap_content\" "
                        + "android:layout_height=\"wrap_content\" />"
                        + "<TextView android:layout_below=\"@id/title\" android:layout_width=\"wrap_content\" "
                        + "android:layout_height=\"wrap_content\" />"
                        + "</RelativeLayout>"));

        HermesReview review = TaskOperationsPreflight.review(operations, "");

        assertEquals(HermesReview.Decision.OK, review.decision);
    }

    @Test
    public void okWhenXmlParserDoesNotSupportHardeningFeature() {
        String originalFactory = System.getProperty("javax.xml.parsers.DocumentBuilderFactory");
        UnsupportedFeatureDocumentBuilderFactory.delegate = DocumentBuilderFactory.newInstance();
        System.setProperty("javax.xml.parsers.DocumentBuilderFactory",
                UnsupportedFeatureDocumentBuilderFactory.class.getName());
        try {
            TaskOperations operations = ops(new FileOperation("write", "app/src/main/res/values/colors.xml",
                    "<resources><color name=\"primary\">#FF1976D2</color></resources>"));

            HermesReview review = TaskOperationsPreflight.review(operations, "");

            assertEquals(HermesReview.Decision.OK, review.decision);
        } finally {
            UnsupportedFeatureDocumentBuilderFactory.delegate = null;
            if (originalFactory == null) {
                System.clearProperty("javax.xml.parsers.DocumentBuilderFactory");
            } else {
                System.setProperty("javax.xml.parsers.DocumentBuilderFactory", originalFactory);
            }
        }
    }

    public static class UnsupportedFeatureDocumentBuilderFactory extends DocumentBuilderFactory {
        static DocumentBuilderFactory delegate;

        @Override
        public DocumentBuilder newDocumentBuilder() throws ParserConfigurationException {
            return delegate.newDocumentBuilder();
        }

        @Override
        public void setAttribute(String name, Object value) {
            delegate.setAttribute(name, value);
        }

        @Override
        public Object getAttribute(String name) {
            return delegate.getAttribute(name);
        }

        @Override
        public void setFeature(String name, boolean value) throws ParserConfigurationException {
            throw new ParserConfigurationException(name);
        }

        @Override
        public boolean getFeature(String name) throws ParserConfigurationException {
            return delegate.getFeature(name);
        }
    }

    private static TaskOperations ops(FileOperation operation) {
        return new TaskOperations("one", Collections.singletonList(operation));
    }

    @Test
    public void findingsCollectsEveryStructuralDefectNotJustTheFirst() {
        List<FileOperation> operations = new ArrayList<>();
        operations.add(new FileOperation("write", "app/src/main/res/values/a.xml", "<resources>")); // unclosed
        operations.add(new FileOperation("write", "app/src/main/res/values/b.xml", "<resources></resources>")); // ok
        operations.add(new FileOperation("write", "app/src/main/res/layout/c.xml", "<LinearLayout>")); // unclosed
        String snapshot = "android { namespace \"com.example\" }";

        List<TaskOperationsPreflight.Finding> findings = TaskOperationsPreflight.findings(
                new TaskOperations("t", operations), snapshot);

        // Both malformed files are reported (not just the first) so a caller can micro-fix each.
        assertEquals(2, findings.size());
        assertTrue(findings.get(0).reason.contains("a.xml"));
        assertTrue(findings.get(1).reason.contains("c.xml"));
    }

    @Test
    public void findingsReportsMissingRImport() {
        String java = "package com.example.ui;\nclass Home { int x = R.string.app_name; }\n";
        List<FileOperation> operations = new ArrayList<>();
        operations.add(new FileOperation("write", "app/src/main/java/com/example/ui/Home.java", java));

        List<TaskOperationsPreflight.Finding> findings = TaskOperationsPreflight.findings(
                new TaskOperations("t", operations), "android { namespace \"com.example\" }");

        assertEquals(1, findings.size());
        assertTrue(findings.get(0).reason.contains("missing R import"));
        assertTrue(findings.get(0).reason.contains("import com.example.R;"));
    }

    @Test
    public void validateSinglePassesAfterAFix() {
        String namespace = "com.example";
        // A now-well-formed XML file passes.
        assertNull(TaskOperationsPreflight.validateSingle(
                new FileOperation("write", "app/src/main/res/values/a.xml", "<resources></resources>"), namespace));
        // A Java file that now imports R passes.
        String fixed = "package com.example.ui;\nimport com.example.R;\nclass Home { int x = R.string.app_name; }\n";
        assertNull(TaskOperationsPreflight.validateSingle(
                new FileOperation("write", "app/src/main/java/com/example/ui/Home.java", fixed), namespace));
    }
}
