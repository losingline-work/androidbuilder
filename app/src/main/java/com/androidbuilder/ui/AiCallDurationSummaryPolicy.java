package com.androidbuilder.ui;

import com.androidbuilder.model.AiConversationRecord;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AiCallDurationSummaryPolicy {
    private static final Pattern DURATION_PATTERN = Pattern.compile("(?m)^durationMs\\s*=\\s*(\\d+)\\s*$");

    private AiCallDurationSummaryPolicy() {
    }

    public static Summary summarize(List<AiConversationRecord> records) {
        Summary summary = new Summary();
        if (records == null) {
            return summary;
        }
        for (AiConversationRecord record : records) {
            if (record == null) {
                continue;
            }
            long durationMs = durationMs(record.metadata);
            summary.totalCount++;
            summary.totalMs += durationMs;
            bucketFor(summary, record.title).add(durationMs);
        }
        return summary;
    }

    public static String format(Summary summary, boolean chinese) {
        if (summary == null || summary.totalCount <= 0) {
            return "";
        }
        String label = chinese ? "云端 " + summary.totalCount + " 次" : "Cloud " + summary.totalCount + " calls";
        String total = (chinese ? "总 " : "total ") + formatDuration(summary.totalMs);
        return label + " · " + total
                + " · coder " + formatDuration(summary.coder.totalMs)
                + " / reviewer " + formatDuration(summary.reviewer.totalMs)
                + " / scout " + formatDuration(summary.scout.totalMs);
    }

    private static Bucket bucketFor(Summary summary, String title) {
        String text = title == null ? "" : title.toLowerCase(Locale.ROOT);
        if (text.contains("task operations") || text.contains("文件操作生成")) {
            return summary.coder;
        }
        if (text.contains("review") || text.contains("审查")) {
            return summary.reviewer;
        }
        if (text.contains("context scout") || text.contains("侦察")) {
            return summary.scout;
        }
        if (text.contains("plan") || text.contains("计划")) {
            return summary.planner;
        }
        return summary.other;
    }

    private static long durationMs(String metadata) {
        Matcher matcher = DURATION_PATTERN.matcher(metadata == null ? "" : metadata);
        if (!matcher.find()) {
            return 0;
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String formatDuration(long durationMs) {
        long seconds = Math.max(0, Math.round(durationMs / 1000.0d));
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long remainingSeconds = seconds % 60;
        StringBuilder text = new StringBuilder();
        if (hours > 0) {
            text.append(hours).append('h');
        }
        if (minutes > 0) {
            text.append(minutes).append('m');
        }
        if (remainingSeconds > 0 || text.length() == 0) {
            text.append(remainingSeconds).append('s');
        }
        return text.toString();
    }

    public static final class Summary {
        public int totalCount;
        public long totalMs;
        public final Bucket coder = new Bucket();
        public final Bucket reviewer = new Bucket();
        public final Bucket scout = new Bucket();
        public final Bucket planner = new Bucket();
        public final Bucket other = new Bucket();
    }

    public static final class Bucket {
        public int count;
        public long totalMs;

        public long avgMs() {
            return count == 0 ? 0 : totalMs / count;
        }

        private void add(long durationMs) {
            count++;
            totalMs += durationMs;
        }
    }
}
