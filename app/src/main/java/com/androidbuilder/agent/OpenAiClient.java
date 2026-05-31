package com.androidbuilder.agent;

import android.content.Context;
import android.content.SharedPreferences;

import com.androidbuilder.backend.BuildBackendSettings;
import com.androidbuilder.model.ChatMessage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class OpenAiClient {
    private static final int CONNECT_TIMEOUT_MS = 20000;
    private static final int DEFAULT_READ_TIMEOUT_MS = 120000;
    private static final int TASKS_READ_TIMEOUT_MS = 180000;
    private static final int CODING_READ_TIMEOUT_MS = 300000;
    private static final int SOCKET_ABORT_RETRIES = 1;
    private static final int SOCKET_ABORT_RETRY_DELAY_MS = 1500;

    public static final String PREFS = "cloud_api";
    public static final String KEY_API_KEY = "api_key";
    public static final String KEY_ENDPOINT = "endpoint";
    public static final String KEY_MODEL = "model";
    public static final String KEY_PROVIDER = "provider";
    public static final String PROVIDER_OPENAI = "openai";
    public static final String PROVIDER_DEEPSEEK = "deepseek";
    public static final String PROVIDER_MINIMAX = "minimax";
    public static final String PROVIDER_CUSTOM = "custom";
    public static final String DEEPSEEK_MODEL_FLASH = "deepseek-v4-flash";
    public static final String DEEPSEEK_MODEL_PRO = "deepseek-v4-pro";
    public static final String MINIMAX_MODEL_M2 = "MiniMax-M2";
    public static final String MINIMAX_MODEL_M1 = "MiniMax-M1";
    public static final String MINIMAX_MODEL_TEXT_01 = "MiniMax-Text-01";

    private final SharedPreferences prefs;
    private final Context context;

    public OpenAiClient(Context context) {
        this.context = context.getApplicationContext();
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean isConfigured() {
        return !prefs.getString(KEY_API_KEY, "").trim().isEmpty();
    }

    public String createSpecJson(List<ChatMessage> messages, String latestPrompt, boolean chinese) throws Exception {
        return completeChat(specSystemPrompt(chinese), messages, "Latest approved implementation request: " + latestPrompt, 0.2, chinese, DEFAULT_READ_TIMEOUT_MS);
    }

    public String createProjectFilesJson(List<ChatMessage> messages, String latestPrompt, boolean chinese) throws Exception {
        return completeChat(projectFilesSystemPrompt(chinese), messages, "Latest approved engineering plan to implement: " + latestPrompt, 0.2, chinese, CODING_READ_TIMEOUT_MS);
    }

    public String createEngineeringPlan(List<ChatMessage> messages, String latestPrompt, boolean chinese) throws Exception {
        return completeChat(planSystemPrompt(chinese), messages, "Latest requirement or plan change: " + latestPrompt, 0.3, chinese, DEFAULT_READ_TIMEOUT_MS);
    }

    public String createImplementationTasks(String plan, boolean chinese) throws Exception {
        return completeChat(tasksSystemPrompt(chinese), java.util.Collections.emptyList(), "Approved engineering plan:\n\n" + plan, 0.2, chinese, TASKS_READ_TIMEOUT_MS);
    }

    public String createTaskOperations(String plan, String taskTitle, String taskInstruction, String sourceSnapshot, boolean chinese) throws Exception {
        String prompt = "Approved engineering plan:\n\n" + plan +
                "\n\nCurrent source tree:\n" + sourceSnapshot +
                "\n\nExecute exactly this task:\nTitle: " + taskTitle +
                "\nInstruction: " + taskInstruction;
        return completeChat(taskOperationsSystemPrompt(chinese), java.util.Collections.emptyList(), prompt, 0.2, chinese, CODING_READ_TIMEOUT_MS);
    }

    private String completeChat(String systemPrompt, List<ChatMessage> messages, String latestUserMessage, double temperature, boolean chinese, int readTimeoutMs) throws Exception {
        if (!isConfigured()) {
            throw new IllegalStateException(chinese ? "请先在设置里填写模型 API Key。" : "Configure a model API key in Settings first.");
        }
        String provider = prefs.getString(KEY_PROVIDER, PROVIDER_OPENAI);
        String endpoint = prefs.getString(KEY_ENDPOINT, defaultEndpoint(provider));
        String model = normalizedModel(provider, prefs.getString(KEY_MODEL, defaultModel(provider)));
        if (PROVIDER_DEEPSEEK.equals(provider) && !isSupportedDeepSeekModel(model)) {
            throw new IllegalStateException(chinese ? "DeepSeek 仅支持 deepseek-v4-flash 和 deepseek-v4-pro。" : "DeepSeek supports only deepseek-v4-flash and deepseek-v4-pro.");
        }
        if (PROVIDER_MINIMAX.equals(provider) && !isSupportedMiniMaxModel(model)) {
            throw new IllegalStateException(chinese ? "MiniMax 仅支持 MiniMax-M2、MiniMax-M1 和 MiniMax-Text-01。" : "MiniMax supports only MiniMax-M2, MiniMax-M1, and MiniMax-Text-01.");
        }
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("temperature", temperature);
        JSONArray chat = new JSONArray();
        chat.put(message("system", systemPrompt));
        for (ChatMessage message : messages) {
            if ("user".equals(message.role) || "assistant".equals(message.role)) {
                chat.put(message(message.role, message.content));
            }
        }
        chat.put(message("user", latestUserMessage));
        body.put("messages", chat);

        for (int attempt = 0; attempt <= SOCKET_ABORT_RETRIES; attempt++) {
            try {
                return executeChatRequest(endpoint, body, readTimeoutMs);
            } catch (SocketTimeoutException error) {
                throw new IllegalStateException(chinese ? "模型响应超时。已等待 " + (readTimeoutMs / 1000) + " 秒，请重试或把任务拆得更小。" : "Model response timed out after " + (readTimeoutMs / 1000) + " seconds. Retry or split the task smaller.", error);
            } catch (SocketException error) {
                if (attempt < SOCKET_ABORT_RETRIES) {
                    sleepBeforeRetry();
                    continue;
                }
                throw new IllegalStateException(chinese ? "网络连接被系统中断。通常是息屏、切后台或网络切换导致，请保持网络稳定后重试。" : "Network connection was interrupted by the system. This is often caused by screen-off, backgrounding, or network changes. Retry with a stable connection.", error);
            }
        }
        throw new IllegalStateException(chinese ? "模型请求失败，请重试。" : "Model request failed. Please retry.");
    }

    private String executeChatRequest(String endpoint, JSONObject body, int readTimeoutMs) throws Exception {
            HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(readTimeoutMs);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + prefs.getString(KEY_API_KEY, ""));
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = connection.getResponseCode();
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream(),
                    StandardCharsets.UTF_8));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("Cloud API failed: HTTP " + code + " " + response);
            }
            JSONObject json = new JSONObject(response.toString());
            return json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(SOCKET_ABORT_RETRY_DELAY_MS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    public static String defaultEndpoint(String provider) {
        if (PROVIDER_DEEPSEEK.equals(provider)) {
            return "https://api.deepseek.com/chat/completions";
        }
        if (PROVIDER_MINIMAX.equals(provider)) {
            return "https://api.minimax.io/v1/text/chatcompletion_v2";
        }
        return "https://api.openai.com/v1/chat/completions";
    }

    public static String defaultModel(String provider) {
        if (PROVIDER_DEEPSEEK.equals(provider)) {
            return DEEPSEEK_MODEL_FLASH;
        }
        if (PROVIDER_MINIMAX.equals(provider)) {
            return MINIMAX_MODEL_M2;
        }
        return "gpt-4.1-mini";
    }

    public static String normalizedModel(String provider, String model) {
        String value = model == null ? "" : model.trim();
        if (value.isEmpty()) {
            return defaultModel(provider);
        }
        return value;
    }

    public static boolean isSupportedDeepSeekModel(String model) {
        String value = model == null ? "" : model.trim();
        return DEEPSEEK_MODEL_FLASH.equals(value) || DEEPSEEK_MODEL_PRO.equals(value);
    }

    public static boolean isSupportedMiniMaxModel(String model) {
        String value = model == null ? "" : model.trim();
        return MINIMAX_MODEL_M2.equals(value) || MINIMAX_MODEL_M1.equals(value) || MINIMAX_MODEL_TEXT_01.equals(value);
    }

    public static String deepSeekModelsText() {
        return DEEPSEEK_MODEL_FLASH + " / " + DEEPSEEK_MODEL_PRO;
    }

    private String specSystemPrompt(boolean chinese) {
        String language = chinese ? "Use Simplified Chinese for app names, field labels and user-facing text." : "Use English for app names, field labels and user-facing text.";
        return "You are executing an approved engineering plan for a small native Android app. " +
                "Respect the latest approved plan and project history. The target app must be Android 12+, Java + XML, SQLite, local-first. " +
                "Do not introduce Compose or complex third-party dependencies. Keep source isolated and buildable. " +
                "Return only compact JSON with keys appName, packageName, description, entityName, primaryField, secondaryField. Use ASCII package names. " + language;
    }

    private String projectFilesSystemPrompt(boolean chinese) {
        String language = chinese ? "Use Simplified Chinese for app names, labels, and user-facing text." : "Use English for app names, labels, and user-facing text.";
        return "You are the coding phase for an Android engineering agent. Implement the approved engineering plan by returning a complete buildable Android project source tree. " +
                "Target Android 12+, Java + XML, SQLite/local-first when storage is needed. Do not use Kotlin, Compose, DataBinding, or ViewBinding. Avoid complex third-party dependencies. " +
                "Return only compact JSON and no markdown. The JSON object must contain appName, packageName, description, and files. " +
                "files must be an array of objects with path and content. Paths must be relative POSIX paths. " +
                "The file list must include settings.gradle, build.gradle, app/build.gradle, app/src/main/AndroidManifest.xml, all Java source files, XML layouts, and resources needed to compile. " +
                "Use Gradle plugin com.android.application 8.7.3, compileSdk 34, minSdk 31, targetSdk 34, and Java 8-compatible source/target. Do not use Java records, switch expressions, var, streams-heavy code, org.jetbrains.kotlin.android, kotlinOptions, Kotlin Gradle DSL, or any .kt file. " +
                "Set android.namespace in app/build.gradle and do not set package=\"...\" in AndroidManifest.xml. " +
                dependencyPolicyPrompt() + " " +
                "Preserve the approved plan's screens, data structure, labels, workflows, and acceptance criteria as source code. " + language;
    }

    private String tasksSystemPrompt(boolean chinese) {
        String language = chinese ? "Use Simplified Chinese for task titles." : "Use English for task titles.";
        return "You split an approved Android engineering plan into a short sequential implementation task list. " +
                "Return only compact JSON with a tasks array. Each task must have title and instruction. " +
                "Use 5 to 12 tasks. Keep each task small enough to implement with one or two file writes. " +
                "The first task should create or update the Gradle project skeleton when needed, and later tasks should add data, screens, interactions, and polish. " +
                "Do not include build or install tasks. " + language;
    }

    private String taskOperationsSystemPrompt(boolean chinese) {
        String language = chinese ? "Use Simplified Chinese for user-facing app text when appropriate." : "Use English for user-facing app text.";
        return "You execute one small Android coding task by returning file operations only. " +
                "Return only compact JSON with summary and operations. operations is an array of objects with action, path, and content. " +
                "Supported actions are write and delete. Use write for full-file replacement. Use relative POSIX paths only. Prefer one or two file operations. " +
                "Do not return markdown, comments outside JSON, explanations, build logs, or base64. " +
                "Keep the generated source buildable with Android Gradle Plugin 8.7.3, compileSdk 34, minSdk 31, targetSdk 34, and Java 8-compatible source/target. " +
                "Use Java + XML only. Do not write Kotlin, .kt files, kotlinOptions, Kotlin Gradle plugins, DataBinding, ViewBinding, or Compose. " + dependencyPolicyPrompt() + " " +
                "When writing Java files, keep package names consistent with Gradle namespace. Set android.namespace in app/build.gradle and do not set package=\"...\" in AndroidManifest.xml. " +
                "Declare every view variable with findViewById from the Activity, inflated root view, or dialog view before using it; never use bare view ids such as fabAdd.setOnClickListener or textIncomeAmount.setText. " +
                "Every R.id.* referenced by Kotlin or Java must exist as android:id=\"@+id/...\" in XML, and every layout id used in code must be generated by resources. " + language;
    }

    private String dependencyPolicyPrompt() {
        String mode = BuildBackendSettings.dependencyMode(context);
        if (BuildBackendSettings.DEPENDENCY_ONLINE.equals(mode)) {
            return OnlineDependencyPolicy.prompt();
        }
        if (BuildBackendSettings.DEPENDENCY_LOCAL_CACHE.equals(mode)) {
            return "Dependency mode is local cache: use only dependencies that are already present in the local offline Maven cache. Do not invent new Maven dependencies. Avoid dataBinding, viewBinding, Compose, Room, Retrofit, and Material unless explicitly available in the cache.";
        }
        return "Dependency mode is offline safe: do not add Maven dependencies, AndroidX libraries, Compose, Room, Retrofit, Material, AppCompat, dataBinding, or viewBinding. Use Android SDK APIs, Java, XML layouts, SQLiteOpenHelper, and findViewById.";
    }

    private String planSystemPrompt(boolean chinese) {
        if (chinese) {
            return "你是安卓工程 Agent 的规划阶段。只输出工程计划，不写源码，不返回 JSON。内置约束：先澄清目标并拆解计划，不直接编码；目标 App 必须是 Android 12+、Java + XML、SQLite、本地优先；不使用 Kotlin、Compose、DataBinding、ViewBinding，不引入复杂第三方依赖；每次改动必须保持现有项目上下文、源码隔离、可构建。输出必须以“# 工程计划”开头，并包含这些小节：需求理解、页面/功能、数据结构、源码改动点、工程约束、测试清单、验收标准、构建风险。计划要具体到可以交给编码阶段执行。";
        }
        return "You are in the planning phase for an Android engineering agent. Output an engineering plan only: no source code and no JSON. Built-in constraints: clarify the goal and break down the plan before coding; the target app must be Android 12+, Java + XML, SQLite, local-first; do not use Kotlin, Compose, DataBinding, ViewBinding, or complex third-party dependencies; preserve project context, source isolation, and buildability on every change. The response must start with '# Engineering Plan' and include: Requirement Understanding, Screens/Features, Data Structure, Source Changes, Engineering Constraints, Test Checklist, Acceptance Criteria, Build Risks. Make it concrete enough for the coding phase to execute.";
    }

    private JSONObject message(String role, String content) throws Exception {
        JSONObject message = new JSONObject();
        message.put("role", role);
        message.put("content", content == null ? "" : content);
        return message;
    }
}
