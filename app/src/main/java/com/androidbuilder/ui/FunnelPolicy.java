package com.androidbuilder.ui;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Renders the weak-model success funnel for one project — plan created → M0 skeleton green → feature
 * milestones green → APK built → installed — plus the task-operations parse-outcome mix. This is the
 * measuring stick for "80% failure": each stage says how far a run got, and the outcome mix attributes WHY
 * generation stalled (json_ok vs salvaged/parse_failed truncation-and-garbage). Pure so it is unit-tested;
 * the activity gathers the counts from the repository and shows the text.
 */
final class FunnelPolicy {
    private FunnelPolicy() {
    }

    static String summary(boolean planCreated, int totalMilestones, int milestonesGreen,
                          boolean apkBuilt, boolean installed,
                          Map<String, Integer> outcomeCounts, boolean chinese) {
        StringBuilder out = new StringBuilder();
        boolean m0Green = milestonesGreen >= 1;
        boolean allGreen = totalMilestones > 0 && milestonesGreen >= totalMilestones;
        out.append(chinese ? "生成漏斗\n" : "Generation funnel\n");
        out.append(stage(planCreated, chinese ? "计划已创建" : "Plan created"));
        out.append(stage(m0Green, chinese ? "骨架 M0 构建绿" : "Skeleton M0 green"));
        out.append(stage(allGreen,
                (chinese ? "里程碑全绿 " : "All milestones green ") + milestonesGreen + "/" + Math.max(totalMilestones, milestonesGreen)));
        out.append(stage(apkBuilt, chinese ? "APK 已生成" : "APK built"));
        out.append(stage(installed, chinese ? "已安装运行" : "Installed"));

        String outcomes = outcomeMix(outcomeCounts, chinese);
        if (!outcomes.isEmpty()) {
            out.append('\n').append(chinese ? "生成调用解析结果\n" : "Generation call parse outcomes\n").append(outcomes);
        }
        return out.toString().trim();
    }

    private static String stage(boolean reached, String label) {
        return (reached ? "✓ " : "○ ") + label + "\n";
    }

    /** The parse-outcome mix (ok / salvaged-truncation / parse-failed), each with a share, most-common first. */
    static String outcomeMix(Map<String, Integer> outcomeCounts, boolean chinese) {
        if (outcomeCounts == null || outcomeCounts.isEmpty()) {
            return "";
        }
        // Only the task-operations parse outcomes are meaningful here; other cloud-call statuses are ignored.
        LinkedHashMap<String, Integer> relevant = new LinkedHashMap<>();
        int total = 0;
        for (Map.Entry<String, Integer> entry : outcomeCounts.entrySet()) {
            if (isParseOutcome(entry.getKey())) {
                relevant.put(entry.getKey(), entry.getValue());
                total += entry.getValue();
            }
        }
        if (total == 0) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Integer> entry : relevant.entrySet()) {
            int pct = (int) Math.round(100.0 * entry.getValue() / total);
            out.append("- ").append(label(entry.getKey(), chinese))
                    .append(": ").append(entry.getValue()).append(" (").append(pct).append("%)\n");
        }
        return out.toString();
    }

    private static boolean isParseOutcome(String status) {
        return "json_ok".equals(status) || "fenced_ok".equals(status) || "json_lenient".equals(status)
                || "json_salvaged".equals(status) || "parse_failed".equals(status);
    }

    private static String label(String status, boolean chinese) {
        if (!chinese) {
            return status;
        }
        switch (status) {
            case "fenced_ok":
                return "fenced 正常";
            case "json_ok":
                return "JSON 正常";
            case "json_lenient":
                return "JSON 宽容修复";
            case "json_salvaged":
                return "截断救回";
            case "parse_failed":
                return "解析失败";
            default:
                return status;
        }
    }
}
