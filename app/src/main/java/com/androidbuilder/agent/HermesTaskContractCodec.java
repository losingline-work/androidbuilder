package com.androidbuilder.agent;

import com.androidbuilder.model.HermesTaskContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class HermesTaskContractCodec {
    public static final String START = "[HermesTaskContract]";
    public static final String END = "[/HermesTaskContract]";

    private HermesTaskContractCodec() {
    }

    static HermesTaskContract fromJson(JSONObject json) {
        if (json == null) {
            return HermesTaskContract.empty();
        }
        return new HermesTaskContract(
                stringArray(json.optJSONArray("allowedPaths")),
                stringArray(json.optJSONArray("expectedFiles")),
                stringArray(json.optJSONArray("forbiddenPaths")),
                stringArray(json.optJSONArray("acceptanceChecks")),
                stringArray(json.optJSONArray("riskNotes")),
                stringArray(json.optJSONArray("dependsOn")),
                stringArray(json.optJSONArray("produces")),
                stringArray(json.optJSONArray("rollbackScope")),
                json.optString("riskLevel", ""),
                json.optBoolean("buildRequiredAfter", false));
    }

    static String appendToInstruction(String instruction, HermesTaskContract contract) {
        String text = instruction == null ? "" : instruction.trim();
        if (contract == null || !contract.hasSignals()) {
            return text;
        }
        return text + "\n\n" + START + "\n" + toJson(contract).toString() + "\n" + END;
    }

    public static HermesTaskContract extractFromInstruction(String instruction) {
        if (instruction == null || instruction.isEmpty()) {
            return HermesTaskContract.empty();
        }
        int start = instruction.indexOf(START);
        int end = instruction.indexOf(END);
        if (start < 0 || end <= start) {
            return HermesTaskContract.empty();
        }
        int jsonStart = start + START.length();
        String jsonText = instruction.substring(jsonStart, end).trim();
        if (jsonText.isEmpty()) {
            return HermesTaskContract.empty();
        }
        try {
            return fromJson(new JSONObject(jsonText));
        } catch (Exception ignored) {
            return HermesTaskContract.empty();
        }
    }

    static String stripFromInstruction(String instruction) {
        String text = instruction == null ? "" : instruction.trim();
        if (text.isEmpty()) {
            return "";
        }
        int start = text.indexOf(START);
        int end = start < 0 ? -1 : text.indexOf(END, start + START.length());
        if (start < 0 || end <= start) {
            return text;
        }
        String before = text.substring(0, start).trim();
        String after = text.substring(end + END.length()).trim();
        if (before.isEmpty()) {
            return after;
        }
        if (after.isEmpty()) {
            return before;
        }
        return before + "\n\n" + after;
    }

    static String promptContextFromInstruction(String instruction) {
        return promptContext(extractFromInstruction(instruction));
    }

    static String promptContext(HermesTaskContract contract) {
        if (contract == null || !contract.hasSignals()) {
            return "";
        }
        StringBuilder builder = new StringBuilder("Hermes task contract:\n");
        appendList(builder, "allowedPaths", contract.allowedPaths);
        appendList(builder, "expectedFiles", contract.expectedFiles);
        appendList(builder, "forbiddenPaths", contract.forbiddenPaths);
        appendList(builder, "acceptanceChecks", contract.acceptanceChecks);
        appendList(builder, "riskNotes", contract.riskNotes);
        appendList(builder, "dependsOn", contract.dependsOn);
        appendList(builder, "produces", contract.produces);
        appendList(builder, "rollbackScope", contract.rollbackScope);
        if (!contract.riskLevel.isEmpty()) {
            builder.append("riskLevel: ").append(contract.riskLevel).append("\n");
        }
        if (contract.buildRequiredAfter) {
            builder.append("buildRequiredAfter: true\n");
        }
        builder.append("Use this contract as scoped execution guidance: prefer allowed paths, produce expected files, satisfy acceptance checks, and do not touch forbidden paths.");
        return builder.toString().trim();
    }

    private static JSONObject toJson(HermesTaskContract contract) {
        JSONObject json = new JSONObject();
        put(json, "allowedPaths", jsonArray(contract.allowedPaths));
        put(json, "expectedFiles", jsonArray(contract.expectedFiles));
        put(json, "forbiddenPaths", jsonArray(contract.forbiddenPaths));
        put(json, "acceptanceChecks", jsonArray(contract.acceptanceChecks));
        put(json, "riskNotes", jsonArray(contract.riskNotes));
        put(json, "dependsOn", jsonArray(contract.dependsOn));
        put(json, "produces", jsonArray(contract.produces));
        put(json, "rollbackScope", jsonArray(contract.rollbackScope));
        put(json, "riskLevel", contract.riskLevel);
        put(json, "buildRequiredAfter", contract.buildRequiredAfter);
        return json;
    }

    private static JSONArray jsonArray(List<String> values) {
        JSONArray array = new JSONArray();
        if (values == null) {
            return array;
        }
        for (String value : values) {
            array.put(value);
        }
        return array;
    }

    private static List<String> stringArray(JSONArray array) {
        List<String> values = new ArrayList<>();
        if (array == null) {
            return values;
        }
        for (int i = 0; i < array.length(); i++) {
            String value = array.optString(i, "").trim();
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return values;
    }

    private static void appendList(StringBuilder builder, String label, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        builder.append(label).append(": ").append(join(values)).append("\n");
    }

    private static String join(List<String> values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(value);
        }
        return builder.toString();
    }

    private static void put(JSONObject json, String key, Object value) {
        try {
            json.put(key, value);
        } catch (Exception ignored) {
            // JSONObject only fails for invalid values; these are controlled primitives and arrays.
        }
    }
}
