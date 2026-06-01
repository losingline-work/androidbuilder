package com.androidbuilder.ui;

import com.androidbuilder.model.BuildJobRecord;
import com.androidbuilder.model.ProjectPlanRecord;
import com.androidbuilder.model.ProjectTaskRecord;

import java.util.ArrayList;
import java.util.List;

public final class ProjectTimelinePolicy {
    public enum Kind {
        MESSAGE,
        OPERATION_STATUS,
        TASK,
        EMPTY_TASKS,
        BUILD_LOG
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
            boolean showOperationStatus,
            ProjectPlanRecord plan,
            List<ProjectTaskRecord> tasks,
            BuildJobRecord latestJob,
            boolean buildLogVisible) {
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < messageCount; i++) {
            entries.add(new Entry(Kind.MESSAGE, i));
        }
        if (showOperationStatus) {
            entries.add(new Entry(Kind.OPERATION_STATUS, -1));
        }
        if (tasks != null && !tasks.isEmpty()) {
            for (int i = 0; i < tasks.size(); i++) {
                entries.add(new Entry(Kind.TASK, i));
            }
        } else if (plan != null) {
            entries.add(new Entry(Kind.EMPTY_TASKS, -1));
        }
        if (latestJob != null || buildLogVisible) {
            entries.add(new Entry(Kind.BUILD_LOG, -1));
        }
        return entries;
    }
}
