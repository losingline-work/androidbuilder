package com.androidbuilder.ui;

import com.androidbuilder.agent.HermesTaskContractCodec;
import com.androidbuilder.model.HermesTaskContract;
import com.androidbuilder.model.ProjectTaskRecord;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Turns a task's raw instruction (human prose + an embedded Hermes contract blob) into a clean,
 * structured detail for the task card: a plain description, the concrete files this task produces
 * (its sub-steps), its acceptance checks, and its dependencies - instead of dumping the contract
 * JSON and technical noise the old "substeps" view showed.
 */
public final class TaskDetailPolicy {
    private static final int MAX_PROSE_CHARS = 400;
    private static final int MAX_LIST = 12;

    private TaskDetailPolicy() {
    }

    public static Detail of(ProjectTaskRecord task) {
        String instruction = task == null || task.instruction == null ? "" : task.instruction;
        String description = compact(HermesTaskContractCodec.stripFromInstruction(instruction), MAX_PROSE_CHARS);
        HermesTaskContract contract = HermesTaskContractCodec.extractFromInstruction(instruction);
        List<String> outputs = fileNames(contract == null ? null : contract.expectedFiles);
        if (outputs.isEmpty() && contract != null) {
            outputs = trimmedList(contract.produces);
        }
        List<String> checks = contract == null ? new ArrayList<>() : trimmedList(contract.acceptanceChecks);
        List<String> dependsOn = contract == null ? new ArrayList<>() : trimmedList(contract.dependsOn);
        return new Detail(description, outputs, checks, dependsOn);
    }

    private static List<String> fileNames(List<String> paths) {
        Set<String> names = new LinkedHashSet<>();
        if (paths != null) {
            for (String path : paths) {
                if (path == null || path.trim().isEmpty()) {
                    continue;
                }
                names.add(new File(path.trim()).getName());
            }
        }
        return capped(new ArrayList<>(names));
    }

    private static List<String> trimmedList(List<String> values) {
        List<String> out = new ArrayList<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.trim().isEmpty()) {
                    out.add(value.trim());
                }
            }
        }
        return capped(out);
    }

    private static List<String> capped(List<String> values) {
        if (values.size() <= MAX_LIST) {
            return values;
        }
        List<String> capped = new ArrayList<>(values.subList(0, MAX_LIST));
        capped.add("… +" + (values.size() - MAX_LIST));
        return capped;
    }

    private static String compact(String value, int max) {
        String text = value == null ? "" : value.replace("\r", "").trim();
        // Drop the retry/repair scaffolding lines that are not part of the task's own description.
        StringBuilder kept = new StringBuilder();
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || isScaffolding(trimmed)) {
                continue;
            }
            if (kept.length() > 0) {
                kept.append(' ');
            }
            kept.append(trimmed);
        }
        String result = kept.toString().trim();
        if (result.length() <= max) {
            return result;
        }
        return result.substring(0, max).trim() + "…";
    }

    private static boolean isScaffolding(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.startsWith("additional retry")
                || lower.startsWith("previous failure")
                || lower.startsWith("previous draft")
                || lower.startsWith("previous rejected")
                || lower.startsWith("retry context")
                || lower.startsWith("this is a retry");
    }

    public static final class Detail {
        public final String description;
        public final List<String> outputs;
        public final List<String> acceptanceChecks;
        public final List<String> dependsOn;

        Detail(String description, List<String> outputs, List<String> acceptanceChecks, List<String> dependsOn) {
            this.description = description == null ? "" : description;
            this.outputs = outputs;
            this.acceptanceChecks = acceptanceChecks;
            this.dependsOn = dependsOn;
        }
    }
}
