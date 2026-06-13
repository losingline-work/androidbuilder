package com.androidbuilder.ui;

import com.androidbuilder.agent.StreamProgressRegistry;

import java.util.Locale;

public final class StreamProgressDisplayPolicy {
    private StreamProgressDisplayPolicy() {
    }

    public static String text(StreamProgressRegistry.StreamProgress progress, boolean chinese, long nowMs) {
        if (progress == null) {
            return "";
        }
        String phase = phaseLabel(progress.phase, chinese);
        String batch = batchLabel(progress.batchDone, progress.batchTotal, chinese);
        String attempt = attemptLabel(progress.attempt, progress.maxAttempts, chinese);
        String count = countLabel(progress.answerChars, progress.reasoningChars, chinese);
        String elapsed = elapsedLabel(progress.startedAt, nowMs);
        StringBuilder builder = new StringBuilder();
        appendPart(builder, phase);
        appendPart(builder, batch);
        appendPart(builder, attempt);
        appendPart(builder, count);
        appendPart(builder, elapsed);
        return builder.toString();
    }

    private static String phaseLabel(String phase, boolean chinese) {
        String value = phase == null ? "" : phase.trim().toLowerCase(Locale.US);
        if ("scouting".equals(value)) {
            return chinese ? "侦察中" : "scouting";
        }
        if ("manifest".equals(value)) {
            return chinese ? "列清单" : "manifest";
        }
        if ("coding".equals(value)) {
            return chinese ? "生成中" : "coding";
        }
        if ("reviewing".equals(value)) {
            return chinese ? "审查中" : "reviewing";
        }
        if ("merging".equals(value)) {
            return chinese ? "合并中" : "merging";
        }
        return "";
    }

    private static String batchLabel(int batchDone, int batchTotal, boolean chinese) {
        if (batchTotal <= 0 || batchDone <= 0) {
            return "";
        }
        return (chinese ? "批次 " : "batch ") + batchDone + "/" + batchTotal;
    }

    private static String attemptLabel(int attempt, int maxAttempts, boolean chinese) {
        if (attempt <= 0) {
            return "";
        }
        if (maxAttempts > 0) {
            return chinese ? "第 " + attempt + "/" + maxAttempts + " 次" : "attempt " + attempt + "/" + maxAttempts;
        }
        return chinese ? "第 " + attempt + " 次" : "attempt " + attempt;
    }

    private static String countLabel(int answerChars, int reasoningChars, boolean chinese) {
        if (answerChars > 0) {
            return compact(answerChars) + (chinese ? " 字" : " chars");
        }
        if (reasoningChars > 0) {
            return (chinese ? "思考 " : "thinking ") + compact(reasoningChars) + (chinese ? " 字" : " chars");
        }
        return "";
    }

    private static String elapsedLabel(long startedAt, long nowMs) {
        if (startedAt <= 0 || nowMs <= startedAt) {
            return "";
        }
        long seconds = Math.max(1, Math.round((nowMs - startedAt) / 1000.0d));
        long minutes = seconds / 60;
        long remainingSeconds = seconds % 60;
        if (minutes > 0) {
            return minutes + "m" + (remainingSeconds == 0 ? "" : remainingSeconds + "s");
        }
        return seconds + "s";
    }

    private static String compact(int chars) {
        if (chars >= 1000) {
            return String.valueOf((chars / 100) / 10.0d) + "k";
        }
        return String.valueOf(chars);
    }

    private static void appendPart(StringBuilder builder, String value) {
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        if (builder.length() > 0) {
            builder.append(" · ");
        }
        builder.append(value.trim());
    }
}
