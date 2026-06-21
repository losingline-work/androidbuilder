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

    static String userMessageForBatchFailure(String errorMessage, boolean chinese, int maxParallel) {
        String text = errorMessage == null ? "" : errorMessage.trim();
        if (!shouldDowngradeToSerial(text)) {
            return text;
        }
        // When already running serially there are no parallel sub-agents to blame, and "switch to serial" is
        // useless advice — the limit is the API/model itself. Give the correct guidance for each case.
        if (maxParallel <= 1) {
            if (chinese) {
                return "接口触发了限流（当前已是串行执行）。请稍后重试，或确认所选模型的速率/额度上限。\n" + text;
            }
            return "The API rate-limited the request (already running serially). Wait and retry, or check the selected model's rate/quota limits.\n" + text;
        }
        if (chinese) {
            return "并行子 Agent 可能触发了接口限流。建议把并行子 Agent 调为串行或 2 个后重试。\n" + text;
        }
        return "Parallel sub-agents may have hit the API rate limit. Retry in serial mode or reduce the limit to 2.\n" + text;
    }
}
