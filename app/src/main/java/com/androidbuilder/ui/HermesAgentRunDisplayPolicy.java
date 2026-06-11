package com.androidbuilder.ui;

import com.androidbuilder.model.HermesAgentRunRecord;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class HermesAgentRunDisplayPolicy {
    private HermesAgentRunDisplayPolicy() {
    }

    public static Item item(HermesAgentRunRecord run, boolean chinese) {
        int batch = run == null ? 1 : Math.max(0, run.batchIndex) + 1;
        int agent = run == null ? 1 : Math.max(0, run.agentIndex) + 1;
        String status = status(run);
        String title = chinese
                ? "批次 " + batch + " · Agent " + agent
                : "Batch " + batch + " · Agent " + agent;
        StringBuilder subtitle = new StringBuilder(statusLabel(status, chinese));
        String detail = detail(run, status);
        if (!detail.isEmpty()) {
            subtitle.append(" · ").append(detail);
        }
        List<String> locks = lockedPaths(run == null ? "" : run.lockedPathsJson);
        if (!locks.isEmpty()) {
            subtitle.append("\n")
                    .append(chinese ? "锁定：" : "Locked: ")
                    .append(join(locks));
        }
        return new Item(title, subtitle.toString(), iconText(status));
    }

    public static String activeSummary(List<HermesAgentRunRecord> runs, boolean chinese) {
        List<HermesAgentRunRecord> active = activeRuns(runs);
        if (active.isEmpty()) {
            return "";
        }
        Collections.sort(active, new Comparator<HermesAgentRunRecord>() {
            @Override
            public int compare(HermesAgentRunRecord left, HermesAgentRunRecord right) {
                if (left.batchIndex != right.batchIndex) {
                    return left.batchIndex < right.batchIndex ? -1 : 1;
                }
                if (left.agentIndex != right.agentIndex) {
                    return left.agentIndex < right.agentIndex ? -1 : 1;
                }
                return Long.compare(left.id, right.id);
            }
        });
        StringBuilder builder = new StringBuilder();
        builder.append(chinese ? "正在执行 " : "Running ")
                .append(active.size())
                .append(chinese ? " 个子 Agent：" : " sub-agent(s): ");
        int count = 0;
        for (HermesAgentRunRecord run : active) {
            if (count > 0) {
                builder.append(chinese ? "；" : "; ");
            }
            if (count >= 3) {
                builder.append(chinese ? "还有 " : "+")
                        .append(active.size() - count)
                        .append(chinese ? " 个" : " more");
                break;
            }
            int agent = Math.max(0, run.agentIndex) + 1;
            String status = status(run);
            builder.append("Agent ").append(agent)
                    .append(chinese ? " " : " ")
                    .append(statusLabel(status, chinese));
            String firstLock = firstLockedPath(run.lockedPathsJson);
            if (!firstLock.isEmpty()) {
                builder.append(chinese ? " · " : " · ").append(firstLock);
            }
            count++;
        }
        return builder.toString();
    }

    private static List<HermesAgentRunRecord> activeRuns(List<HermesAgentRunRecord> runs) {
        if (runs == null || runs.isEmpty()) {
            return Collections.emptyList();
        }
        List<HermesAgentRunRecord> active = new ArrayList<>();
        for (HermesAgentRunRecord run : runs) {
            String status = status(run);
            if ("running".equals(status) || "merge_pending".equals(status)) {
                active.add(run);
            }
        }
        return active;
    }

    private static String detail(HermesAgentRunRecord run, String status) {
        if (run == null) {
            return "";
        }
        if ("failed".equals(status) && !run.errorSummary.isEmpty()) {
            return run.errorSummary;
        }
        return run.summary;
    }

    private static String status(HermesAgentRunRecord run) {
        if (run == null || run.status == null) {
            return "pending";
        }
        String status = run.status.trim().toLowerCase(Locale.ROOT);
        return status.isEmpty() ? "pending" : status;
    }

    private static String statusLabel(String status, boolean chinese) {
        if ("running".equals(status)) {
            return chinese ? "运行中" : "Running";
        }
        if ("merge_pending".equals(status)) {
            return chinese ? "等待合并" : "Merge pending";
        }
        if ("done".equals(status)) {
            return chinese ? "已完成" : "Done";
        }
        if ("failed".equals(status)) {
            return chinese ? "失败" : "Failed";
        }
        return chinese ? "待执行" : "Pending";
    }

    private static String iconText(String status) {
        if ("running".equals(status)) {
            return "...";
        }
        if ("merge_pending".equals(status)) {
            return "⇄";
        }
        if ("done".equals(status)) {
            return "✓";
        }
        if ("failed".equals(status)) {
            return "!";
        }
        return "·";
    }

    private static List<String> lockedPaths(String json) {
        List<String> paths = new ArrayList<>();
        String value = json == null ? "" : json.trim();
        if (value.isEmpty()) {
            return paths;
        }
        try {
            JSONArray array = new JSONArray(value);
            for (int i = 0; i < array.length(); i++) {
                String path = array.optString(i, "").trim();
                if (!path.isEmpty()) {
                    paths.add(path);
                }
            }
        } catch (JSONException ignored) {
            if (!value.isEmpty()) {
                paths.add(value);
            }
        }
        return paths;
    }

    private static String firstLockedPath(String json) {
        List<String> paths = lockedPaths(json);
        return paths.isEmpty() ? "" : paths.get(0);
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

    public static final class Item {
        public final String title;
        public final String subtitle;
        public final String iconText;

        Item(String title, String subtitle, String iconText) {
            this.title = title;
            this.subtitle = subtitle;
            this.iconText = iconText;
        }
    }
}
