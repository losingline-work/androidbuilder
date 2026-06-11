package com.androidbuilder.agent;

import com.androidbuilder.model.ProjectTaskRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class HermesParallelBatch {
    public final int batchIndex;
    public final List<ProjectTaskRecord> tasks;
    public final String exclusiveReason;

    public HermesParallelBatch(int batchIndex, List<ProjectTaskRecord> tasks, String exclusiveReason) {
        this.batchIndex = batchIndex;
        this.tasks = immutableTasks(tasks);
        this.exclusiveReason = clean(exclusiveReason);
    }

    static HermesParallelBatch empty() {
        return new HermesParallelBatch(0, Collections.emptyList(), "");
    }

    private static List<ProjectTaskRecord> immutableTasks(List<ProjectTaskRecord> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(tasks));
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
