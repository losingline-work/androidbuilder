package com.androidbuilder.agent;

import com.androidbuilder.model.FileOperation;
import com.androidbuilder.model.TaskOperations;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class LocalGuardPromptBuilder {
    private static final int MAX_PROMPT_CHARS = 6500;
    private static final int PLAN_LIMIT = 900;
    private static final int TASK_LIMIT = 1200;
    private static final int SNAPSHOT_DIGEST_LIMIT = 2600;
    private static final int OPERATION_DIGEST_LIMIT = 2600;
    private static final int POLICY_ERROR_LIMIT = 1200;
    private static final int BUILD_LOG_TAIL_LIMIT = 3200;
    private static final Pattern R_REFERENCE = Pattern.compile("\\bR\\.(id|layout|string|color|drawable|mipmap|style)\\.([A-Za-z_][A-Za-z0-9_]*)\\b");
    private static final Pattern DBHELPER_FIELD = Pattern.compile("\\bDBHelper\\.([A-Z][A-Z0-9_]+)\\b");
    private static final Pattern METHOD_CALL = Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_]*)\\.([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");
    private static final Pattern DAO_DECLARATION = Pattern.compile("\\b([A-Z][A-Za-z0-9_]*Dao)\\s+([A-Za-z_][A-Za-z0-9_]*)\\b");
    private static final Pattern METHOD_DECLARATION = Pattern.compile("\\b(?:public|protected|private)\\s+(?:static\\s+)?(?:final\\s+)?[A-Za-z_][A-Za-z0-9_$.<>?\\[\\]]*\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");

    private LocalGuardPromptBuilder() {
    }

    static String reviewOperationsPrompt(String plan, String taskTitle, String taskInstruction, String sourceSnapshot, TaskOperations operations) {
        String prompt = contract()
                + "\n\nRole: You are a local Android source guard assistant. You are advisory only."
                + "\nGoal: Review proposed file operations before they are written. Find likely API/field/resource mismatches, especially database/helper/DAO/model/adapter consistency issues."
                + preflightChecklist()
                + "\nDo not approve unsafe shortcuts. Do not modify files. Do not skip deterministic validation."
                + "\n\nTask title:\n" + truncate(taskTitle, TASK_LIMIT)
                + "\n\nTask instruction:\n" + truncate(taskInstruction, TASK_LIMIT)
                + "\n\nOperation digest:\n" + operationDigest(operations)
                + "\n\nSource API digest:\n" + sourceApiDigest(sourceSnapshot)
                + "\n\nApproved plan summary:\n" + truncate(plan, PLAN_LIMIT);
        return fit(prompt);
    }

    static String policyFailurePrompt(String taskInstruction, String policyError, String focusedSnapshot, int attempt) {
        String prompt = contract()
                + "\n\nRole: You are a local Android source guard assistant. A deterministic source guard rejected the generated operations."
                + "\nGoal: Explain the smallest precise hint the cloud model needs for retry attempt " + attempt + "."
                + "\nFocus on existing declarations, missing fields/methods/resources, and caller/API consistency. Do not ask to bypass validation."
                + policyFailureChecklist()
                + "\n\nOriginal task instruction:\n" + truncate(taskInstruction, TASK_LIMIT)
                + "\n\nDeterministic policy error:\n" + truncate(policyError, POLICY_ERROR_LIMIT)
                + "\n\nSource API digest:\n" + sourceApiDigest(focusedSnapshot);
        return fit(prompt);
    }

    static String triageBuildFailurePrompt(String buildLog, String focusedSnapshot) {
        String prompt = contract()
                + "\n\nRole: You are a local Android build-failure triage assistant. A Gradle build just failed."
                + "\nGoal: From the build log, find the real root cause and produce the smallest precise repair instruction for the cloud model."
                + triageChecklist()
                + "\n\nBuild log tail:\n" + tail(buildLog, BUILD_LOG_TAIL_LIMIT)
                + "\n\nSource API digest:\n" + sourceApiDigest(focusedSnapshot);
        return fit(prompt);
    }

    private static String triageChecklist() {
        return "\n\nTriage checklist:"
                + "\n- Identify the exact failing file(s) and the single root error: javac compile error, missing symbol, resource linking (AAPT2), manifest, or dependency resolution."
                + "\n- Quote only the key error line(s); ignore Gradle banners, download progress, and stack frames that are not the cause."
                + "\n- additionalInstruction must state the smallest change to fix it: which file, which symbol/resource, and what to add or change. Name real files from the digest."
                + "\n- Prefer Android SDK / Java / XML fixes; do not propose new dependencies, Kotlin, Compose, DataBinding, ViewBinding, or annotation processors."
                + "\n- If the log shows no actionable source cause (only network, runtime, or toolchain/environment errors), return decision=ok with an empty additionalInstruction so the deterministic instruction is used.";
    }

    private static String contract() {
        return "Return ONLY one JSON object with exactly these fields:"
                + "\n{\"decision\":\"ok|rewrite\",\"summary\":\"short reason\",\"additionalInstruction\":\"specific retry hint or empty string\"}"
                + "\nUse decision=ok when the operations look consistent."
                + "\nUse decision=rewrite only when the next cloud request should be retried before writing, or when the policy failure needs a more precise retry hint.";
    }

    private static String preflightChecklist() {
        return "\n\nHigh-priority preflight checklist:"
                + "\n1. If any proposed Java file content contains the token ->, return decision=rewrite. Java lambda syntax is forbidden in every Java file, including utility files such as DateUtils.java. Tell the cloud model to rewrite all listeners/callbacks using anonymous inner classes and to remove every -> token."
                + "\n2. For every DAO/helper/model/adapter method call in proposed files, verify the exact method exists in the current snapshot or is added in the same operations. If JsonBackup.java calls RecordDao.listAll(), RecordDao must declare listAll() with matching parameters, or JsonBackup.java must call an existing DAO method instead."
                + "\n3. For backup/import/export code, do not invent DAO methods such as RecordDao.listAll(), getAll(), update(Record), delete(long), or queryByType(int) unless the DAO is updated in the same response. Prefer using an existing DAO method when one is already present."
                + "\n4. If a caller references a new DBHelper.COL_* constant, R.drawable.*, XML resource id, model field, adapter listener, or DAO method that is not declared or not added in the same operations, return decision=rewrite with the exact missing symbol and caller file."
                + "\n5. Do not approve Gson or other undeclared third-party imports in offline-safe generated apps; prefer org.json and Android SDK APIs."
                + "\n6. When decision=rewrite, additionalInstruction must be a direct retry hint naming the exact file and symbol, for example: Add RecordDao.listAll() or update JsonBackup.java to use an existing DAO method; add app/src/main/res/drawable/ic_food.xml or stop referencing R.drawable.ic_food; remove -> from DateUtils.java and use anonymous inner classes.";
    }

    private static String policyFailureChecklist() {
        return "\n\nPolicy-error rewrite checklist:"
                + "\n- Preserve the exact blocked symbol and file from the policy error in additionalInstruction."
                + "\n- For missing methods such as RecordDao.listAll() in JsonBackup.java, tell the cloud model to either add that exact DAO method with matching parameters/return type or update JsonBackup.java to use an existing DAO method. Do not leave the caller unchanged."
                + "\n- For Java lambda syntax errors such as DateUtils.java containing ->, tell the cloud model: Do not use -> anywhere; rewrite all Java lambdas as anonymous inner classes."
                + "\n- For missing drawable resources such as R.drawable.ic_food in IconRes.java, tell the cloud model to add app/src/main/res/drawable/ic_food.xml as a valid vector/shape drawable in the same response or change the caller to an existing drawable."
                + "\n- Do not suggest bypassing AndroidSourceGuard; the deterministic guard remains final.";
    }

    private static String operationDigest(TaskOperations operations) {
        try {
            StringBuilder digest = new StringBuilder();
            digest.append("summary=").append(operations == null ? "" : truncate(operations.summary, 300)).append('\n');
            if (operations == null || operations.operations == null) {
                return digest.toString();
            }
            int index = 0;
            for (FileOperation operation : operations.operations) {
                if (operation == null || digest.length() > OPERATION_DIGEST_LIMIT) {
                    continue;
                }
                index++;
                String content = operation.content == null ? "" : operation.content;
                digest.append(index).append(". ")
                        .append(operation.action).append(' ')
                        .append(operation.path).append(" chars=")
                        .append(content.length()).append('\n');
                appendOperationSignals(digest, content);
            }
            return truncate(digest.toString(), OPERATION_DIGEST_LIMIT);
        } catch (Exception error) {
            return "";
        }
    }

    private static void appendOperationSignals(StringBuilder digest, String content) {
        if (content.contains("->")) {
            digest.append("   signal: contains -> token\n");
        }
        appendPatternSignals(digest, "R", R_REFERENCE, content);
        appendPatternSignals(digest, "DBHelper", DBHELPER_FIELD, content);
        appendDaoCallSignals(digest, content);
        appendDeclaredMethodSignals(digest, content);
    }

    private static void appendPatternSignals(StringBuilder digest, String label, Pattern pattern, String content) {
        Matcher matcher = pattern.matcher(content);
        Set<String> seen = new HashSet<>();
        while (matcher.find() && seen.size() < 12) {
            String value;
            if ("R".equals(label)) {
                value = "R." + matcher.group(1) + "." + matcher.group(2);
            } else {
                value = "DBHelper." + matcher.group(1);
            }
            if (seen.add(value)) {
                digest.append("   signal: ").append(value).append('\n');
            }
        }
    }

    private static void appendDaoCallSignals(StringBuilder digest, String content) {
        Map<String, String> daoVars = new HashMap<>();
        Matcher declaration = DAO_DECLARATION.matcher(content);
        while (declaration.find()) {
            daoVars.put(declaration.group(2), declaration.group(1));
        }
        Matcher call = METHOD_CALL.matcher(content);
        Set<String> seen = new HashSet<>();
        while (call.find() && seen.size() < 16) {
            String receiver = call.group(1);
            String method = call.group(2);
            String type = daoVars.get(receiver);
            String value = type == null ? receiver + "." + method + "()" : type + "." + method + "()";
            if (seen.add(value)) {
                digest.append("   call: ").append(value).append('\n');
            }
        }
    }

    private static void appendDeclaredMethodSignals(StringBuilder digest, String content) {
        Matcher matcher = METHOD_DECLARATION.matcher(content);
        Set<String> seen = new HashSet<>();
        while (matcher.find() && seen.size() < 16) {
            String value = matcher.group(1) + "(...)";
            if (seen.add(value)) {
                digest.append("   declares: ").append(value).append('\n');
            }
        }
    }

    private static String sourceApiDigest(String sourceSnapshot) {
        String text = sourceSnapshot == null ? "" : sourceSnapshot;
        if (text.length() == 0) {
            return "(empty)";
        }
        StringBuilder digest = new StringBuilder();
        String currentPath = "";
        String[] lines = text.split("\\n");
        boolean wroteHeader = false;
        for (String line : lines) {
            if (digest.length() > SNAPSHOT_DIGEST_LIMIT) {
                break;
            }
            if (line.startsWith("--- ") && line.endsWith(" ---")) {
                currentPath = line.substring(4, line.length() - 4).trim();
                wroteHeader = false;
                continue;
            }
            if (!isApiLine(line)) {
                continue;
            }
            if (!wroteHeader) {
                digest.append("--- ").append(currentPath).append(" ---\n");
                wroteHeader = true;
            }
            digest.append(line.trim()).append('\n');
        }
        if (digest.length() == 0) {
            return truncate(text, SNAPSHOT_DIGEST_LIMIT);
        }
        return truncate(digest.toString(), SNAPSHOT_DIGEST_LIMIT);
    }

    private static boolean isApiLine(String line) {
        String trimmed = line == null ? "" : line.trim();
        if (trimmed.length() == 0) {
            return false;
        }
        if (trimmed.contains(" class ") || trimmed.startsWith("class ")
                || trimmed.contains(" interface ") || trimmed.startsWith("interface ")) {
            return true;
        }
        if (trimmed.contains("static final") || trimmed.contains(" TABLE_") || trimmed.contains(" COL_")) {
            return true;
        }
        if (trimmed.startsWith("public ") || trimmed.startsWith("protected ") || trimmed.startsWith("private ")) {
            return trimmed.contains("(") || trimmed.contains("=");
        }
        return trimmed.contains("R.drawable.") || trimmed.contains("android:id=");
    }

    private static String truncate(String value, int limit) {
        String text = value == null ? "" : value;
        if (text.length() <= limit) {
            return text;
        }
        return text.substring(0, limit) + "\n...[truncated]";
    }

    private static String tail(String value, int limit) {
        String text = value == null ? "" : value;
        if (text.length() <= limit) {
            return text;
        }
        return "...[truncated]\n" + text.substring(text.length() - limit);
    }

    private static String fit(String value) {
        String text = value == null ? "" : value;
        if (text.length() <= MAX_PROMPT_CHARS) {
            return text;
        }
        String suffix = "\n...[truncated for local context]";
        return text.substring(0, MAX_PROMPT_CHARS - suffix.length()) + suffix;
    }
}
