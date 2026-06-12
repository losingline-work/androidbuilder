package com.androidbuilder.agent;

import com.androidbuilder.model.TaskManifest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class ManifestBatchPolicy {
    static final int SINGLE_BATCH_THRESHOLD = 6;
    // Batches are sized by content weight, not file count: tiny resource XML (icons, values)
    // packs densely while layouts and Java batch sparsely, so one cloud call stays small and a
    // failed batch loses little work.
    static final int MAX_BATCH_WEIGHT = 10;

    private ManifestBatchPolicy() {
    }

    static List<List<TaskManifest.Entry>> batches(List<TaskManifest.Entry> files) {
        if (files == null || files.isEmpty()) {
            return Collections.emptyList();
        }
        if (files.size() <= SINGLE_BATCH_THRESHOLD) {
            return Collections.singletonList(Collections.unmodifiableList(new ArrayList<>(files)));
        }
        List<TaskManifest.Entry> ordered = new ArrayList<>();
        for (int category = 0; category <= 4; category++) {
            for (TaskManifest.Entry file : files) {
                if (categoryFor(file) == category) {
                    ordered.add(file);
                }
            }
        }
        List<List<TaskManifest.Entry>> batches = new ArrayList<>();
        List<TaskManifest.Entry> current = new ArrayList<>();
        int currentWeight = 0;
        for (TaskManifest.Entry file : ordered) {
            int weight = weightFor(file);
            if (!current.isEmpty() && currentWeight + weight > MAX_BATCH_WEIGHT) {
                batches.add(Collections.unmodifiableList(current));
                current = new ArrayList<>();
                currentWeight = 0;
            }
            current.add(file);
            currentWeight += weight;
        }
        if (!current.isEmpty()) {
            batches.add(Collections.unmodifiableList(current));
        }
        return Collections.unmodifiableList(batches);
    }

    private static int weightFor(TaskManifest.Entry entry) {
        String path = entry == null || entry.path == null ? "" : entry.path;
        if (path.startsWith("app/src/main/res/layout")) {
            return 2;
        }
        if (path.startsWith("app/src/main/res/") && path.endsWith(".xml")) {
            return 1;
        }
        return 3;
    }

    private static int categoryFor(TaskManifest.Entry entry) {
        String path = entry == null || entry.path == null ? "" : entry.path;
        if (path.startsWith("app/src/main/res/values/")) {
            return 0;
        }
        if (path.startsWith("app/src/main/res/")) {
            return 1;
        }
        if (isConfig(path)) {
            return 2;
        }
        if (path.endsWith(".java")) {
            return 3;
        }
        return 4;
    }

    private static boolean isConfig(String path) {
        return "settings.gradle".equals(path)
                || "build.gradle".equals(path)
                || "gradle.properties".equals(path)
                || "app/build.gradle".equals(path)
                || "app/src/main/AndroidManifest.xml".equals(path)
                || path.startsWith("gradle/")
                || path.endsWith(".gradle")
                || path.endsWith(".properties");
    }
}
