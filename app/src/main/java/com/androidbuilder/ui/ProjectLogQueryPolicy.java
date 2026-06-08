package com.androidbuilder.ui;

import com.androidbuilder.model.ProjectLogEntry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class ProjectLogQueryPolicy {
    private ProjectLogQueryPolicy() {
    }

    public static List<ProjectLogEntry> filter(List<ProjectLogEntry> entries, String query) {
        List<ProjectLogEntry> results = new ArrayList<>();
        String[] tokens = tokens(query);
        if (entries != null) {
            for (ProjectLogEntry entry : entries) {
                if (entry != null && matches(entry, tokens)) {
                    results.add(entry);
                }
            }
        }
        results.sort(Comparator
                .comparingLong(ProjectLogEntry::displayTime)
                .reversed()
                .thenComparingInt(ProjectLogQueryPolicy::kindPriority)
                .thenComparing((ProjectLogEntry entry) -> entry.sourceId, Comparator.reverseOrder()));
        return results;
    }

    private static int kindPriority(ProjectLogEntry entry) {
        if (entry.kind == ProjectLogEntry.Kind.AI) {
            return 0;
        }
        if (entry.kind == ProjectLogEntry.Kind.MESSAGE) {
            return 1;
        }
        return 2;
    }

    public static String preview(String value, int maxChars) {
        String text = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        if (maxChars <= 0 || text.length() <= maxChars) {
            return text;
        }
        int end = Math.max(0, maxChars - 3);
        return text.substring(0, end).trim() + "...";
    }

    private static boolean matches(ProjectLogEntry entry, String[] tokens) {
        if (tokens.length == 0) {
            return true;
        }
        String haystack = entry.searchableText();
        for (String token : tokens) {
            if (!haystack.contains(token)) {
                return false;
            }
        }
        return true;
    }

    private static String[] tokens(String query) {
        String text = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return new String[0];
        }
        return text.split("\\s+");
    }
}
