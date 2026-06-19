package com.androidbuilder.agent;

import com.androidbuilder.model.ProjectMilestoneRecord;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the model's feature-slice list into the final ordered milestone list: it ALWAYS prepends a canonical,
 * deterministic milestone 0 (the runnable skeleton) so the single most load-bearing step never depends on a
 * weak model getting it right, reindexes the model's slices as contiguous M1..N, and caps the count as a
 * runaway guard. The model is instructed to return only feature slices (no skeleton).
 */
public final class MilestonePlanPolicy {
    /** Safety bound on feature slices (excluding the skeleton); a pathological list is truncated, not trusted. */
    public static final int MAX_FEATURE_MILESTONES = 20;

    private MilestonePlanPolicy() {
    }

    /** The canonical, model-independent runnable-skeleton milestone (always order 0). */
    public static ProjectMilestoneRecord skeletonMilestone(boolean chinese) {
        String title = chinese ? "可运行骨架" : "Runnable skeleton";
        String description = chinese
                ? "搭建可运行骨架：Gradle 配置、AndroidManifest、启动 Activity 与一个空的首页布局、基础主题。目标是 app 能编译、安装并启动到首页（暂不含业务功能）。"
                : "Stand up a runnable skeleton: Gradle config, AndroidManifest, a launcher Activity with an empty home layout, and a base theme. Goal: the app compiles, installs and launches to the home screen (no features yet).";
        String slice = chinese
                ? "可运行骨架（Gradle + 启动 Activity + 空首页 + 基础主题）"
                : "runnable skeleton (Gradle + launcher Activity + empty home + base theme)";
        return new ProjectMilestoneRecord(0, 0, 0, title, description, slice, MilestoneStatus.PENDING, "", 0, 0, 0, 0);
    }

    /**
     * Prepend the canonical skeleton as M0 and append the model's feature slices as M1..N (reindexed
     * contiguously, capped at {@link #MAX_FEATURE_MILESTONES}). Null/empty slice lists still yield a valid
     * single-milestone plan (just the skeleton) — the app at least builds and runs.
     */
    public static List<ProjectMilestoneRecord> normalize(List<ProjectMilestoneRecord> featureSlices, boolean chinese) {
        List<ProjectMilestoneRecord> out = new ArrayList<>();
        out.add(skeletonMilestone(chinese));
        if (featureSlices != null) {
            int limit = Math.min(featureSlices.size(), MAX_FEATURE_MILESTONES);
            for (int i = 0; i < limit; i++) {
                ProjectMilestoneRecord m = featureSlices.get(i);
                out.add(new ProjectMilestoneRecord(
                        0, 0, i + 1, m.title, m.description, m.slice,
                        MilestoneStatus.PENDING, "", 0, 0, 0, 0));
            }
        }
        return out;
    }

    /** True when {@link #normalize} dropped trailing feature slices past the cap (so the caller can warn). */
    public static boolean truncated(List<ProjectMilestoneRecord> featureSlices) {
        return featureSlices != null && featureSlices.size() > MAX_FEATURE_MILESTONES;
    }
}
