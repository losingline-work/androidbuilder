package com.androidbuilder.agent;

import com.androidbuilder.model.HermesTaskContract;
import com.androidbuilder.model.ProjectTaskRecord;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class ImplementationTaskParser {
    private ImplementationTaskParser() {
    }

    public static List<ProjectTaskRecord> fromJson(String raw) throws Exception {
        String jsonText = LenientJson.extractObject(raw, "Task response");
        JSONObject json = LenientJson.parse(jsonText);
        JSONArray tasksJson = json.optJSONArray("tasks");
        if (tasksJson == null || tasksJson.length() == 0) {
            throw new IllegalArgumentException("Implementation task list is empty.");
        }
        List<ProjectTaskRecord> tasks = new ArrayList<>();
        for (int i = 0; i < tasksJson.length(); i++) {
            JSONObject taskJson = tasksJson.getJSONObject(i);
            String title = taskJson.optString("title", "").trim();
            String instruction = taskJson.optString("instruction", "").trim();
            if (title.isEmpty() || instruction.isEmpty()) {
                throw new IllegalArgumentException("Implementation task requires title and instruction.");
            }
            HermesTaskContract contract = HermesTaskContractCodec.fromJson(taskJson);
            instruction = HermesTaskContractCodec.appendToInstruction(instruction, contract);
            tasks.add(new ProjectTaskRecord(0, 0, i, title, instruction, "pending", "", 0, 0, 0, 0));
        }
        return tasks;
    }
}
