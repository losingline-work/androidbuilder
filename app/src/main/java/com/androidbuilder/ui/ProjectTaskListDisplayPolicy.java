package com.androidbuilder.ui;

import com.androidbuilder.agent.HermesTaskContractCodec;
import com.androidbuilder.model.HermesAgentRunRecord;
import com.androidbuilder.model.HermesTaskContract;
import com.androidbuilder.model.ProjectTaskRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ProjectTaskListDisplayPolicy {
    private ProjectTaskListDisplayPolicy() {
    }

    public static boolean defaultCollapsed() {
        return true;
    }

    public static List<Group> groups(List<ProjectTaskRecord> tasks, boolean collapsed) {
        return groups(tasks, collapsed, false);
    }

    public static List<Group> groups(List<ProjectTaskRecord> tasks, boolean collapsed, boolean chinese) {
        if (tasks == null || tasks.isEmpty()) {
            return Collections.emptyList();
        }
        List<Group> result = new ArrayList<>();
        String currentKey = null;
        List<ProjectTaskRecord> currentRows = null;
        for (ProjectTaskRecord task : orderedTasks(tasks)) {
            String key = groupKey(task);
            if (currentRows == null || !key.equals(currentKey)) {
                if (currentRows != null) {
                    result.add(new Group(currentKey, labelFor(currentKey, chinese), currentRows, shouldExpandGroup(currentRows, collapsed)));
                }
                currentKey = key;
                currentRows = new ArrayList<>();
            }
            currentRows.add(task);
        }
        if (currentRows != null) {
            result.add(new Group(currentKey, labelFor(currentKey, chinese), currentRows, shouldExpandGroup(currentRows, collapsed)));
        }
        return result;
    }

    public static List<ProjectTaskRecord> visibleTasks(List<ProjectTaskRecord> tasks, boolean collapsed) {
        return visibleTasks(tasks, collapsed, Collections.emptyList());
    }

    public static List<ProjectTaskRecord> visibleTasks(List<ProjectTaskRecord> tasks, boolean collapsed, List<HermesAgentRunRecord> agentRuns) {
        if (tasks == null || tasks.isEmpty()) {
            return Collections.emptyList();
        }
        if (!collapsed) {
            return new ArrayList<>(tasks);
        }
        List<ProjectTaskRecord> visible = new ArrayList<>();
        ProjectTaskRecord firstPending = null;
        Set<Long> activeAgentTaskIds = activeAgentTaskIds(agentRuns);
        for (ProjectTaskRecord task : orderedTasks(tasks)) {
            String status = status(task);
            if ("failed".equals(status) || "running".equals(status) || activeAgentTaskIds.contains(task.id)) {
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

    private static List<ProjectTaskRecord> orderedTasks(List<ProjectTaskRecord> tasks) {
        List<ProjectTaskRecord> ordered = new ArrayList<>(tasks);
        Collections.sort(ordered, new Comparator<ProjectTaskRecord>() {
            @Override
            public int compare(ProjectTaskRecord left, ProjectTaskRecord right) {
                int order = Integer.compare(left.sortOrder, right.sortOrder);
                if (order != 0) {
                    return order;
                }
                return Long.compare(left.id, right.id);
            }
        });
        return ordered;
    }

    public static boolean shouldCollapseCompleted(List<ProjectTaskRecord> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return false;
        }
        for (ProjectTaskRecord task : tasks) {
            if (!"done".equals(status(task))) {
                return false;
            }
        }
        return true;
    }

    public static String completionSummary(List<ProjectTaskRecord> tasks, boolean chinese) {
        if (!shouldCollapseCompleted(tasks)) {
            return "";
        }
        String base = chinese
                ? "✓ 已完成 " + tasks.size() + "/" + tasks.size() + " 项任务"
                : "✓ Completed " + tasks.size() + "/" + tasks.size() + " tasks";
        long startedAt = Long.MAX_VALUE;
        long completedAt = 0;
        for (ProjectTaskRecord task : tasks) {
            if (task.startedAt > 0) {
                startedAt = Math.min(startedAt, task.startedAt);
            }
            if (task.completedAt > 0) {
                completedAt = Math.max(completedAt, task.completedAt);
            }
        }
        if (startedAt == Long.MAX_VALUE || completedAt <= startedAt) {
            return base;
        }
        return base + (chinese ? " · 总用时 " : " · total ") + formatDuration(completedAt - startedAt);
    }

    private static Set<Long> activeAgentTaskIds(List<HermesAgentRunRecord> agentRuns) {
        if (agentRuns == null || agentRuns.isEmpty()) {
            return Collections.emptySet();
        }
        Set<Long> ids = new HashSet<>();
        for (HermesAgentRunRecord run : agentRuns) {
            String status = run == null || run.status == null ? "" : run.status.trim().toLowerCase(Locale.ROOT);
            if ("running".equals(status) || "merge_pending".equals(status) || "failed".equals(status)) {
                ids.add(run.projectTaskId);
            }
        }
        return ids;
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

    private static String labelFor(String key, boolean chinese) {
        if ("foundation".equals(key)) {
            return chinese ? "基础" : "Foundation";
        }
        if ("data".equals(key)) {
            return chinese ? "数据" : "Data";
        }
        if ("ui".equals(key)) {
            return "UI";
        }
        if ("stats".equals(key)) {
            return chinese ? "统计" : "Stats";
        }
        if ("settings".equals(key)) {
            return chinese ? "设置" : "Settings";
        }
        return chinese ? "收尾" : "Polish";
    }

    private static String status(ProjectTaskRecord task) {
        return task == null || task.status == null ? "pending" : task.status.trim().toLowerCase(Locale.ROOT);
    }

    private static String formatDuration(long durationMs) {
        long seconds = Math.max(0, Math.round(durationMs / 1000.0d));
        long minutes = seconds / 60;
        long remainingSeconds = seconds % 60;
        if (minutes > 0) {
            return minutes + "m" + (remainingSeconds == 0 ? "" : remainingSeconds + "s");
        }
        return seconds + "s";
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
