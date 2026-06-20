package com.androidbuilder.ui;

import com.androidbuilder.agent.MilestoneTasksCodec;
import com.androidbuilder.model.BuildJobRecord;
import com.androidbuilder.model.MilestoneTaskSnapshot;
import com.androidbuilder.model.ProjectMilestoneRecord;
import com.androidbuilder.model.ProjectTaskRecord;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure builder of the milestone-card view models. A card's task list comes from the milestone's retained
 * {@code tasksJson} snapshot (or the LIVE project_tasks for the milestone currently generating). Its
 * CONSOLIDATED build summary (attempts = repairRounds+1, latest result, failure excerpt) is computed from the
 * milestone's latest build job, so the many build+repair rounds become one summary instead of dozens of rows.
 * Unit-testable (no Android deps).
 */
final class MilestoneTimelinePolicy {
    interface BuildLookup {
        BuildJobRecord get(long buildJobId);
    }

    private MilestoneTimelinePolicy() {
    }

    static List<MilestoneCardModel> cards(List<ProjectMilestoneRecord> milestones, long activeMilestoneId, List<ProjectTaskRecord> liveTasks) {
        return cards(milestones, activeMilestoneId, liveTasks, null, "");
    }

    static List<MilestoneCardModel> cards(List<ProjectMilestoneRecord> milestones, long activeMilestoneId,
                                          List<ProjectTaskRecord> liveTasks, BuildLookup builds) {
        return cards(milestones, activeMilestoneId, liveTasks, builds, "");
    }

    static List<MilestoneCardModel> cards(List<ProjectMilestoneRecord> milestones, long activeMilestoneId,
                                          List<ProjectTaskRecord> liveTasks, BuildLookup builds, String activeStatusHint) {
        List<MilestoneCardModel> cards = new ArrayList<>();
        if (milestones == null) {
            return cards;
        }
        for (ProjectMilestoneRecord milestone : milestones) {
            BuildJobRecord build = builds != null && milestone.buildJobId != 0 ? builds.get(milestone.buildJobId) : null;
            boolean hasBuild = build != null;
            // The live status hint lands on the milestone currently being worked on (the march's active one).
            String hint = milestone.id == activeMilestoneId && activeStatusHint != null ? activeStatusHint : "";
            cards.add(new MilestoneCardModel(
                    milestone.id, milestone.orderIndex, milestone.title, milestone.status,
                    tasksFor(milestone, activeMilestoneId, liveTasks),
                    hasBuild,
                    hasBuild ? Math.max(1, milestone.repairRounds + 1) : 0,
                    hasBuild ? build.status : "",
                    hasBuild ? excerpt(build.errorSummary) : "",
                    hint));
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

    /** First non-empty line of the failure summary, trimmed to a card-friendly length. */
    static String excerpt(String errorSummary) {
        if (errorSummary == null) {
            return "";
        }
        for (String line : errorSummary.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                return trimmed.length() <= 200 ? trimmed : trimmed.substring(0, 199).trim() + "…";
            }
        }
        return "";
    }
}
