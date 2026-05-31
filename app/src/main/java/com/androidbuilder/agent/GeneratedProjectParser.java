package com.androidbuilder.agent;

import com.androidbuilder.model.GeneratedProject;
import com.androidbuilder.model.GeneratedProjectFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class GeneratedProjectParser {
    private static final String[] REQUIRED_FILES = {
            "settings.gradle",
            "build.gradle",
            "app/build.gradle",
            "app/src/main/AndroidManifest.xml"
    };

    private GeneratedProjectParser() {
    }

    public static GeneratedProject fromJson(String raw) throws Exception {
        if (raw == null || raw.trim().isEmpty()) {
            throw new IllegalArgumentException("Generated project JSON is empty.");
        }
        JSONObject json = new JSONObject(extractJson(raw));
        JSONArray filesJson = json.optJSONArray("files");
        if (filesJson == null || filesJson.length() == 0) {
            throw new IllegalArgumentException("Generated project must include a non-empty files array.");
        }

        List<GeneratedProjectFile> files = new ArrayList<>();
        Set<String> paths = new HashSet<>();
        for (int i = 0; i < filesJson.length(); i++) {
            JSONObject fileJson = filesJson.getJSONObject(i);
            String path = PathValidator.normalizeGeneratedPath(fileJson.optString("path", ""));
            if (!paths.add(path)) {
                throw new IllegalArgumentException("Duplicate generated file path: " + path);
            }
            files.add(new GeneratedProjectFile(path, fileJson.optString("content", "")));
        }
        requireAndroidProjectFiles(paths);
        String appName = json.optString("appName", "Generated App").trim();
        if (appName.isEmpty()) {
            appName = "Generated App";
        }
        String packageName = json.optString("packageName", "").trim();
        if (!isPackageName(packageName)) {
            throw new IllegalArgumentException("Generated project has invalid packageName: " + packageName);
        }
        return new GeneratedProject(
                appName,
                packageName,
                json.optString("description", "").trim(),
                files
        );
    }

    private static String extractJson(String raw) {
        String text = raw.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("Generated project response did not contain a JSON object.");
        }
        return text.substring(start, end + 1);
    }

    private static void requireAndroidProjectFiles(Set<String> paths) {
        for (String required : REQUIRED_FILES) {
            if (!paths.contains(required)) {
                throw new IllegalArgumentException("Generated project is missing required file: " + required);
            }
        }
    }

    private static boolean isPackageName(String value) {
        return value != null && value.matches("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+");
    }
}
