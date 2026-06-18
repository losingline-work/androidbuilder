package com.androidbuilder.agent;

import com.androidbuilder.model.TaskManifest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

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
        // Collapse to a single batch only when it is small by file count AND by content weight — a handful
        // of HEAVY files (e.g. complex layouts) must still be split, or one cloud response overflows
        // max_tokens, truncates, and the carry-forward keeps re-requesting the same oversized set forever
        // (project-14: 5 complex layouts looped to retry-exhaustion).
        if (files.size() <= SINGLE_BATCH_THRESHOLD && totalWeight(files) <= MAX_BATCH_WEIGHT) {
            return Collections.singletonList(Collections.unmodifiableList(new ArrayList<>(files)));
        }
        List<TaskManifest.Entry> ordered = new ArrayList<>();
        for (int category = 0; category <= 4; category++) {
            List<TaskManifest.Entry> inCategory = new ArrayList<>();
            for (TaskManifest.Entry file : files) {
                if (categoryFor(file) == category) {
                    inCategory.add(file);
                }
            }
            if (category == 3) {
                // Producer-before-consumer: order Java by architectural tier (util/db foundation ->
                // entities -> DAOs -> repositories -> domain -> ui) so a class is generated before
                // any class that calls it, and the consumer batch sees the producer's real API in
                // its completed-files context instead of inventing a signature that won't match.
                Collections.sort(inCategory, Comparator
                        .comparingInt((TaskManifest.Entry e) -> javaTier(e == null ? "" : e.path))
                        .thenComparing(e -> e == null || e.path == null ? "" : e.path));
            }
            ordered.addAll(inCategory);
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

    private static int javaTier(String rawPath) {
        String path = rawPath == null ? "" : rawPath.toLowerCase(Locale.ROOT);
        // Foundation/leaves first: util (self-contained), App, and the DB contract/helper that every
        // DAO depends on.
        if (path.contains("/util") || path.endsWith("/app.java")
                || path.contains("/db/") || path.contains("/data/db")
                || path.contains("dbhelper") || path.contains("dbcontract")) {
            return 0;
        }
        if (path.contains("/entity/") || path.contains("/model/")) {
            return 1;
        }
        if (path.contains("/dao/")) {
            return 2;
        }
        if (path.contains("/repo")) {
            return 3;
        }
        if (path.contains("/domain/")) {
            return 4;
        }
        if (path.contains("/ui/")) {
            return 6;
        }
        return 5;
    }

    private static int totalWeight(List<TaskManifest.Entry> files) {
        int total = 0;
        for (TaskManifest.Entry file : files) {
            total += weightFor(file);
        }
        return total;
    }

    private static int weightFor(TaskManifest.Entry entry) {
        String path = entry == null || entry.path == null ? "" : entry.path;
        // A complex layout (a full Fragment screen) can fill a whole cloud response on its own; weight it
        // so at most ONE layout lands per batch (6 > MAX_BATCH_WEIGHT/2), which keeps every response well
        // under max_tokens and lets the carry-forward converge one layout at a time instead of re-truncating
        // a bundle of them. Small res XML (icons, menu, color selectors) still pack densely alongside it.
        if (path.startsWith("app/src/main/res/layout")) {
            return 6;
        }
        // Heavy value files: a full-featured app's strings.xml (hundreds of strings) and verbose Material
        // themes/arrays are NOT tiny — bundling the whole values/ set in one response overflows max_tokens
        // and loops the carry-forward (project-141: 7 value files in batch 1/1 truncated to exhaustion).
        if (path.endsWith("/strings.xml")) {
            return 4;
        }
        if (path.endsWith("/themes.xml") || path.endsWith("/arrays.xml") || path.endsWith("/styles.xml")) {
            return 3;
        }
        if (path.startsWith("app/src/main/res/values")) {
            return 2; // colors, dimens, and other values variants
        }
        if (path.startsWith("app/src/main/res/") && path.endsWith(".xml")) {
            return 1; // drawable, menu, color selectors, mipmap — small
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
