package com.androidbuilder.agent;

import com.androidbuilder.model.TaskManifest;
import com.androidbuilder.model.TaskOperations;

import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class BatchEscalationPolicy {
    private static final Pattern MISSING_RESOURCE = Pattern.compile(
            "Batch validation: missing (XML id|layout resource|string resource|color resource|drawable resource|mipmap resource|style resource) (R\\.(id|layout|string|color|drawable|mipmap|style)\\.([A-Za-z0-9_]+))");

    private BatchEscalationPolicy() {
    }

    static TaskOperations blockedIfManifestCannotProduce(String validationError, TaskManifest manifest) {
        Matcher matcher = MISSING_RESOURCE.matcher(validationError == null ? "" : validationError);
        if (!matcher.find()) {
            return null;
        }
        String resourceType = matcher.group(3);
        String symbol = matcher.group(2);
        if (manifestCanProduce(resourceType, manifest)) {
            return null;
        }
        String reason = "Batch validation requires missing resource " + symbol
                + ", but the task manifest does not include any file that can produce that resource.";
        return new TaskOperations(
                "Task blocked by missing prerequisite resource.",
                Collections.emptyList(),
                true,
                reason,
                "Add or plan the missing Android resource for " + symbol + " before generating the Java file.");
    }

    private static boolean manifestCanProduce(String resourceType, TaskManifest manifest) {
        if (manifest == null || manifest.files == null) {
            return false;
        }
        for (TaskManifest.Entry entry : manifest.files) {
            String path = entry == null || entry.path == null ? "" : entry.path;
            if ("id".equals(resourceType)) {
                if (path.startsWith("app/src/main/res/layout/") || path.startsWith("app/src/main/res/values/")) {
                    return true;
                }
            } else if ("layout".equals(resourceType)) {
                if (path.startsWith("app/src/main/res/layout/")) {
                    return true;
                }
            } else if ("drawable".equals(resourceType)) {
                if (path.startsWith("app/src/main/res/drawable/") || path.startsWith("app/src/main/res/values/")) {
                    return true;
                }
            } else if ("mipmap".equals(resourceType)) {
                if (path.startsWith("app/src/main/res/mipmap/")) {
                    return true;
                }
            } else if ("string".equals(resourceType) || "color".equals(resourceType) || "style".equals(resourceType)) {
                if (path.startsWith("app/src/main/res/values/")) {
                    return true;
                }
            }
        }
        return false;
    }
}
