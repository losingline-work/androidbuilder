package com.androidbuilder.agent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses the code-review model's response into structured findings, and turns the ACTIONABLE ones into a
 * repair instruction the existing repair path can apply. Pure (no I/O) — fully unit-testable.
 *
 * <p>The review is a quality gate between generation and build: it catches build-INVISIBLE runtime-crash
 * and wiring bugs (the authoritative signal, like javac/aapt for the build, logcat for runtime). To avoid
 * false-positive thrash, only {@code blocker}/{@code high} findings that name a concrete file are acted on;
 * vague or {@code low} findings are dropped. A malformed/absent response yields no findings (never blocks).
 */
final class CodeReviewParser {
    static final class Finding {
        final String severity;
        final String file;
        final int line;
        final String issue;
        final String fix;

        Finding(String severity, String file, int line, String issue, String fix) {
            this.severity = severity == null ? "" : severity.trim().toLowerCase(java.util.Locale.ROOT);
            this.file = file == null ? "" : file.trim();
            this.line = line;
            this.issue = issue == null ? "" : issue.trim();
            this.fix = fix == null ? "" : fix.trim();
        }

        boolean isActionable() {
            return !file.isEmpty() && !issue.isEmpty() && ("blocker".equals(severity) || "high".equals(severity));
        }
    }

    private CodeReviewParser() {
    }

    /** Parses {@code {"findings":[{severity,file,line,issue,fix}]}}; returns an empty list on any problem. */
    static List<Finding> parse(String response) {
        List<Finding> findings = new ArrayList<>();
        if (response == null || response.trim().isEmpty()) {
            return findings;
        }
        try {
            String json = extractJsonObject(response);
            if (json == null) {
                return findings;
            }
            JSONArray array = new JSONObject(json).optJSONArray("findings");
            if (array == null) {
                return findings;
            }
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                findings.add(new Finding(
                        item.optString("severity", ""),
                        item.optString("file", ""),
                        item.optInt("line", 0),
                        item.optString("issue", ""),
                        item.optString("fix", "")));
            }
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
        return findings;
    }

    /** The findings worth a repair round (concrete file + blocker/high severity). */
    static List<Finding> actionable(List<Finding> findings) {
        List<Finding> actionable = new ArrayList<>();
        if (findings != null) {
            for (Finding finding : findings) {
                if (finding.isActionable()) {
                    actionable.add(finding);
                }
            }
        }
        return actionable;
    }

    /** Formats actionable findings into a minimal-change repair instruction; "" when there are none. */
    static String toRepairInstruction(List<Finding> findings, boolean chinese) {
        List<Finding> actionable = actionable(findings);
        if (actionable.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder(chinese
                ? "代码评审发现以下构建查不到的问题，请逐条做最小必要修复（只改涉及文件，不要重写整个项目）：\n"
                : "Code review found these build-invisible issues. Fix each with the smallest change (touch only the named files, do not rewrite the project):\n");
        int index = 1;
        for (Finding finding : actionable) {
            builder.append(index++).append(". [").append(finding.file);
            if (finding.line > 0) {
                builder.append(':').append(finding.line);
            }
            builder.append("] ").append(finding.issue);
            if (!finding.fix.isEmpty()) {
                builder.append(chinese ? " → 修法：" : " -> fix: ").append(finding.fix);
            }
            builder.append('\n');
        }
        return builder.toString();
    }

    private static String extractJsonObject(String raw) {
        String text = raw.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return (start < 0 || end <= start) ? null : text.substring(start, end + 1);
    }
}
