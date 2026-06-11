package com.androidbuilder.ui;

import com.androidbuilder.agent.HermesTaskContractCodec;
import com.androidbuilder.model.HermesTaskContract;
import com.androidbuilder.model.ProjectTaskRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ProjectTaskListDisplayPolicy {
    private ProjectTaskListDisplayPolicy() {
    }

    public static boolean defaultCollapsed() {
        return true;
    }

    public static List<Group> groups(List<ProjectTaskRecord> tasks, boolean collapsed) {
        if (tasks == null || tasks.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, List<ProjectTaskRecord>> grouped = new LinkedHashMap<>();
        for (ProjectTaskRecord task : tasks) {
            String key = groupKey(task);
            List<ProjectTaskRecord> rows = grouped.get(key);
            if (rows == null) {
                rows = new ArrayList<>();
                grouped.put(key, rows);
            }
            rows.add(task);
        }
        List<Group> result = new ArrayList<>();
        for (Map.Entry<String, List<ProjectTaskRecord>> entry : grouped.entrySet()) {
            result.add(new Group(entry.getKey(), labelFor(entry.getKey()), entry.getValue(), shouldExpandGroup(entry.getValue(), collapsed)));
        }
        return result;
    }

    public static List<ProjectTaskRecord> visibleTasks(List<ProjectTaskRecord> tasks, boolean collapsed) {
        if (tasks == null || tasks.isEmpty()) {
            return Collections.emptyList();
        }
        if (!collapsed) {
            return new ArrayList<>(tasks);
        }
        List<ProjectTaskRecord> visible = new ArrayList<>();
        ProjectTaskRecord firstPending = null;
        for (ProjectTaskRecord task : tasks) {
            String status = status(task);
            if ("failed".equals(status) || "running".equals(status)) {
                visible.add(task);
            } else if ("pending".equals(status) && firstPending == null) {
                firstPending = task;
            }
        }
        if (firstPending != null && !visible.contains(firstPending)) {
            visible.add(firstPending);
        }
        return visible;
    }

    private static boolean shouldExpandGroup(List<ProjectTaskRecord> tasks, boolean collapsed) {
        if (!collapsed) {
            return true;
        }
        for (ProjectTaskRecord task : tasks) {
            String status = status(task);
            if ("failed".equals(status) || "running".equals(status)) {
                return true;
            }
        }
        return false;
    }

    private static String groupKey(ProjectTaskRecord task) {
        HermesTaskContract contract = HermesTaskContractCodec.extractFromInstruction(task == null ? "" : task.instruction);
        if (contract != null && !contract.produces.isEmpty()) {
            return normalizeKey(contract.produces.get(0));
        }
        String text = ((task == null || task.title == null ? "" : task.title) + " "
                + (task == null || task.instruction == null ? "" : task.instruction)).toLowerCase(Locale.ROOT);
        if (containsAny(text, "gradle", "manifest", "skeleton", "foundation", "依赖", "骨架")) {
            return "foundation";
        }
        if (containsAny(text, "dao", "sqlite", "database", "model", "数据")) {
            return "data";
        }
        if (containsAny(text, "layout", "activity", "adapter", "ui", "screen", "页面", "界面")) {
            return "ui";
        }
        if (containsAny(text, "stat", "chart", "统计")) {
            return "stats";
        }
        if (containsAny(text, "setting", "export", "import", "设置", "导出")) {
            return "settings";
        }
        return "polish";
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeKey(String value) {
        String text = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return text.isEmpty() ? "polish" : text.replaceAll("[^a-z0-9_-]+", "_");
    }

    private static String labelFor(String key) {
        if ("foundation".equals(key)) {
            return "Foundation";
        }
        if ("data".equals(key)) {
            return "Data";
        }
        if ("ui".equals(key)) {
            return "UI";
        }
        if ("stats".equals(key)) {
            return "Stats";
        }
        if ("settings".equals(key)) {
            return "Settings";
        }
        return "Polish";
    }

    private static String status(ProjectTaskRecord task) {
        return task == null || task.status == null ? "pending" : task.status;
    }

    public static final class Group {
        public final String key;
        public final String label;
        public final List<ProjectTaskRecord> tasks;
        public final boolean expanded;

        Group(String key, String label, List<ProjectTaskRecord> tasks, boolean expanded) {
            this.key = key;
            this.label = label;
            this.tasks = Collections.unmodifiableList(new ArrayList<>(tasks));
            this.expanded = expanded;
        }
    }
}
