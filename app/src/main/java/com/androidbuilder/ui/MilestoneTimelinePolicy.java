package com.androidbuilder.ui;

import com.androidbuilder.agent.MilestoneTasksCodec;
import com.androidbuilder.model.MilestoneTaskSnapshot;
import com.androidbuilder.model.ProjectMilestoneRecord;
import com.androidbuilder.model.ProjectTaskRecord;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure builder of the milestone-card view models from the milestone rows. Each card's task list comes from
 * the milestone's retained {@code tasksJson} snapshot; for the milestone currently being generated (which has
 * no snapshot yet) it falls back to the LIVE project_tasks. Unit-testable (no Android deps).
 */
final class MilestoneTimelinePolicy {
    private MilestoneTimelinePolicy() {
    }

    static List<MilestoneCardModel> cards(List<ProjectMilestoneRecord> milestones, long activeMilestoneId, List<ProjectTaskRecord> liveTasks) {
        List<MilestoneCardModel> cards = new ArrayList<>();
        if (milestones == null) {
            return cards;
        }
        for (ProjectMilestoneRecord milestone : milestones) {
            cards.add(new MilestoneCardModel(milestone.id, milestone.orderIndex, milestone.title, milestone.status,
                    tasksFor(milestone, activeMilestoneId, liveTasks)));
        }
        return cards;
    }

    private static List<MilestoneTaskSnapshot> tasksFor(ProjectMilestoneRecord milestone, long activeMilestoneId, List<ProjectTaskRecord> liveTasks) {
        if (milestone.tasksJson != null && !milestone.tasksJson.trim().isEmpty()) {
            return MilestoneTasksCodec.decode(milestone.tasksJson);
        }
        if (milestone.id == activeMilestoneId && liveTasks != null) {
            List<MilestoneTaskSnapshot> live = new ArrayList<>();
            for (ProjectTaskRecord task : liveTasks) {
                live.add(new MilestoneTaskSnapshot(task.title, task.status));
            }
            return live;
        }
        return new ArrayList<>();
    }
}
