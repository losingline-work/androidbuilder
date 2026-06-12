package com.androidbuilder.ui;

import com.androidbuilder.agent.BuildLogContextExtractor;
import com.androidbuilder.model.AiConversationRecord;
import com.androidbuilder.model.BuildJobRecord;
import com.androidbuilder.model.ChatMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class ProjectBuildFailureContextPolicy {
    private static final int INLINE_LIMIT = 9000;
    private static final int CONTEXT_RADIUS = 2500;
    private static final int COPY_LIMIT = 18000;
    private static final int FULL_COPY_LIMIT = 80000;

    private ProjectBuildFailureContextPolicy() {
    }

    public static boolean canCopyFailureContext(BuildJobRecord job) {
        if (job == null) {
            return false;
        }
        String status = job.status == null ? "" : job.status.trim().toLowerCase(Locale.ROOT);
        return "failed".equals(status) && job.logsPath != null && !job.logsPath.trim().isEmpty();
    }

    public static boolean canCopyFailureContext(BuildJobRecord job, List<ChatMessage> messages, List<AiConversationRecord> aiRecords) {
        if (job == null) {
            return false;
        }
        String status = job.status == null ? "" : job.status.trim().toLowerCase(Locale.ROOT);
        return "failed".equals(status);
    }

    public static String copyText(BuildJobRecord job, String logs) {
        return copyText(job, logs, false);
    }

    public static String copyText(BuildJobRecord job, String logs, boolean chinese) {
        StringBuilder result = new StringBuilder();
        if (job != null) {
            result.append(chinese ? "任务 #" : "Job #").append(job.id).append('\n');
            appendLine(result, chinese ? "状态" : "Status", job.status);
            appendLine(result, chinese ? "阶段" : "Phase", job.phase);
            appendLine(result, chinese ? "错误摘要" : "Error summary", job.errorSummary);
            result.append('\n');
        }
        String context = previewText(logs, chinese);
        if (context.isEmpty()) {
            context = chinese ? "没有捕获到构建日志。" : "No build log captured.";
        }
        result.append(context);
        return bound(result.toString().trim(), COPY_LIMIT);
    }

    public static String copyText(
            BuildJobRecord job,
            String logs,
            List<ChatMessage> messages,
            List<AiConversationRecord> aiRecords,
            boolean chinese) {
        StringBuilder result = new StringBuilder();
        result.append(chinese ? "失败上下文" : "Failure Context").append('\n');
        if (job != null) {
            result.append(chinese ? "任务 #" : "Job #").append(job.id).append('\n');
            appendLine(result, chinese ? "状态" : "Status", job.status);
            appendLine(result, chinese ? "阶段" : "Phase", job.phase);
            appendLine(result, chinese ? "错误摘要" : "Error summary", job.errorSummary);
        }
        List<TimelineEvent> events = timelineEvents(job, messages, aiRecords, chinese);
        if (!events.isEmpty()) {
            appendSection(result, chinese ? "本次执行记录" : "Execution Records");
            for (TimelineEvent event : events) {
                appendBlock(result, event.title, event.body);
            }
        }
        appendSection(result, chinese ? "日志摘要" : "Log Summary");
        String context = previewText(logs, chinese);
        result.append(context.isEmpty()
                ? (chinese ? "没有捕获到构建日志。" : "No build log captured.")
                : context);
        return bound(result.toString().trim(), FULL_COPY_LIMIT);
    }

    public static String previewText(String logs) {
        return previewText(logs, false);
    }

    public static String previewText(String logs, boolean chinese) {
        if (logs == null || logs.trim().isEmpty()) {
            return "";
        }
        if (logs.length() <= INLINE_LIMIT) {
            return logs;
        }
        StringBuilder result = new StringBuilder();
        appendSnippet(result, chinese ? "开头日志" : "First log", logs, 0, Math.min(1600, logs.length()));
        String missingFieldHints = BuildLogContextExtractor.missingFieldHints(logs);
        if (!missingFieldHints.isEmpty()) {
            appendSnippet(result, chinese ? "Java API 一致性提示" : "Java API consistency hints", missingFieldHints, 0, missingFieldHints.length());
        }
        String javaDiagnostics = BuildLogContextExtractor.javaCompileDiagnostics(logs, 9000);
        if (!javaDiagnostics.isEmpty()) {
            appendSnippet(result, chinese ? "Java 编译诊断" : "Java compile diagnostics", javaDiagnostics, 0, javaDiagnostics.length());
        }
        int[] anchors = failureAnchors(logs);
        for (int anchor : anchors) {
            if (anchor >= 0) {
                appendSnippet(result, chinese ? "失败上下文" : "Failure context", logs,
                        Math.max(0, anchor - CONTEXT_RADIUS),
                        Math.min(logs.length(), anchor + CONTEXT_RADIUS));
            }
        }
        appendSnippet(result, chinese ? "末尾日志" : "Last log", logs, Math.max(0, logs.length() - 3500), logs.length());
        return bound(result.toString().trim(), 14000);
    }

    private static void appendLine(StringBuilder result, String label, String value) {
        String text = value == null ? "" : value.trim();
        if (!text.isEmpty()) {
            result.append(label).append(": ").append(text).append('\n');
        }
    }

    private static List<TimelineEvent> timelineEvents(BuildJobRecord job, List<ChatMessage> messages, List<AiConversationRecord> aiRecords, boolean chinese) {
        long jobId = job == null ? 0 : job.id;
        List<TimelineEvent> events = new ArrayList<>();
        if (jobId <= 0) {
            return events;
        }
        if (messages != null) {
            for (ChatMessage message : messages) {
                if (message == null || message.linkedBuildJobId == null || message.linkedBuildJobId != jobId) {
                    continue;
                }
                String role = message.role == null ? "" : message.role;
                String title = (chinese ? "消息" : "Message") + " #" + message.id
                        + (role.trim().isEmpty() ? "" : " · " + role);
                events.add(new TimelineEvent(message.createdAt, title, message.content));
            }
        }
        if (aiRecords != null) {
            for (AiConversationRecord record : aiRecords) {
                if (record == null || record.linkedBuildJobId == null || record.linkedBuildJobId != jobId) {
                    continue;
                }
                String title = (record.title == null || record.title.trim().isEmpty()
                        ? (chinese ? "AI 调用" : "AI Call")
                        : record.title.trim()) + " #" + record.id;
                StringBuilder body = new StringBuilder();
                appendLine(body, chinese ? "来源" : "Source", record.source);
                appendLine(body, chinese ? "状态" : "Status", record.status);
                appendBlock(body, "Metadata", record.metadata);
                appendBlock(body, "Request", record.requestText);
                appendBlock(body, "Response", record.responseText);
                events.add(new TimelineEvent(record.createdAt, title, body.toString().trim()));
            }
        }
        Collections.sort(events, new Comparator<TimelineEvent>() {
            @Override
            public int compare(TimelineEvent left, TimelineEvent right) {
                int time = Long.compare(left.time, right.time);
                return time != 0 ? time : left.title.compareTo(right.title);
            }
        });
        return events;
    }

    private static void appendSection(StringBuilder result, String title) {
        if (result.length() > 0) {
            result.append("\n\n");
        }
        result.append("==== ").append(title).append(" ====\n");
    }

    private static void appendBlock(StringBuilder result, String label, String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) {
            return;
        }
        if (result.length() > 0 && result.charAt(result.length() - 1) != '\n') {
            result.append('\n');
        }
        result.append(label).append(":\n").append(text).append('\n');
    }

    private static int[] failureAnchors(String logs) {
        return new int[]{
                indexOfAny(logs, ".java:", "error: cannot find symbol", "has private access", "cannot be applied to given types", "actual and formal argument lists differ"),
                indexOfAny(logs, "Android resource linking failed", "error: resource", "error: failed linking", "AAPT: error"),
                indexOfAny(logs, "Namespace not specified", "Manifest merger failed", "package=\"", "> Task :app:processDebugResources FAILED", "Execution failed for task ':app:processDebugResources'", "* What went wrong:"),
                indexOfAny(logs, "BUILD FAILED", "Caused by:")
        };
    }

    private static int indexOfAny(String text, String... needles) {
        int best = -1;
        for (String needle : needles) {
            int index = text.indexOf(needle);
            if (index >= 0 && (best < 0 || index < best)) {
                best = index;
            }
        }
        return best;
    }

    private static void appendSnippet(StringBuilder result, String label, String text, int start, int end) {
        if (start >= end) {
            return;
        }
        String snippet = text.substring(start, end).trim();
        if (snippet.isEmpty() || result.indexOf(snippet) >= 0) {
            return;
        }
        if (result.length() > 0) {
            result.append("\n\n...\n\n");
        }
        result.append(label).append(":\n").append(snippet);
    }

    private static String bound(String text, int limit) {
        if (text.length() <= limit) {
            return text;
        }
        String suffix = "\n\n...[truncated]";
        return text.substring(0, Math.max(0, limit - suffix.length())).trim() + suffix;
    }

    private static final class TimelineEvent {
        final long time;
        final String title;
        final String body;

        TimelineEvent(long time, String title, String body) {
            this.time = time;
            this.title = title == null ? "" : title;
            this.body = body == null ? "" : body;
        }
    }
}
