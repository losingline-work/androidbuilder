package com.androidbuilder.agent;

import com.androidbuilder.model.ProjectTaskRecord;

import java.util.List;
import java.util.Locale;

/**
 * Decides when, during plan execution, the accumulated source is at a tier boundary worth validating
 * incrementally instead of dumping every error onto one final build:
 *
 * <ul>
 *   <li>once every RESOURCE-tier task (values/themes/menu, drawable/layout) is done and a CODE task is
 *       still pending, run aapt resource linking - it validates every resource reference and produces a
 *       real R.java, so the Java wiring tier then generates against verified resources;</li>
 *   <li>once every CODE-tier task is done, run javac - it type-checks against that real R.java.</li>
 * </ul>
 *
 * Pure decision logic; the orchestrator does the I/O (running aapt/javac and repairing on failure) and
 * tracks which checkpoints it has already run so a checkpoint fires once per tier completion.
 */
public final class TierValidationPolicy {

    public enum Tier { GRADLE, RESOURCE, CODE, OTHER }

    private TierValidationPolicy() {
    }

    public static Tier tierOf(String taskTitle) {
        if (taskTitle == null) {
            return Tier.OTHER;
        }
        String title = taskTitle.trim();
        if (CanonicalTaskPhase.is(title, CanonicalTaskPhase.Phase.GRADLE)) {
            return Tier.GRADLE;
        }
        if (CanonicalTaskPhase.is(title, CanonicalTaskPhase.Phase.RESOURCES)
                || CanonicalTaskPhase.is(title, CanonicalTaskPhase.Phase.DRAWABLE_LAYOUT)) {
            return Tier.RESOURCE;
        }
        if (CanonicalTaskPhase.is(title, CanonicalTaskPhase.Phase.JAVA)) {
            return Tier.CODE;
        }
        // Fallback for model-titled tasks that skipped canned normalization.
        String lower = title.toLowerCase(Locale.ROOT);
        if (lower.contains("gradle") || lower.contains("manifest skeleton")) {
            return Tier.GRADLE;
        }
        if (lower.contains(".java") || lower.contains("java ") || lower.contains("wiring")
                || lower.contains("activity") || lower.contains("adapter") || lower.contains("fragment")) {
            return Tier.CODE;
        }
        if (lower.contains("layout") || lower.contains("drawable") || lower.contains("values")
                || lower.contains("resource") || lower.contains("theme") || lower.contains("menu")
                || lower.contains("color") || lower.contains("style")) {
            return Tier.RESOURCE;
        }
        return Tier.OTHER;
    }

    /** True when every resource-tier task is done and at least one code-tier task is still pending. */
    public static boolean shouldValidateResources(List<ProjectTaskRecord> tasks) {
        if (tasks == null) {
            return false;
        }
        boolean anyResource = false;
        boolean anyPendingCode = false;
        for (ProjectTaskRecord task : tasks) {
            Tier tier = tierOf(task == null ? null : task.title);
            if (tier == Tier.RESOURCE) {
                anyResource = true;
                if (!isDone(task)) {
                    return false;
                }
            } else if (tier == Tier.CODE && !isDone(task)) {
                anyPendingCode = true;
            }
        }
        return anyResource && anyPendingCode;
    }

    /** True when every code-tier task is done (the code tier is complete and worth a javac check). */
    public static boolean shouldValidateCode(List<ProjectTaskRecord> tasks) {
        if (tasks == null) {
            return false;
        }
        boolean anyCode = false;
        for (ProjectTaskRecord task : tasks) {
            if (tierOf(task == null ? null : task.title) == Tier.CODE) {
                anyCode = true;
                if (!isDone(task)) {
                    return false;
                }
            }
        }
        return anyCode;
    }

    private static boolean isDone(ProjectTaskRecord task) {
        return task != null && task.status != null && "done".equalsIgnoreCase(task.status.trim());
    }
}
