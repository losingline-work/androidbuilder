package com.androidbuilder.ui;

import com.androidbuilder.model.BuildJobRecord;
import com.androidbuilder.model.ChatMessage;
import com.androidbuilder.model.ProjectPlanRecord;
import com.androidbuilder.model.ProjectTaskRecord;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class ProjectTimelineSnapshot {
    interface BuildJobResolver {
        BuildJobRecord getBuildJob(long id);
    }

    private final List<ChatMessage> messages;
    private final List<ProjectTimelinePolicy.Entry> entries;
    private final Map<Long, BuildJobRecord> buildJobsById;

    private ProjectTimelineSnapshot(
            List<ChatMessage> messages,
            List<ProjectTimelinePolicy.Entry> entries,
            Map<Long, BuildJobRecord> buildJobsById) {
        this.messages = messages;
        this.entries = entries;
        this.buildJobsById = buildJobsById;
    }

    static ProjectTimelineSnapshot empty() {
        return new ProjectTimelineSnapshot(new ArrayList<>(), new ArrayList<>(), new HashMap<>());
    }

    static ProjectTimelineSnapshot create(
            List<ChatMessage> messages,
            boolean showOperationStatus,
            ProjectPlanRecord plan,
            List<ProjectTaskRecord> tasks,
            BuildJobRecord latestJob,
            BuildJobResolver resolver) {
        List<ChatMessage> safeMessages = messages == null ? new ArrayList<>() : new ArrayList<>(messages);
        Map<Long, BuildJobRecord> buildJobsById = resolveLinkedBuildJobs(safeMessages, resolver);
        List<Long> linkedBuildJobIds = new ArrayList<>();
        List<Boolean> visible = new ArrayList<>();
        for (ChatMessage message : safeMessages) {
            BuildJobRecord job = message.linkedBuildJobId == null ? null : buildJobsById.get(message.linkedBuildJobId);
            linkedBuildJobIds.add(ProjectBuildLogVisibilityPolicy.shouldShow(job, message.content) ? message.linkedBuildJobId : null);
            visible.add(!ProjectTimelineMessageVisibilityPolicy.isChatter(message.role, message.content));
        }
        List<ProjectTimelinePolicy.Entry> entries = ProjectTimelinePolicy.entries(
                safeMessages.size(),
                linkedBuildJobIds,
                visible,
                taskAnchorIndex(safeMessages, tasks),
                showOperationStatus,
                plan,
                tasks,
                latestJob,
                false);
        return new ProjectTimelineSnapshot(safeMessages, entries, buildJobsById);
    }

    /**
     * The task group is anchored to the moment the plan was split into tasks: the number of
     * messages created at or before the latest task's createdAt. Messages are ordered ascending,
     * so this is where the group sits chronologically instead of being pinned at the top.
     */
    private static int taskAnchorIndex(List<ChatMessage> messages, List<ProjectTaskRecord> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return messages.size();
        }
        long anchor = 0;
        for (ProjectTaskRecord task : tasks) {
            anchor = Math.max(anchor, task.createdAt);
        }
        int index = 0;
        for (ChatMessage message : messages) {
            if (message.createdAt <= anchor) {
                index++;
            } else {
                break;
            }
        }
        return index;
    }

    private static Map<Long, BuildJobRecord> resolveLinkedBuildJobs(List<ChatMessage> messages, BuildJobResolver resolver) {
        Map<Long, BuildJobRecord> buildJobsById = new HashMap<>();
        if (resolver == null) {
            return buildJobsById;
        }
        for (ChatMessage message : messages) {
            if (message.linkedBuildJobId == null || buildJobsById.containsKey(message.linkedBuildJobId)) {
                continue;
            }
            buildJobsById.put(message.linkedBuildJobId, resolver.getBuildJob(message.linkedBuildJobId));
        }
        return buildJobsById;
    }

    int size() {
        return entries.size();
    }

    ProjectTimelinePolicy.Entry entryAt(int position) {
        return position >= 0 && position < entries.size() ? entries.get(position) : null;
    }

    int positionForTaskIndex(int taskIndex) {
        for (int i = 0; i < entries.size(); i++) {
            ProjectTimelinePolicy.Entry entry = entries.get(i);
            if (entry.kind == ProjectTimelinePolicy.Kind.TASK_GROUP) {
                return i;
            }
        }
        return -1;
    }

    BuildJobRecord buildLogJob(ProjectTimelinePolicy.Entry entry) {
        if (entry == null || entry.sourceIndex < 0 || entry.sourceIndex >= messages.size()) {
            return null;
        }
        return jobForMessage(messages.get(entry.sourceIndex));
    }

    BuildJobRecord jobForMessage(ChatMessage message) {
        if (message == null || message.linkedBuildJobId == null) {
            return null;
        }
        return buildJobsById.get(message.linkedBuildJobId);
    }
}
