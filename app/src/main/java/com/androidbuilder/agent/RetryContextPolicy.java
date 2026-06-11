package com.androidbuilder.agent;

import java.util.Locale;

final class RetryContextPolicy {
    private static final String SINGLE_FILE_OVERRIDE =
            "Reviewer rewrite scope overrides earlier negotiated patch intent or risk notes for this retry.";

    private RetryContextPolicy() {
    }

    static String merge(String existing, String addition) {
        String cleanExisting = existing == null ? "" : existing.trim();
        String cleanAddition = addition == null ? "" : addition.trim();
        if (cleanAddition.isEmpty()) {
            return cleanExisting;
        }
        if (isSingleFileRewrite(cleanAddition)) {
            cleanExisting = withoutNegotiatedScoutBlocks(cleanExisting);
            cleanExisting = append(cleanExisting, SINGLE_FILE_OVERRIDE);
        }
        if (cleanExisting.isEmpty()) {
            return cleanAddition;
        }
        return cleanExisting + "\n\nAdditional retry signal:\n" + cleanAddition;
    }

    private static boolean isSingleFileRewrite(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        boolean scoped = lower.contains("rewrite only ")
                || lower.contains("single write operation")
                || lower.contains("only path=")
                || lower.contains("only the malformed")
                || text.contains("只重写")
                || text.contains("这一个文件")
                || text.contains("单一 write 操作");
        return scoped && lower.matches("(?s).*app/src/main/[^\\s,，;；)）]+\\.(java|xml|gradle|properties).*");
    }

    private static String withoutNegotiatedScoutBlocks(String text) {
        if (text == null || text.trim().isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        boolean skipping = false;
        String[] lines = text.split("\n", -1);
        for (String line : lines) {
            String trimmed = line.trim();
            if ("Negotiated patch intent:".equals(trimmed) || "Negotiated risk notes:".equals(trimmed)) {
                skipping = true;
                continue;
            }
            if (skipping) {
                if (trimmed.isEmpty()) {
                    skipping = false;
                }
                continue;
            }
            builder.append(line).append('\n');
        }
        return collapseBlankLines(builder.toString().trim());
    }

    private static String append(String existing, String addition) {
        if (addition == null || addition.trim().isEmpty()) {
            return existing == null ? "" : existing.trim();
        }
        if (existing == null || existing.trim().isEmpty()) {
            return addition.trim();
        }
        if (existing.contains(addition.trim())) {
            return existing.trim();
        }
        return existing.trim() + "\n\n" + addition.trim();
    }

    private static String collapseBlankLines(String text) {
        return text.replaceAll("\\n{3,}", "\n\n").trim();
    }
}
