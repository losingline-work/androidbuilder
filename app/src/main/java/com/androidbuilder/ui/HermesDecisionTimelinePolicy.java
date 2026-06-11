package com.androidbuilder.ui;

import com.androidbuilder.model.AiConversationRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class HermesDecisionTimelinePolicy {
    private static final int TASK_ITEM_LIMIT = 5;

    private HermesDecisionTimelinePolicy() {
    }

    public static List<HermesDecisionTimelineItem> fromRecords(List<AiConversationRecord> records) {
        if (records == null || records.isEmpty()) {
            return Collections.emptyList();
        }
        List<HermesDecisionTimelineItem> items = new ArrayList<>();
        for (AiConversationRecord record : records) {
            if (!isHermesRecord(record)) {
                continue;
            }
            items.add(new HermesDecisionTimelineItem(
                    valueFor(record.metadata, "hermesPhase", fallbackPhase(record)),
                    valueFor(record.metadata, "hermesRole", fallbackRole(record)),
                    firstValue(record.responseText, "decision", record.status),
                    summary(record),
                    record.createdAt));
        }
        return items;
    }

    public static List<HermesDecisionTimelineItem> forTask(List<AiConversationRecord> records, long taskId) {
        if (records == null || records.isEmpty() || taskId <= 0) {
            return Collections.emptyList();
        }
        List<AiConversationRecord> filtered = new ArrayList<>();
        for (AiConversationRecord record : records) {
            if (record != null && taskId == taskId(record.metadata)) {
                filtered.add(record);
            }
        }
        Collections.sort(filtered, (left, right) -> Long.compare(right.createdAt, left.createdAt));
        List<HermesDecisionTimelineItem> items = fromRecords(filtered);
        if (items.size() <= TASK_ITEM_LIMIT) {
            return items;
        }
        return new ArrayList<>(items.subList(0, TASK_ITEM_LIMIT));
    }

    private static boolean isHermesRecord(AiConversationRecord record) {
        if (record == null) {
            return false;
        }
        String source = record.source == null ? "" : record.source.toLowerCase(Locale.ROOT);
        String title = record.title == null ? "" : record.title.toLowerCase(Locale.ROOT);
        String metadata = record.metadata == null ? "" : record.metadata.toLowerCase(Locale.ROOT);
        return "hermes".equals(source) || title.contains("hermes") || metadata.contains("hermes");
    }

    private static String summary(AiConversationRecord record) {
        String output = blockAfter(record.responseText, "outputSummary:");
        if (!output.isEmpty()) {
            return firstLine(output);
        }
        String summary = firstValue(record.responseText, "summary", "");
        if (!summary.isEmpty()) {
            return summary;
        }
        String reason = blockAfter(record.requestText, "reason:");
        if (!reason.isEmpty()) {
            return firstLine(reason);
        }
        return record.title == null ? "" : record.title;
    }

    private static String fallbackPhase(AiConversationRecord record) {
        String fromRequest = firstValue(record == null ? "" : record.requestText, "phase", "");
        if (!fromRequest.isEmpty()) {
            return fromRequest;
        }
        return "";
    }

    private static String fallbackRole(AiConversationRecord record) {
        String fromRequest = firstValue(record == null ? "" : record.requestText, "role", "");
        if (!fromRequest.isEmpty()) {
            return fromRequest;
        }
        return "";
    }

    private static String valueFor(String metadata, String key, String fallback) {
        String prefix = key + "=";
        String[] lines = (metadata == null ? "" : metadata).split("\n");
        for (String line : lines) {
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length()).trim();
            }
        }
        return fallback == null ? "" : fallback;
    }

    private static long taskId(String metadata) {
        String value = valueFor(metadata, "taskId", "");
        if (value.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String firstValue(String text, String key, String fallback) {
        String prefix = key + ":";
        String[] lines = (text == null ? "" : text).split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith(prefix)) {
                return trimmed.substring(prefix.length()).trim();
            }
        }
        return fallback == null ? "" : fallback.trim();
    }

    private static String blockAfter(String text, String marker) {
        String value = text == null ? "" : text;
        int index = value.indexOf(marker);
        if (index < 0) {
            return "";
        }
        return value.substring(index + marker.length()).trim();
    }

    private static String firstLine(String text) {
        String[] lines = (text == null ? "" : text.trim()).split("\n");
        return lines.length == 0 ? "" : lines[0].trim();
    }
}
