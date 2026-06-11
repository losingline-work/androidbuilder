package com.androidbuilder.ui;

import com.androidbuilder.model.AiConversationRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class HermesDecisionTimelinePolicy {
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
