package com.androidbuilder.ui;

import com.androidbuilder.model.AiConversationRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Renders a task's execution STEPS (its cloud generations, deterministic preflights, reviews and the
 * merge outcome) into a compact, human-readable flow log for the task card - so a finished or failed
 * task shows HOW it went, not just its final result. Each step becomes one line: a status glyph + a
 * short label + the decision. The list is capped so a task that retried many times stays readable.
 */
final class TaskExecutionLogPolicy {
    private static final int MAX_LINES = 40;

    private TaskExecutionLogPolicy() {
    }

    static String render(List<AiConversationRecord> steps, boolean chinese) {
        if (steps == null || steps.isEmpty()) {
            return "";
        }
        // Collapse runs of near-identical steps (e.g. "文件操作生成批次 1/21 … 21/21") into one counted
        // line, keying on the title with digits normalised out, so the log reads as a flow instead of
        // dozens of look-alike rows.
        List<String> lines = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        String previousKey = null;
        for (AiConversationRecord step : steps) {
            if (step == null) {
                continue;
            }
            String key = (label(step) + "|" + nz(step.status)).replaceAll("\\d+", "#");
            String line = glyph(step.status) + " " + label(step) + decision(step.status, chinese);
            if (key.equals(previousKey) && !lines.isEmpty()) {
                counts.set(counts.size() - 1, counts.get(counts.size() - 1) + 1);
                lines.set(lines.size() - 1, line); // keep the latest concrete line (e.g. last batch number)
            } else {
                lines.add(line);
                counts.add(1);
                previousKey = key;
            }
        }

        int start = Math.max(0, lines.size() - MAX_LINES);
        StringBuilder out = new StringBuilder();
        if (start > 0) {
            out.append(chinese ? "… 省略前 " + start + " 步" : "… " + start + " earlier steps omitted");
        }
        for (int i = start; i < lines.size(); i++) {
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(lines.get(i));
            if (counts.get(i) > 1) {
                out.append(" ×").append(counts.get(i));
            }
        }
        return out.toString();
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    private static String glyph(String status) {
        String s = status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
        switch (s) {
            case "ok":
            case "success":
                return "✅";
            case "rewrite":
                return "🔁";
            case "failed":
            case "error":
                return "❌";
            case "dispatch":
                return "📤";
            default:
                return "•";
        }
    }

    private static String decision(String status, boolean chinese) {
        String s = status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
        switch (s) {
            case "rewrite":
                return chinese ? " · 要求重写" : " · rewrite";
            case "failed":
            case "error":
                return chinese ? " · 失败" : " · failed";
            case "ok":
                return chinese ? " · 通过" : " · ok";
            case "success":
                return chinese ? " · 成功" : " · ok";
            case "dispatch":
                return chinese ? " · 派发" : " · dispatched";
            default:
                return s.isEmpty() ? "" : " · " + s;
        }
    }

    /** A short label for the step: prefer the title, dropping a redundant trailing "#n" only when noisy. */
    private static String label(AiConversationRecord step) {
        String title = step.title == null ? "" : step.title.trim();
        if (title.isEmpty()) {
            return step.source == null ? "step" : step.source;
        }
        return title;
    }
}
