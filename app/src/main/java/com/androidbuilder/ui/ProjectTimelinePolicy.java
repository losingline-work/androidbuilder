package com.androidbuilder.ui;

import com.androidbuilder.model.BuildJobRecord;
import com.androidbuilder.model.ProjectPlanRecord;
import com.androidbuilder.model.ProjectTaskRecord;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ProjectTimelinePolicy {
    public enum Kind {
        MESSAGE,
        PLAN_CARD,
        OPERATION_STATUS,
        TASK_GROUP,
        BUILD_LOG,
        MILESTONE_CARD,
        CRASH_REPAIR_CARD
    }

    public static final class Entry {
        public final Kind kind;
        public final int sourceIndex;

        private Entry(Kind kind, int sourceIndex) {
            this.kind = kind;
            this.sourceIndex = sourceIndex;
        }
    }

    private ProjectTimelinePolicy() {
    }

    public static List<Entry> entries(
            int messageCount,
            List<Long> linkedBuildJobIds,
            List<Boolean> messageVisible,
            int taskAnchorIndex,
            boolean showOperationStatus,
            ProjectPlanRecord plan,
            List<ProjectTaskRecord> tasks,
            BuildJobRecord latestJob,
            boolean buildLogVisible) {
        return entries(messageCount, linkedBuildJobIds, messageVisible, null, taskAnchorIndex, showOperationStatus, plan, tasks, latestJob, buildLogVisible, 0, false);
    }

    public static List<Entry> entries(
            int messageCount,
            List<Long> linkedBuildJobIds,
            List<Boolean> messageVisible,
            List<Boolean> messagePlanCards,
            int taskAnchorIndex,
            boolean showOperationStatus,
            ProjectPlanRecord plan,
            List<ProjectTaskRecord> tasks,
            BuildJobRecord latestJob,
            boolean buildLogVisible) {
        return entries(messageCount, linkedBuildJobIds, messageVisible, messagePlanCards, taskAnchorIndex,
                showOperationStatus, plan, tasks, latestJob, buildLogVisible, 0, false);
    }

    public static List<Entry> entries(
            int messageCount,
            List<Long> linkedBuildJobIds,
            List<Boolean> messageVisible,
            List<Boolean> messagePlanCards,
            int taskAnchorIndex,
            boolean showOperationStatus,
            ProjectPlanRecord plan,
            List<ProjectTaskRecord> tasks,
            BuildJobRecord latestJob,
            boolean buildLogVisible,
            int milestoneCardCount) {
        return entries(messageCount, linkedBuildJobIds, messageVisible, messagePlanCards, taskAnchorIndex,
                showOperationStatus, plan, tasks, latestJob, buildLogVisible, milestoneCardCount, false);
    }

    public static List<Entry> entries(
            int messageCount,
            List<Long> linkedBuildJobIds,
            List<Boolean> messageVisible,
            List<Boolean> messagePlanCards,
            int taskAnchorIndex,
            boolean showOperationStatus,
            ProjectPlanRecord plan,
            List<ProjectTaskRecord> tasks,
            BuildJobRecord latestJob,
            boolean buildLogVisible,
            int milestoneCardCount,
            boolean crashRepairCard) {
        // Incremental flow: replace the per-milestone task-group + per-build log rows with one card per
        // milestone. Keep the real messages (plan, milestone list, terminal result) and the operation status.
        if (milestoneCardCount > 0) {
            List<Entry> entries = new ArrayList<>();
            // The post-install launch-crash capture + repair gets its OWN card, pinned at the top (it is the
            // most recent thing the user is acting on), separate from the milestone cards.
            if (crashRepairCard) {
                entries.add(new Entry(Kind.CRASH_REPAIR_CARD, -1));
            }
            for (int i = 0; i < messageCount; i++) {
                boolean visible = messageVisible == null || i >= messageVisible.size() || messageVisible.get(i);
                if (visible) {
                    boolean planCard = messagePlanCards != null
                            && i < messagePlanCards.size()
                            && Boolean.TRUE.equals(messagePlanCards.get(i));
                    entries.add(new Entry(planCard ? Kind.PLAN_CARD : Kind.MESSAGE, i));
                }
            }
            for (int card = 0; card < milestoneCardCount; card++) {
                entries.add(new Entry(Kind.MILESTONE_CARD, card));
            }
            if (showOperationStatus) {
                entries.add(new Entry(Kind.OPERATION_STATUS, -1));
            }
            return entries;
        }
        List<Entry> entries = new ArrayList<>();
        if (crashRepairCard) {
            entries.add(new Entry(Kind.CRASH_REPAIR_CARD, -1));
        }
        // The task group is a record too: it is inserted at the chronological position where the
        // plan was split into tasks (taskAnchorIndex = number of messages created before it),
        // not pinned at the top.
        boolean hasTaskGroup = tasks != null && !tasks.isEmpty();
        boolean taskGroupEmitted = false;
        // The BUILD_LOG anchor is independent of message visibility: it points at the last
        // message linked to each job even when that message itself is hidden as chatter, so the
        // build log row never disappears when we filter the "build complete" message away.
        Map<Long, Integer> lastMessageIndexByJob = new LinkedHashMap<>();
        if (linkedBuildJobIds != null) {
            for (int i = 0; i < messageCount && i < linkedBuildJobIds.size(); i++) {
                Long buildJobId = linkedBuildJobIds.get(i);
                if (buildJobId != null) {
                    lastMessageIndexByJob.put(buildJobId, i);
                }
            }
        }
        for (int i = 0; i < messageCount; i++) {
            if (hasTaskGroup && !taskGroupEmitted && i >= taskAnchorIndex) {
                entries.add(new Entry(Kind.TASK_GROUP, -1));
                taskGroupEmitted = true;
            }
            boolean visible = messageVisible == null || i >= messageVisible.size() || messageVisible.get(i);
            if (visible) {
                boolean planCard = messagePlanCards != null
                        && i < messagePlanCards.size()
                        && Boolean.TRUE.equals(messagePlanCards.get(i));
                entries.add(new Entry(planCard ? Kind.PLAN_CARD : Kind.MESSAGE, i));
            }
            Long buildJobId = linkedBuildJobIds != null && i < linkedBuildJobIds.size() ? linkedBuildJobIds.get(i) : null;
            if (buildJobId != null && Integer.valueOf(i).equals(lastMessageIndexByJob.get(buildJobId))) {
                entries.add(new Entry(Kind.BUILD_LOG, i));
            }
        }
        if (hasTaskGroup && !taskGroupEmitted) {
            entries.add(new Entry(Kind.TASK_GROUP, -1));
        }
        if (buildLogVisible
                && ProjectBuildLogContentPolicy.hasFailureSummary(latestJob)
                && !lastMessageIndexByJob.containsKey(latestJob.id)) {
            entries.add(new Entry(Kind.BUILD_LOG, -1));
        }
        if (showOperationStatus) {
            entries.add(new Entry(Kind.OPERATION_STATUS, -1));
        }
        return entries;
    }

    public static List<Entry> entries(
            int messageCount,
            List<Long> linkedBuildJobIds,
            List<Boolean> messageVisible,
            boolean showOperationStatus,
            ProjectPlanRecord plan,
            List<ProjectTaskRecord> tasks,
            BuildJobRecord latestJob,
            boolean buildLogVisible) {
        return entries(messageCount, linkedBuildJobIds, messageVisible, 0, showOperationStatus, plan, tasks, latestJob, buildLogVisible);
    }

    public static List<Entry> entries(
            int messageCount,
            List<Long> linkedBuildJobIds,
            boolean showOperationStatus,
            ProjectPlanRecord plan,
            List<ProjectTaskRecord> tasks,
            BuildJobRecord latestJob,
            boolean buildLogVisible) {
        return entries(messageCount, linkedBuildJobIds, null, 0, showOperationStatus, plan, tasks, latestJob, buildLogVisible);
    }

    public static List<Entry> entries(
            int messageCount,
            boolean showOperationStatus,
            ProjectPlanRecord plan,
            List<ProjectTaskRecord> tasks,
            BuildJobRecord latestJob,
            boolean buildLogVisible) {
        return entries(messageCount, null, null, 0, showOperationStatus, plan, tasks, latestJob, buildLogVisible);
    }
}
