package com.androidbuilder.agent;

import com.androidbuilder.model.FileOperation;
import com.androidbuilder.model.TaskOperations;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TaskDraftContextPolicyTest {
    @Test
    public void correctionSectionListsManifestAndIncludesFullErroredFile() {
        TaskOperations draft = operations(
                write("app/src/main/java/com/example/BaseActivity.java", "class BaseActivity {\n  void bind(){ toolbar.setTitle(\"x\"); }\n}\n"),
                write("app/src/main/res/layout/activity_main.xml", "<TextView android:id=\"@+id/title\" />\n"));

        String section = TaskDraftContextPolicy.correctionSection(
                draft,
                "Generated source policy blocked missing XML id: R.id.toolbar in BaseActivity.java.",
                2000);

        assertTrue(section.contains("Previous draft manifest"));
        assertTrue(section.contains("write app/src/main/java/com/example/BaseActivity.java"));
        assertTrue(section.contains("write app/src/main/res/layout/activity_main.xml"));
        assertTrue(section.contains("class BaseActivity"));
        assertTrue(section.contains("toolbar.setTitle"));
        assertFalse(section.contains("<TextView"));
    }

    @Test
    public void correctionSectionIncludesReferencedJavaType() {
        TaskOperations draft = operations(
                write("app/src/main/java/com/example/CategoryDao.java", "class CategoryDao { CategoryDao(Context context) {} }\n"),
                write("app/src/main/java/com/example/RecordDao.java", "class RecordDao { public void list() { int hiddenBody = 1; } }\n"));

        String section = TaskDraftContextPolicy.correctionSection(
                draft,
                "constructor CategoryDao in class CategoryDao cannot be applied to given types",
                2000);

        assertTrue(section.contains("CategoryDao(Context context)"));
        assertTrue(section.contains("Draft API digest (your own previous work - keep consistent with it):"));
        assertTrue(section.contains("class RecordDao { void list(); }"));
        assertFalse(section.contains("hiddenBody"));
    }

    @Test
    public void correctionSectionIncludesFullErroredFileAndApiDigestForOtherDraftJavaFiles() {
        FileOperation[] files = new FileOperation[15];
        files[0] = write("app/src/main/java/com/example/DBHelper.java",
                "class DBHelper { public void broken() { int offendingFullBody = 1; } }\n");
        for (int i = 1; i < files.length; i++) {
            files[i] = write("app/src/main/java/com/example/Other" + i + ".java",
                    "class Other" + i + " { public void api" + i + "() { int hiddenBody" + i + " = " + i + "; } }\n");
        }
        TaskOperations draft = operations(files);

        String section = TaskDraftContextPolicy.correctionSection(
                draft,
                "Generated source policy blocked missing class field: DBHelper.COL_ID in DBHelper.java.",
                TaskDraftContextPolicy.DRAFT_SECTION_LIMIT);

        assertTrue(section.contains("offendingFullBody"));
        assertTrue(section.contains("Draft API digest (your own previous work - keep consistent with it):"));
        assertTrue(section.contains("class Other14 { void api14(); }"));
        assertFalse(section.contains("hiddenBody14"));
        assertTrue(section.length() <= 14000);
    }

    @Test
    public void sectionRespectsCharacterBudget() {
        StringBuilder large = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            large.append('x');
        }
        TaskOperations draft = operations(write("app/src/main/java/com/example/Large.java", large.toString()));

        String section = TaskDraftContextPolicy.correctionSection(
                draft,
                "Generated source policy blocked Large.java.",
                500);

        assertTrue(section.length() <= 500);
        assertTrue(section.contains("...[truncated]"));
    }

    @Test
    public void emptyDraftProducesEmptySection() {
        assertEquals("", TaskDraftContextPolicy.correctionSection(null, "error", 1000));
        assertEquals("", TaskDraftContextPolicy.advisorySection(null, "error", 1000));
    }

    private static TaskOperations operations(FileOperation... operations) {
        return new TaskOperations("draft", Arrays.asList(operations));
    }

    private static FileOperation write(String path, String content) {
        return new FileOperation("write", path, content);
    }
}
