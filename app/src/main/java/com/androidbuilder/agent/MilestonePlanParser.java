package com.androidbuilder.agent;

import com.androidbuilder.model.ProjectMilestoneRecord;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses the milestone-plan response — {@code {"milestones":[{order,title,description,slice}]}} — into an
 * ordered list of {@link ProjectMilestoneRecord}. Strict like {@link ImplementationTaskParser}: an empty or
 * malformed list throws (milestone planning is a deliberate gate, not a soft suggestion). {@code slice}
 * defaults to {@code description} when omitted. Order is taken from array position, not the model's "order"
 * field, so the list is always contiguous regardless of what the model numbered.
 */
public final class MilestonePlanParser {
    private MilestonePlanParser() {
    }

    public static List<ProjectMilestoneRecord> fromJson(String raw) throws Exception {
        String jsonText = LenientJson.extractObject(raw, "Milestone plan response");
        JSONObject json = LenientJson.parse(jsonText);
        JSONArray milestonesJson = json.optJSONArray("milestones");
        if (milestonesJson == null || milestonesJson.length() == 0) {
            throw new IllegalArgumentException("Milestone plan is empty.");
        }
        List<ProjectMilestoneRecord> milestones = new ArrayList<>();
        for (int i = 0; i < milestonesJson.length(); i++) {
            JSONObject milestoneJson = milestonesJson.getJSONObject(i);
            String title = milestoneJson.optString("title", "").trim();
            String description = milestoneJson.optString("description", "").trim();
            if (title.isEmpty() || description.isEmpty()) {
                throw new IllegalArgumentException("Milestone requires title and description.");
            }
            String slice = milestoneJson.optString("slice", "").trim();
            if (slice.isEmpty()) {
                slice = description;
            }
            milestones.add(new ProjectMilestoneRecord(
                    0, 0, i, title, description, slice,
                    MilestoneStatus.PENDING, "", 0, 0, 0, 0));
        }
        return milestones;
    }
}
