package com.androidbuilder.agent;

import com.androidbuilder.model.AppSpec;
import com.androidbuilder.util.NameUtils;

import org.json.JSONObject;

public final class AppSpecParser {
    private AppSpecParser() {
    }

    public static AppSpec fromPrompt(String prompt, String fallbackName) {
        return fromPrompt(prompt, fallbackName, false);
    }

    public static AppSpec fromPrompt(String prompt, String fallbackName, boolean chinese) {
        String name = fallbackName == null || fallbackName.trim().isEmpty()
                ? NameUtils.projectNameFromPrompt(prompt)
                : fallbackName.trim();
        return new AppSpec(
                name,
                NameUtils.packageNameFromProject(name),
                prompt == null ? "" : prompt.trim(),
                chinese ? "记录" : "Item",
                chinese ? "标题" : "Title",
                chinese ? "备注" : "Notes",
                chinese ? "zh" : "en"
        );
    }

    public static AppSpec fromJson(String raw, String prompt, String fallbackName) {
        return fromJson(raw, prompt, fallbackName, false);
    }

    public static AppSpec fromJson(String raw, String prompt, String fallbackName, boolean chinese) {
        if (raw == null || raw.trim().isEmpty()) {
            return fromPrompt(prompt, fallbackName, chinese);
        }
        String jsonText = raw.trim();
        int start = jsonText.indexOf('{');
        int end = jsonText.lastIndexOf('}');
        if (start >= 0 && end > start) {
            jsonText = jsonText.substring(start, end + 1);
        }
        try {
            JSONObject json = new JSONObject(jsonText);
            String appName = value(json, "appName", fallbackName);
            if (appName == null || appName.trim().isEmpty()) {
                appName = NameUtils.projectNameFromPrompt(prompt);
            }
            String packageName = value(json, "packageName", NameUtils.packageNameFromProject(appName));
            String description = value(json, "description", prompt);
            String entityName = value(json, "entityName", chinese ? "记录" : "Item");
            String primaryField = value(json, "primaryField", chinese ? "标题" : "Title");
            String secondaryField = value(json, "secondaryField", chinese ? "备注" : "Notes");
            return new AppSpec(appName, packageName, description, entityName, primaryField, secondaryField, chinese ? "zh" : "en");
        } catch (Exception ignored) {
            return fromPrompt(prompt, fallbackName, chinese);
        }
    }

    private static String value(JSONObject json, String key, String fallback) {
        String value = json.optString(key, fallback);
        return value == null ? fallback : value.trim();
    }
}
