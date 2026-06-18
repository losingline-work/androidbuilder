package com.androidbuilder.agent;

import com.androidbuilder.model.ProjectTaskRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class ImplementationTaskNormalizer {
    private ImplementationTaskNormalizer() {
    }

    static List<ProjectTaskRecord> normalize(List<ProjectTaskRecord> tasks) {
        return normalize(tasks, false);
    }

    static List<ProjectTaskRecord> normalize(List<ProjectTaskRecord> tasks, boolean chinese) {
        List<ProjectTaskRecord> normalized = new ArrayList<>();
        if (tasks == null) {
            return normalized;
        }
        for (ProjectTaskRecord task : mergeFineResourceTasks(tasks, chinese)) {
            List<ProjectTaskRecord> split = splitIfBroad(task, chinese);
            if (split.isEmpty()) {
                normalized.add(task);
            } else {
                normalized.addAll(split);
            }
        }
        List<ProjectTaskRecord> reordered = new ArrayList<>();
        for (int i = 0; i < normalized.size(); i++) {
            ProjectTaskRecord task = normalized.get(i);
            reordered.add(new ProjectTaskRecord(
                    task.id,
                    task.projectId,
                    i,
                    task.title,
                    task.instruction,
                    task.status,
                    task.resultSummary,
                    task.createdAt,
                    task.updatedAt,
                    task.startedAt,
                    task.completedAt));
        }
        return reordered;
    }

    static boolean canReplaceExistingTasks(List<ProjectTaskRecord> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return false;
        }
        for (ProjectTaskRecord task : tasks) {
            String status = task.status == null ? "" : task.status;
            if ("done".equals(status) || "running".equals(status)) {
                return false;
            }
        }
        return true;
    }

    static boolean changed(List<ProjectTaskRecord> left, List<ProjectTaskRecord> right) {
        if (left == null || right == null || left.size() != right.size()) {
            return true;
        }
        for (int i = 0; i < left.size(); i++) {
            ProjectTaskRecord a = left.get(i);
            ProjectTaskRecord b = right.get(i);
            if (!safe(a.title).equals(safe(b.title)) || !safe(a.instruction).equals(safe(b.instruction))) {
                return true;
            }
        }
        return false;
    }

    private static List<ProjectTaskRecord> mergeFineResourceTasks(List<ProjectTaskRecord> tasks, boolean chinese) {
        List<ProjectTaskRecord> merged = new ArrayList<>();
        boolean addedResourcePhase = false;
        boolean addedDrawableLayoutPhase = false;
        for (ProjectTaskRecord task : tasks) {
            FinePhase phase = finePhase(task);
            if (phase == FinePhase.RESOURCE) {
                if (!addedResourcePhase) {
                    merged.add(resourceTask("", chinese));
                    addedResourcePhase = true;
                }
            } else if (phase == FinePhase.DRAWABLE_LAYOUT) {
                if (!addedDrawableLayoutPhase) {
                    merged.add(drawableLayoutTask("", chinese));
                    addedDrawableLayoutPhase = true;
                }
            } else {
                merged.add(task);
            }
        }
        return merged;
    }

    private static FinePhase finePhase(ProjectTaskRecord task) {
        CategoryFlags flags = categories(task);
        if (flags.onlyResource()) {
            return FinePhase.RESOURCE;
        }
        if (flags.onlyDrawableLayout()) {
            return FinePhase.DRAWABLE_LAYOUT;
        }
        return FinePhase.NONE;
    }

    private static List<ProjectTaskRecord> splitIfBroad(ProjectTaskRecord task, boolean chinese) {
        if (isNormalizedPhase(task)) {
            return java.util.Collections.emptyList();
        }
        CategoryFlags flags = categories(task);
        if (flags.count() < 2) {
            return java.util.Collections.emptyList();
        }

        // The phases are split by FILE TYPE, so they otherwise carry identical titles across every feature.
        // Carry the original task's name as a display suffix so the user can tell them apart.
        String feature = featureHint(task);
        List<ProjectTaskRecord> split = new ArrayList<>();
        if (flags.gradle) {
            split.add(task(CanonicalTaskPhase.withFeature(CanonicalTaskPhase.Phase.GRADLE, feature, chinese),
                    "Update only Gradle/build configuration files such as app/build.gradle. Keep namespace, applicationId, SDK levels, versionCode/versionName, and allowed dependencies consistent. Do not write values XML, menu XML, drawable XML, layout XML, or Java files."));
        }
        if (flags.values || flags.themes || flags.menu) {
            split.add(resourceTask(feature, chinese));
        }
        if (flags.drawable || flags.layout) {
            split.add(drawableLayoutTask(feature, chinese));
        }
        if (flags.javaSource) {
            split.add(task(CanonicalTaskPhase.withFeature(CanonicalTaskPhase.Phase.JAVA, feature, chinese),
                    "Write only Java source files such as package placeholders, MainActivity, DBHelper, DAO, Repository, Fragment, or Adapter classes needed for this phase. Use existing layouts and resources; if a referenced resource is missing, stop and keep this task focused instead of writing new XML here."));
        }
        return split;
    }

    /** A short, human-readable hint drawn from the original (pre-split) task title; "" when uninformative. */
    private static String featureHint(ProjectTaskRecord task) {
        String title = task == null || task.title == null ? "" : task.title.trim();
        if (title.isEmpty() || isNormalizedPhase(task)) {
            return "";
        }
        return title.length() > 28 ? title.substring(0, 28).trim() : title;
    }

    private static boolean isNormalizedPhase(ProjectTaskRecord task) {
        String title = task == null || task.title == null ? "" : task.title;
        return CanonicalTaskPhase.is(title, CanonicalTaskPhase.Phase.GRADLE)
                || CanonicalTaskPhase.is(title, CanonicalTaskPhase.Phase.RESOURCES)
                || CanonicalTaskPhase.is(title, CanonicalTaskPhase.Phase.DRAWABLE_LAYOUT)
                || CanonicalTaskPhase.is(title, CanonicalTaskPhase.Phase.JAVA);
    }

    private static CategoryFlags categories(ProjectTaskRecord task) {
        String text = positiveText(task);
        return new CategoryFlags(
                hasAny(text, "gradle", "build.gradle", "依赖", "dependency", "compilesdk", "minsdk"),
                hasAny(text, "colors.xml", "strings.xml", "dimens.xml", "values资源", "values/", "主色", "文案"),
                hasAny(text, "themes.xml", "values-night", "theme", "主题", "夜间"),
                hasAny(text, "drawable", "vector", "selector", "icon", "图标"),
                hasAny(text, "menu", "bottom_nav", "bottomnavigation", "底部导航", "导航menu"),
                hasAny(text, "layout", "activity_main.xml", "布局"),
                hasAny(text, "java", "mainactivity", "dbhelper", "dao", "repository", "adapter", "fragment", "包占位", "源码", "类"));
    }

    private static String positiveText(ProjectTaskRecord task) {
        String raw = (task.title == null ? "" : task.title) + "\n" + (task.instruction == null ? "" : task.instruction);
        StringBuilder builder = new StringBuilder();
        for (String sentence : raw.split("(?<=[.;。；\\n])")) {
            String positive = beforeNegativeMarker(sentence);
            if (!positive.trim().isEmpty()) {
                builder.append(positive).append('\n');
            }
        }
        return builder.toString().toLowerCase(Locale.ROOT);
    }

    private static String beforeNegativeMarker(String sentence) {
        String lower = sentence.toLowerCase(Locale.ROOT);
        int index = -1;
        for (String marker : new String[]{"do not write", "don't write", "must not write", "不得", "不要写", "不写", "禁止写", "不能写", "不要生成"}) {
            int found = lower.indexOf(marker);
            if (found >= 0 && (index < 0 || found < index)) {
                index = found;
            }
        }
        return index >= 0 ? sentence.substring(0, index) : sentence;
    }

    private static ProjectTaskRecord resourceTask(String feature, boolean chinese) {
        return task(CanonicalTaskPhase.withFeature(CanonicalTaskPhase.Phase.RESOURCES, feature, chinese),
                "Write app/src/main/res/values resource XML such as colors.xml, strings.xml, dimens.xml, arrays.xml, app/src/main/res/values/themes.xml, matching values-night/themes.xml variants, and menu XML such as menu_bottom_nav.xml when needed. Keep XML well-formed and reference only resources that exist or are written in this task. Do not write Gradle files, layout XML, or Java files.");
    }

    private static ProjectTaskRecord drawableLayoutTask(String feature, boolean chinese) {
        return task(CanonicalTaskPhase.withFeature(CanonicalTaskPhase.Phase.DRAWABLE_LAYOUT, feature, chinese),
                "Write drawable XML resources (shape drawables, color selectors, and simple icons) together with related layout XML such as activity_main.xml. Prefer built-in @android:drawable icons and simple <shape> drawables over hand-drawn vector paths. Include every drawable referenced by these layouts and declare all view ids used by later Java wiring. Do not write Gradle files, values XML, menu XML, or Java files.");
    }

    private static ProjectTaskRecord task(String title, String instruction) {
        return new ProjectTaskRecord(0, 0, 0, title, instruction, "pending", "", 0, 0, 0, 0);
    }

    private static boolean hasAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static int count(boolean... values) {
        int count = 0;
        for (boolean value : values) {
            if (value) {
                count++;
            }
        }
        return count;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private enum FinePhase {
        NONE,
        RESOURCE,
        DRAWABLE_LAYOUT
    }

    private static final class CategoryFlags {
        final boolean gradle;
        final boolean values;
        final boolean themes;
        final boolean drawable;
        final boolean menu;
        final boolean layout;
        final boolean javaSource;

        CategoryFlags(boolean gradle, boolean values, boolean themes, boolean drawable, boolean menu, boolean layout, boolean javaSource) {
            this.gradle = gradle;
            this.values = values;
            this.themes = themes;
            this.drawable = drawable;
            this.menu = menu;
            this.layout = layout;
            this.javaSource = javaSource;
        }

        int count() {
            return ImplementationTaskNormalizer.count(gradle, values, themes, drawable, menu, layout, javaSource);
        }

        boolean onlyResource() {
            return !gradle && !drawable && !layout && !javaSource && (values || themes || menu);
        }

        boolean onlyDrawableLayout() {
            return !gradle && !values && !themes && !menu && !javaSource && (drawable || layout);
        }
    }
}
