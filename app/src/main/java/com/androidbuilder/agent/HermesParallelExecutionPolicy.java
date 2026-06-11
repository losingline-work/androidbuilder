package com.androidbuilder.agent;

import java.util.Locale;

public final class HermesParallelExecutionPolicy {
    private HermesParallelExecutionPolicy() {
    }

    static boolean shouldDowngradeToSerial(String errorMessage) {
        String text = errorMessage == null ? "" : errorMessage.toLowerCase(Locale.ROOT);
        return text.contains("429")
                || text.contains("rate limit")
                || text.contains("too many requests");
    }

    static String userMessageForBatchFailure(String errorMessage, boolean chinese) {
        String text = errorMessage == null ? "" : errorMessage.trim();
        if (!shouldDowngradeToSerial(text)) {
            return text;
        }
        if (chinese) {
            return "并行子 Agent 可能触发了接口限流。建议把并行子 Agent 调为串行或 2 个后重试。\n" + text;
        }
        return "Parallel sub-agents may have hit the API rate limit. Retry in serial mode or reduce the limit to 2.\n" + text;
    }
}
