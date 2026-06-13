package com.androidbuilder.agent;

import com.androidbuilder.model.FileOperation;
import com.androidbuilder.model.TaskOperations;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class JavaImportNormalizerTest {
    @Test
    public void addsProjectRImportForSubpackageJavaWrites() {
        TaskOperations operations = new TaskOperations("activity", Collections.singletonList(
                new FileOperation("write", "app/src/main/java/com/generated/app/ui/MainActivity.java",
                        "package com.generated.app.ui;\n"
                                + "import android.app.Activity;\n"
                                + "class MainActivity extends Activity {\n"
                                + "  int layout() { return R.layout.activity_main; }\n"
                                + "}\n")));

        TaskOperations normalized = JavaImportNormalizer.normalize(
                operations,
                "--- app/build.gradle ---\nandroid { namespace \"com.generated.app\" }\n");

        String content = normalized.operations.get(0).content;
        assertTrue(content.contains("import com.generated.app.R;\n"));
        assertTrue(content.indexOf("package com.generated.app.ui;") < content.indexOf("import com.generated.app.R;"));
    }

    @Test
    public void doesNotDuplicateExistingRImportOrTouchAndroidR() {
        TaskOperations operations = new TaskOperations("activity", Collections.singletonList(
                new FileOperation("write", "app/src/main/java/com/generated/app/ui/MainActivity.java",
                        "package com.generated.app.ui;\n"
                                + "import com.generated.app.R;\n"
                                + "class MainActivity {\n"
                                + "  int home() { return android.R.id.home; }\n"
                                + "  int layout() { return R.layout.activity_main; }\n"
                                + "}\n")));

        TaskOperations normalized = JavaImportNormalizer.normalize(
                operations,
                "--- app/build.gradle ---\nandroid { namespace \"com.generated.app\" }\n");

        String content = normalized.operations.get(0).content;
        assertEquals(content.indexOf("import com.generated.app.R;"), content.lastIndexOf("import com.generated.app.R;"));
        assertFalse(content.contains("import android.R;"));
    }
}
