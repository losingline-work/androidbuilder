package com.androidbuilder.ui;

import com.androidbuilder.model.ProjectTaskRecord;

import java.util.List;

public final class ProjectLiveState {
    private ProjectLiveState() {
    }

    public static boolean tasksChanged(List<ProjectTaskRecord> current, List<ProjectTaskRecord> next) {
        if (current == null || next == null) {
            return current != next;
        }
        if (current.size() != next.size()) {
            return true;
        }
        for (int i = 0; i < current.size(); i++) {
            ProjectTaskRecord left = current.get(i);
            ProjectTaskRecord right = next.get(i);
            if (left.id != right.id
                    || left.sortOrder != right.sortOrder
                    || !same(left.title, right.title)
                    || !same(left.instruction, right.instruction)
                    || !same(left.status, right.status)
                    || left.updatedAt != right.updatedAt
                    || left.startedAt != right.startedAt
                    || left.completedAt != right.completedAt
                    || !same(left.resultSummary, right.resultSummary)) {
                return true;
            }
        }
        return false;
    }

    private static boolean same(String left, String right) {
        if (left == null) {
            return right == null;
        }
        return left.equals(right);
    }
}
