package com.androidbuilder.ui;

import java.util.ArrayList;
import java.util.List;

public final class PlanCardSummaryPolicy {
    private static final int MAX_SECTIONS = 4;

    private PlanCardSummaryPolicy() {
    }

    public static boolean isPlanMessage(String content) {
        String text = content == null ? "" : content.trim();
        return text.startsWith("# 工程计划") || text.startsWith("# Engineering Plan");
    }

    public static String summary(String content, boolean chinese) {
        List<String> sections = sections(content);
        if (sections.isEmpty()) {
            return chinese ? "完整计划已收起" : "Full plan collapsed";
        }
        StringBuilder builder = new StringBuilder();
        for (String section : sections) {
            if (builder.length() > 0) {
                builder.append(" · ");
            }
            builder.append(section);
        }
        return builder.toString();
    }

    static List<String> sections(String content) {
        List<String> sections = new ArrayList<>();
        String[] lines = (content == null ? "" : content).split("\\r?\\n");
        for (String line : lines) {
            String title = sectionTitle(line);
            if (title.isEmpty()) {
                continue;
            }
            sections.add(title);
            if (sections.size() >= MAX_SECTIONS) {
                break;
            }
        }
        return sections;
    }

    private static String sectionTitle(String line) {
        String text = line == null ? "" : line.trim();
        if (!text.startsWith("## ")) {
            return "";
        }
        String title = text.replaceFirst("^#+\\s+", "").trim();
        if (title.isEmpty()
                || "工程计划".equals(title)
                || "Engineering Plan".equalsIgnoreCase(title)) {
            return "";
        }
        return title;
    }
}
