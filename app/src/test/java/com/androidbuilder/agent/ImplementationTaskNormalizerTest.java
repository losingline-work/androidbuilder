package com.androidbuilder.agent;

import com.androidbuilder.model.ProjectTaskRecord;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ImplementationTaskNormalizerTest {
    @Test
    public void keepsBroadFoundationTaskToCoarsePhases() {
        ProjectTaskRecord broad = new ProjectTaskRecord(
                0,
                0,
                0,
                "搭建 Gradle 骨架与主题资源",
                "新增包占位、更新 app/build.gradle 依赖白名单、补齐 values/colors.xml、values/themes.xml、values-night/themes.xml、values/strings.xml、底部导航 menu/menu_bottom_nav.xml、4 个 drawable 图标占位，以及 MainActivity 和 DBHelper。",
                "pending",
                "",
                0,
                0,
                0,
                0);

        List<ProjectTaskRecord> normalized = ImplementationTaskNormalizer.normalize(Collections.singletonList(broad));

        assertTrue(normalized.size() <= 4);
        assertTrue(hasTask(normalized, "Gradle", "app/build.gradle"));
        assertTrue(anyInstructionContainsAll(normalized, "colors.xml", "themes.xml", "menu_bottom_nav.xml"));
        assertTrue(anyInstructionContainsAll(normalized, "drawable", "layout XML", "activity_main.xml"));
        assertTrue(hasTask(normalized, "Java", "MainActivity"));
        assertFalse(anyInstructionContainsAll(normalized, "app/build.gradle", "menu_bottom_nav.xml", "MainActivity"));
    }

    @Test
    public void canReplaceExistingTasksOnlyBeforeAnyTaskIsDoneOrRunning() {
        ProjectTaskRecord failed = new ProjectTaskRecord(1, 1, 0, "Failed broad task", "app/build.gradle values/colors.xml menu_bottom_nav.xml MainActivity", "failed", "", 0, 0, 1, 2);
        ProjectTaskRecord done = new ProjectTaskRecord(2, 1, 0, "Done broad task", "app/build.gradle values/colors.xml menu_bottom_nav.xml MainActivity", "done", "", 0, 0, 1, 2);

        assertTrue(ImplementationTaskNormalizer.canReplaceExistingTasks(Collections.singletonList(failed)));
        assertFalse(ImplementationTaskNormalizer.canReplaceExistingTasks(Collections.singletonList(done)));
    }

    @Test
    public void mergesOldFineResourceTasksIntoCoarsePhases() {
        List<ProjectTaskRecord> normalized = ImplementationTaskNormalizer.normalize(java.util.Arrays.asList(
                task("values XML", "Write only app/src/main/res/values XML files such as colors.xml, strings.xml, themes.xml."),
                task("menu XML", "Write only app/src/main/res/menu XML files such as menu_bottom_nav.xml."),
                task("layout XML", "Write only app/src/main/res/layout XML files such as activity_main.xml and view_keypad.xml."),
                task("Java source", "Write MainActivity and DBHelper Java source files.")));

        assertTrue(normalized.size() <= 3);
        assertTrue(anyInstructionContainsAll(normalized, "colors.xml", "themes.xml", "menu_bottom_nav.xml"));
        assertTrue(anyInstructionContainsAll(normalized, "drawable", "layout XML", "activity_main.xml"));
        assertTrue(hasTask(normalized, "Java", "MainActivity"));
        assertFalse(hasTask(normalized, "values XML", "Write only app/src/main/res/values"));
        assertFalse(hasTask(normalized, "menu XML", "Write only app/src/main/res/menu"));
    }

    @Test
    public void ignoresForbiddenFileTypesWhenClassifyingNarrowTask() {
        ProjectTaskRecord layoutOnly = task(
                "layout XML",
                "Write only app/src/main/res/layout XML files. Reference only resources that already exist. Do not write Java files, drawables, menu XML, values XML, or Gradle files.");

        List<ProjectTaskRecord> normalized = ImplementationTaskNormalizer.normalize(Collections.singletonList(layoutOnly));

        assertTrue(hasTask(normalized, "drawable and layout XML", "layout XML"));
        assertFalse(hasTask(normalized, "Gradle", "app/build.gradle"));
        assertFalse(anyInstructionContainsAll(normalized, "colors.xml", "themes.xml", "menu_bottom_nav.xml"));
        assertFalse(hasTask(normalized, "Java", "MainActivity"));
    }

    @Test
    public void splitPhasesCarryTheFeatureNameAsSuffix() {
        ProjectTaskRecord broad = task("首页与余额卡片",
                "更新 app/build.gradle 依赖，补 values/colors.xml 与 themes.xml，写 activity_main.xml 布局，实现 MainActivity 与 HomeFragment。");

        List<ProjectTaskRecord> normalized = ImplementationTaskNormalizer.normalize(Collections.singletonList(broad));

        boolean anySuffixed = false;
        for (ProjectTaskRecord task : normalized) {
            // Every split phase still resolves to a canonical phase despite the display suffix, so the
            // downstream policies (tiers, high-volume batching) keep working.
            boolean recognized = CanonicalTaskPhase.is(task.title, CanonicalTaskPhase.GRADLE)
                    || CanonicalTaskPhase.is(task.title, CanonicalTaskPhase.RESOURCES)
                    || CanonicalTaskPhase.is(task.title, CanonicalTaskPhase.DRAWABLE_LAYOUT)
                    || CanonicalTaskPhase.is(task.title, CanonicalTaskPhase.JAVA);
            assertTrue("phase not recognized: " + task.title, recognized);
            if (task.title.contains(CanonicalTaskPhase.SEPARATOR + "首页与余额卡片")) {
                anySuffixed = true;
            }
        }
        assertTrue("at least one phase should carry the feature suffix", anySuffixed);
    }

    private static ProjectTaskRecord task(String title, String instruction) {
        return new ProjectTaskRecord(0, 0, 0, title, instruction, "pending", "", 0, 0, 0, 0);
    }

    private static boolean hasTask(List<ProjectTaskRecord> tasks, String titlePart, String instructionPart) {
        for (ProjectTaskRecord task : tasks) {
            if (task.title.contains(titlePart) && task.instruction.contains(instructionPart)) {
                return true;
            }
        }
        return false;
    }

    private static boolean anyInstructionContainsAll(List<ProjectTaskRecord> tasks, String first, String second, String third) {
        for (ProjectTaskRecord task : tasks) {
            if (task.instruction.contains(first) && task.instruction.contains(second) && task.instruction.contains(third)) {
                return true;
            }
        }
        return false;
    }
}
