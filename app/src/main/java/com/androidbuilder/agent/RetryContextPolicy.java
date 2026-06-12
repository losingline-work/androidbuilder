package com.androidbuilder.agent;

import java.util.Locale;
import java.util.ArrayList;
import java.util.List;

final class RetryContextPolicy {
    private static final String ADDITIONAL_HEADER = "Additional retry signal:";
    private static final int MAX_ADDITIONAL_SIGNALS = 2;
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
        RetrySignals signals = RetrySignals.from(cleanExisting);
        if (signals.contains(cleanAddition)) {
            return signals.assemble();
        }
        signals.add(cleanAddition);
        return signals.assemble();
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
            if (trimmed.startsWith("Negotiated patch intent") || trimmed.startsWith("Negotiated risk notes")) {
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

    private static final class RetrySignals {
        final String base;
        final List<String> additional;

        RetrySignals(String base, List<String> additional) {
            this.base = base == null ? "" : base.trim();
            this.additional = additional;
        }

        static RetrySignals from(String text) {
            String clean = text == null ? "" : text.trim();
            if (clean.startsWith(ADDITIONAL_HEADER + "\n")) {
                String value = clean.substring((ADDITIONAL_HEADER + "\n").length()).trim();
                List<String> additional = new ArrayList<>();
                if (!value.isEmpty()) {
                    additional.add(value);
                }
                return new RetrySignals("", additional);
            }
            String marker = "\n\n" + ADDITIONAL_HEADER + "\n";
            String[] parts = clean.split(java.util.regex.Pattern.quote(marker), -1);
            String base = parts.length == 0 ? "" : parts[0].trim();
            List<String> additional = new ArrayList<>();
            for (int i = 1; i < parts.length; i++) {
                String value = parts[i].trim();
                if (!value.isEmpty() && !contains(additional, value)) {
                    additional.add(value);
                }
            }
            return new RetrySignals(base, additional);
        }

        boolean contains(String value) {
            String clean = value == null ? "" : value.trim();
            return clean.equals(base) || contains(additional, clean);
        }

        void add(String value) {
            String clean = value == null ? "" : value.trim();
            if (clean.isEmpty() || contains(clean)) {
                return;
            }
            additional.add(clean);
            while (additional.size() > MAX_ADDITIONAL_SIGNALS) {
                additional.remove(0);
            }
        }

        String assemble() {
            if (base.isEmpty() && additional.size() == 1) {
                return additional.get(0);
            }
            StringBuilder builder = new StringBuilder(base);
            for (String value : additional) {
                if (builder.length() > 0) {
                    builder.append("\n\n");
                }
                builder.append(ADDITIONAL_HEADER).append('\n').append(value);
            }
            return builder.toString().trim();
        }

        private static boolean contains(List<String> values, String value) {
            for (String existing : values) {
                if (existing.equals(value)) {
                    return true;
                }
            }
            return false;
        }
    }
}
