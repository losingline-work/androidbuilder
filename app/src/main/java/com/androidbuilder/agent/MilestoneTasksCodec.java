package com.androidbuilder.agent;

import com.androidbuilder.model.MilestoneTaskSnapshot;
import com.androidbuilder.model.ProjectTaskRecord;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Serializes a milestone's task list (title + status only) to/from compact JSON for the {@code tasks_json}
 * snapshot stored on the milestone row, so a completed milestone's task list survives the next milestone's
 * {@code clearProjectTasks} and can be shown in its card.
 */
public final class MilestoneTasksCodec {
    private MilestoneTasksCodec() {
    }

    public static String encode(List<ProjectTaskRecord> tasks) {
        JSONArray array = new JSONArray();
        if (tasks != null) {
            for (ProjectTaskRecord task : tasks) {
                JSONObject object = new JSONObject();
                try {
                    object.put("t", task.title == null ? "" : task.title);
                    object.put("s", task.status == null ? "" : task.status);
                } catch (Exception ignored) {
                    continue;
                }
                array.put(object);
            }
        }
        return array.toString();
    }

    public static List<MilestoneTaskSnapshot> decode(String json) {
        List<MilestoneTaskSnapshot> out = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) {
            return out;
        }
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.getJSONObject(i);
                out.add(new MilestoneTaskSnapshot(object.optString("t", ""), object.optString("s", "")));
            }
        } catch (Exception ignored) {
            // Malformed snapshot — show nothing rather than crash.
        }
        return out;
    }
}
