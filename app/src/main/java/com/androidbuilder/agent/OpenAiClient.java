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
    private static final int MODEL_READ_TIMEOUT_MS = 10 * 60 * 1000;
    private static final int DEFAULT_READ_TIMEOUT_MS = MODEL_READ_TIMEOUT_MS;
    private static final int TASKS_READ_TIMEOUT_MS = MODEL_READ_TIMEOUT_MS;
    private static final int CODING_READ_TIMEOUT_MS = MODEL_READ_TIMEOUT_MS;
    private static final int SOCKET_ABORT_RETRIES = 2;
    private static final int SOCKET_ABORT_RETRY_DELAY_MS = 1500;
    private static final int PROGRESS_EMIT_CHARS = 120;

    /** Reports streaming progress while a response is being received. Called on a worker thread. */
    public interface ProgressListener {
        void onProgress(int answerChars, int reasoningChars);
    }

    public static final String PREFS = "cloud_api";
    public static final String KEY_API_KEY = "api_key";
    public static final String KEY_ENDPOINT = "endpoint";
    public static final String KEY_MODEL = "model";
    public static final String KEY_PROVIDER = "provider";
    public static final String KEY_THINKING = "thinking_enabled";
    public static final String PROVIDER_OPENAI = "openai";
    public static final String PROVIDER_DEEPSEEK = "deepseek";
    public static final String PROVIDER_MINIMAX = "minimax";
    public static final String PROVIDER_CUSTOM = "custom";
    public static final String OPENAI_MODEL_GPT_55 = "gpt-5.5";
    public static final String OPENAI_MODEL_GPT_54 = "gpt-5.4";
    public static final String OPENAI_MODEL_GPT_54_MINI = "gpt-5.4-mini";
    public static final String OPENAI_MODEL_GPT_54_NANO = "gpt-5.4-nano";
    public static final String OPENAI_MODEL_GPT_51 = "gpt-5.1";
    public static final String OPENAI_MODEL_GPT_5 = "gpt-5";
    public static final String OPENAI_MODEL_GPT_5_MINI = "gpt-5-mini";
    public static final String OPENAI_MODEL_GPT_5_NANO = "gpt-5-nano";
    public static final String DEEPSEEK_MODEL_FLASH = "deepseek-v4-flash";
    public static final String DEEPSEEK_MODEL_PRO = "deepseek-v4-pro";
    public static final String DEEPSEEK_OFFICIAL_BASE_URL = "https://api.deepseek.com";
    public static final String DEEPSEEK_OPENAI_COMPATIBLE_BASE_URL = "https://api.deepseek.com/v1";
    public static final String MINIMAX_MODEL_M3 = "MiniMax-M3";
    public static final String MINIMAX_MODEL_M27 = "MiniMax-M2.7";
    public static final String MINIMAX_MODEL_M27_HIGHSPEED = "MiniMax-M2.7-highspeed";
    public static final String MINIMAX_MODEL_M25 = "MiniMax-M2.5";
    public static final String MINIMAX_MODEL_M25_HIGHSPEED = "MiniMax-M2.5-highspeed";
    public static final String MINIMAX_MODEL_M21 = "MiniMax-M2.1";
    public static final String MINIMAX_MODEL_M21_HIGHSPEED = "MiniMax-M2.1-highspeed";
    public static final String MINIMAX_MODEL_M2 = "MiniMax-M2";
    public static final String MINIMAX_CHINA_BASE_URL = "https://api.minimaxi.com/v1";
    public static final String MINIMAX_INTERNATIONAL_BASE_URL = "https://api.minimax.io/v1";
    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";
    private static final String DEEPSEEK_OFFICIAL_CHAT_COMPLETIONS_ENDPOINT = DEEPSEEK_OFFICIAL_BASE_URL + CHAT_COMPLETIONS_PATH;
    private static final String DEEPSEEK_OPENAI_COMPATIBLE_CHAT_COMPLETIONS_ENDPOINT = DEEPSEEK_OPENAI_COMPATIBLE_BASE_URL + CHAT_COMPLETIONS_PATH;
    private static final String MINIMAX_OPENAI_COMPATIBLE_ENDPOINT = MINIMAX_CHINA_BASE_URL + CHAT_COMPLETIONS_PATH;
    private static final String MINIMAX_INTERNATIONAL_OPENAI_COMPATIBLE_ENDPOINT = MINIMAX_INTERNATIONAL_BASE_URL + CHAT_COMPLETIONS_PATH;
    private static final String MINIMAX_LEGACY_CHAT_COMPLETION_ENDPOINT = "https://api.minimax.io/v1/text/chatcompletion_v2";

    private final SharedPreferences prefs;
    private final Context context;
    private volatile ProgressListener progressListener;

    public OpenAiClient(Context context) {
        this.context = context.getApplicationContext();
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void setProgressListener(ProgressListener listener) {
        this.progressListener = listener;
    }

    public boolean isConfigured() {
        String provider = prefs.getString(KEY_PROVIDER, PROVIDER_OPENAI);
        return !apiKeyForProvider(prefs, provider).trim().isEmpty();
    }

    public String currentProvider() {
        return prefs.getString(KEY_PROVIDER, PROVIDER_OPENAI);
    }

    public String currentModel() {
        return modelForProvider(prefs, currentProvider());
    }

    public String currentEndpoint() {
        return endpointForProvider(prefs, currentProvider());
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

    public String createTaskOperations(String plan, String taskTitle, String taskInstruction, String sourceSnapshot, String recentRequirements, boolean chinese) throws Exception {
        String requirementsSection = recentRequirements == null || recentRequirements.trim().isEmpty()
                ? ""
                : "\n\nRecent user requirements and clarifications (honor these even if the plan omits them):\n" + recentRequirements.trim();
        String prompt = "Approved engineering plan:\n\n" + plan +
                requirementsSection +
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
        String apiKey = apiKeyForProvider(prefs, provider);
        String endpoint = endpointForProvider(prefs, provider);
        String model = modelForProvider(prefs, provider);
        if (endpoint.trim().isEmpty()) {
            throw new IllegalStateException(chinese ? "请先在设置里填写模型接口地址。" : "Configure a model API endpoint in Settings first.");
        }
        if (PROVIDER_DEEPSEEK.equals(provider) && !isSupportedDeepSeekModel(model)) {
            throw new IllegalStateException(chinese ? "DeepSeek 仅支持 deepseek-v4-flash 和 deepseek-v4-pro。" : "DeepSeek supports only deepseek-v4-flash and deepseek-v4-pro.");
        }
        if (PROVIDER_MINIMAX.equals(provider) && !isSupportedMiniMaxModel(model)) {
            throw new IllegalStateException(chinese ? "MiniMax 仅支持 MiniMax-M3、MiniMax-M2.7、MiniMax-M2.5、MiniMax-M2.1 和 MiniMax-M2 系列模型。" : "MiniMax supports MiniMax-M3, MiniMax-M2.7, MiniMax-M2.5, MiniMax-M2.1, and MiniMax-M2 series models.");
        }
        boolean thinkingEnabled = thinkingEnabledForProvider(prefs, provider);
        JSONObject body = chatRequestBody(provider, model, systemPrompt, messages, latestUserMessage, temperature, thinkingEnabled);

        for (int attempt = 0; attempt <= SOCKET_ABORT_RETRIES; attempt++) {
            try {
                return executeChatRequest(endpoint, apiKey, body, readTimeoutMs, provider, chinese);
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

    private static JSONObject chatRequestBody(String provider, String model, String systemPrompt, List<ChatMessage> messages, String latestUserMessage, double temperature, boolean thinkingEnabled) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("stream", true);
        if (!usesDefaultSamplingParameters(provider, model)) {
            body.put("temperature", temperature);
        }
        if (PROVIDER_MINIMAX.equals(provider)) {
            // reasoning_split keeps reasoning out of the answer content so the code JSON stays parseable.
            body.put("reasoning_split", true);
        }
        // Thinking is on by default for MiniMax/DeepSeek; only send the disable flag when switched off,
        // so the on-state request stays byte-identical to the previous behavior.
        if (!thinkingEnabled && supportsThinkingToggle(provider)) {
            body.put("thinking", new JSONObject().put("type", "disabled"));
        }
        JSONArray chat = new JSONArray();
        chat.put(message("system", systemPrompt));
        for (ChatMessage message : messages) {
            if ("user".equals(message.role) || "assistant".equals(message.role)) {
                chat.put(message(message.role, message.content));
            }
        }
        chat.put(message("user", latestUserMessage));
        body.put("messages", chat);
        return body;
    }

    static JSONObject chatRequestBodyForTest(String provider, String model, String systemPrompt, List<ChatMessage> messages, String latestUserMessage, double temperature, boolean thinkingEnabled) throws Exception {
        return chatRequestBody(provider, model, systemPrompt, messages, latestUserMessage, temperature, thinkingEnabled);
    }

    private String executeChatRequest(String endpoint, String apiKey, JSONObject body, int readTimeoutMs, String provider, boolean chinese) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(readTimeoutMs);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        connection.setRequestProperty("Accept", "text/event-stream");
        try (OutputStream out = connection.getOutputStream()) {
            out.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        int code = connection.getResponseCode();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream(),
                StandardCharsets.UTF_8))) {
            if (code < 200 || code >= 300) {
                StringBuilder error = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    error.append(line);
                }
                throw new IllegalStateException(httpErrorMessage(provider, code, error.toString(), chinese));
            }
            return readChatContent(reader, progressListener);
        }
    }

    /**
     * Reads an OpenAI-compatible streaming (SSE) chat response, accumulating the answer content
     * and reporting progress. Falls back to parsing a single non-streamed JSON body if the server
     * ignored {@code stream:true}.
     */
    static String readChatContent(BufferedReader reader, ProgressListener listener) throws Exception {
        StringBuilder answer = new StringBuilder();
        StringBuilder raw = new StringBuilder();
        boolean sawStreamEvent = false;
        int reasoningChars = 0;
        int lastEmitted = -1;
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                continue;
            }
            String trimmed = line.trim();
            if (trimmed.startsWith("data:")) {
                sawStreamEvent = true;
                String payload = trimmed.substring(5).trim();
                if (payload.isEmpty()) {
                    continue;
                }
                if ("[DONE]".equals(payload)) {
                    break;
                }
                try {
                    JSONObject chunk = new JSONObject(payload);
                    JSONArray choices = chunk.optJSONArray("choices");
                    if (choices != null && choices.length() > 0) {
                        JSONObject choice = choices.getJSONObject(0);
                        JSONObject delta = choice.optJSONObject("delta");
                        if (delta == null) {
                            delta = choice.optJSONObject("message");
                        }
                        if (delta != null) {
                            answer.append(delta.optString("content", ""));
                            reasoningChars += delta.optString("reasoning_content", "").length();
                        }
                    }
                } catch (Exception ignored) {
                    // Tolerate keep-alive comments and non-JSON data frames.
                }
                if (listener != null) {
                    int total = answer.length() + reasoningChars;
                    if (total - lastEmitted >= PROGRESS_EMIT_CHARS) {
                        lastEmitted = total;
                        listener.onProgress(answer.length(), reasoningChars);
                    }
                }
            } else {
                raw.append(line);
            }
        }
        if (!sawStreamEvent) {
            // Server ignored stream:true and returned a single JSON object.
            return extractChatContent(new JSONObject(raw.toString()));
        }
        if (listener != null) {
            listener.onProgress(answer.length(), reasoningChars);
        }
        return answer.toString();
    }

    static String readChatContentForTest(String sse) throws Exception {
        return readChatContent(new BufferedReader(new java.io.StringReader(sse)), null);
    }

    private static String httpErrorMessage(String provider, int code, String response, boolean chinese) {
        if (PROVIDER_MINIMAX.equals(provider) && code == 401) {
            if (chinese) {
                return "MiniMax API 认证失败（HTTP 401）。如果使用 Token Plan，请在 MiniMax 开放平台「接口密钥」里创建 Token Plan Key，通常以 sk-cp- 开头；不要粘贴网页登录态 token。Base URL 可在设置里切换：中国大陆/内网用 https://api.minimaxi.com/v1，国际/外网用 https://api.minimax.io/v1。模型可先选 MiniMax-M3 或 MiniMax-M2.7。原始响应: " + response;
            }
            return "MiniMax API authorization failed (HTTP 401). If you use Token Plan, create a Token Plan Key from MiniMax API Keys; it usually starts with sk-cp-. Do not paste a web session token. Switch the MiniMax base URL in Settings: China/mainland or intranet uses https://api.minimaxi.com/v1, international or external network uses https://api.minimax.io/v1. Start with MiniMax-M3 or MiniMax-M2.7. Raw response: " + response;
        }
        return "Cloud API failed: HTTP " + code + " " + response;
    }

    static String httpErrorMessageForTest(String provider, int code, String response, boolean chinese) {
        return httpErrorMessage(provider, code, response, chinese);
    }

    private static String extractChatContent(JSONObject json) throws Exception {
        JSONObject baseResp = json.optJSONObject("base_resp");
        if (baseResp != null && baseResp.optInt("status_code", 0) != 0) {
            throw new IllegalStateException("MiniMax API failed: " + baseResp.optInt("status_code") + " " + baseResp.optString("status_msg"));
        }
        return json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");
    }

    static String extractChatContentForTest(JSONObject json) throws Exception {
        return extractChatContent(json);
    }

    static int defaultReadTimeoutMsForTest() {
        return DEFAULT_READ_TIMEOUT_MS;
    }

    static int tasksReadTimeoutMsForTest() {
        return TASKS_READ_TIMEOUT_MS;
    }

    static int codingReadTimeoutMsForTest() {
        return CODING_READ_TIMEOUT_MS;
    }

    static int socketAbortRetriesForTest() {
        return SOCKET_ABORT_RETRIES;
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(SOCKET_ABORT_RETRY_DELAY_MS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    public static String defaultEndpoint(String provider) {
        if (PROVIDER_CUSTOM.equals(provider)) {
            return "";
        }
        if (PROVIDER_DEEPSEEK.equals(provider)) {
            return DEEPSEEK_OFFICIAL_CHAT_COMPLETIONS_ENDPOINT;
        }
        if (PROVIDER_MINIMAX.equals(provider)) {
            return MINIMAX_OPENAI_COMPATIBLE_ENDPOINT;
        }
        return "https://api.openai.com/v1/chat/completions";
    }

    public static String normalizedEndpoint(String provider, String endpoint) {
        String value = endpoint == null ? "" : endpoint.trim();
        if (value.isEmpty()) {
            return defaultEndpoint(provider);
        }
        if (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (PROVIDER_MINIMAX.equals(provider) && isMiniMaxOldOrLegacyEndpoint(value)) {
            return MINIMAX_INTERNATIONAL_OPENAI_COMPATIBLE_ENDPOINT;
        }
        if (PROVIDER_DEEPSEEK.equals(provider) && DEEPSEEK_OFFICIAL_BASE_URL.equals(value)) {
            return DEEPSEEK_OFFICIAL_CHAT_COMPLETIONS_ENDPOINT;
        }
        if (value.endsWith("/v1")) {
            return value + CHAT_COMPLETIONS_PATH;
        }
        return value;
    }

    public static String defaultModel(String provider) {
        if (PROVIDER_CUSTOM.equals(provider)) {
            return "";
        }
        if (PROVIDER_DEEPSEEK.equals(provider)) {
            return DEEPSEEK_MODEL_FLASH;
        }
        if (PROVIDER_MINIMAX.equals(provider)) {
            return MINIMAX_MODEL_M3;
        }
        return OPENAI_MODEL_GPT_54_MINI;
    }

    public static String normalizedModel(String provider, String model) {
        String value = model == null ? "" : model.trim();
        if (value.isEmpty()) {
            return defaultModel(provider);
        }
        if (PROVIDER_MINIMAX.equals(provider) && !isSupportedMiniMaxModel(value)) {
            return defaultModel(provider);
        }
        return value;
    }

    public static String scopedKey(String key, String provider) {
        return key + "_" + providerKeySuffix(provider);
    }

    public static String apiKeyForProvider(SharedPreferences prefs, String provider) {
        String value = scopedValue(prefs, provider, KEY_API_KEY, "");
        return normalizedApiKey(value);
    }

    public static String normalizedApiKey(String apiKey) {
        String value = stripApiKeyPunctuation(apiKey == null ? "" : apiKey.trim());
        int bearerIndex = indexOfIgnoreCase(value, "Bearer ");
        if (bearerIndex >= 0) {
            return stripApiKeyPunctuation(value.substring(bearerIndex + 7).trim());
        }
        int assignmentIndex = value.indexOf('=');
        if (assignmentIndex >= 0 && looksLikeApiKeyAssignment(value.substring(0, assignmentIndex))) {
            return stripApiKeyPunctuation(value.substring(assignmentIndex + 1).trim());
        }
        return value;
    }

    public static String endpointForProvider(SharedPreferences prefs, String provider) {
        return normalizedEndpoint(provider, scopedValue(prefs, provider, KEY_ENDPOINT, defaultEndpoint(provider)));
    }

    public static String modelForProvider(SharedPreferences prefs, String provider) {
        return normalizedModel(provider, scopedValue(prefs, provider, KEY_MODEL, defaultModel(provider)));
    }

    /** Whether this provider exposes a thinking/reasoning mode that the user can switch off. */
    public static boolean supportsThinkingToggle(String provider) {
        return PROVIDER_MINIMAX.equals(provider) || PROVIDER_DEEPSEEK.equals(provider);
    }

    /** Thinking mode is on by default; only MiniMax/DeepSeek honor the toggle. */
    public static boolean thinkingEnabledForProvider(SharedPreferences prefs, String provider) {
        if (!supportsThinkingToggle(provider)) {
            return true;
        }
        return !"false".equals(scopedValue(prefs, provider, KEY_THINKING, "true"));
    }

    private static String scopedValue(SharedPreferences prefs, String provider, String key, String defaultValue) {
        String scopedKey = scopedKey(key, provider);
        if (prefs.contains(scopedKey)) {
            return prefs.getString(scopedKey, defaultValue);
        }
        String savedProvider = prefs.getString(KEY_PROVIDER, PROVIDER_OPENAI);
        if (providerKeySuffix(provider).equals(providerKeySuffix(savedProvider)) && prefs.contains(key)) {
            return prefs.getString(key, defaultValue);
        }
        return defaultValue;
    }

    private static String providerKeySuffix(String provider) {
        String value = provider == null ? "" : provider.trim();
        if (value.isEmpty()) {
            return PROVIDER_OPENAI;
        }
        return value.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    public static String[] openAiModels() {
        return new String[]{
                OPENAI_MODEL_GPT_54_MINI,
                OPENAI_MODEL_GPT_55,
                OPENAI_MODEL_GPT_54,
                OPENAI_MODEL_GPT_54_NANO,
                OPENAI_MODEL_GPT_51,
                OPENAI_MODEL_GPT_5,
                OPENAI_MODEL_GPT_5_MINI,
                OPENAI_MODEL_GPT_5_NANO};
    }

    public static boolean isSupportedOpenAiModel(String model) {
        String value = model == null ? "" : model.trim();
        for (String supported : openAiModels()) {
            if (supported.equals(value)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isSupportedDeepSeekModel(String model) {
        String value = model == null ? "" : model.trim();
        return DEEPSEEK_MODEL_FLASH.equals(value) || DEEPSEEK_MODEL_PRO.equals(value);
    }

    public static boolean isDeepSeekOfficialEndpoint(String endpoint) {
        return DEEPSEEK_OFFICIAL_CHAT_COMPLETIONS_ENDPOINT.equals(normalizedEndpoint(PROVIDER_DEEPSEEK, endpoint));
    }

    public static boolean isDeepSeekOpenAiCompatibleEndpoint(String endpoint) {
        return DEEPSEEK_OPENAI_COMPATIBLE_CHAT_COMPLETIONS_ENDPOINT.equals(normalizedEndpoint(PROVIDER_DEEPSEEK, endpoint));
    }

    public static boolean isSupportedMiniMaxModel(String model) {
        String value = model == null ? "" : model.trim();
        return MINIMAX_MODEL_M3.equals(value) ||
                MINIMAX_MODEL_M27.equals(value) ||
                MINIMAX_MODEL_M27_HIGHSPEED.equals(value) ||
                MINIMAX_MODEL_M25.equals(value) ||
                MINIMAX_MODEL_M25_HIGHSPEED.equals(value) ||
                MINIMAX_MODEL_M21.equals(value) ||
                MINIMAX_MODEL_M21_HIGHSPEED.equals(value) ||
                MINIMAX_MODEL_M2.equals(value);
    }

    public static boolean isMiniMaxChinaEndpoint(String endpoint) {
        return MINIMAX_OPENAI_COMPATIBLE_ENDPOINT.equals(normalizedEndpoint(PROVIDER_MINIMAX, endpoint));
    }

    public static boolean isMiniMaxInternationalEndpoint(String endpoint) {
        return MINIMAX_INTERNATIONAL_OPENAI_COMPATIBLE_ENDPOINT.equals(normalizedEndpoint(PROVIDER_MINIMAX, endpoint));
    }

    public static String deepSeekModelsText() {
        return DEEPSEEK_MODEL_FLASH + " / " + DEEPSEEK_MODEL_PRO;
    }

    private static boolean usesDefaultSamplingParameters(String provider, String model) {
        return PROVIDER_OPENAI.equals(provider) && isOpenAiReasoningModel(model);
    }

    private static boolean isOpenAiReasoningModel(String model) {
        String value = model == null ? "" : model.trim();
        return value.startsWith("gpt-5") ||
                value.startsWith("o1") ||
                value.startsWith("o3") ||
                value.startsWith("o4");
    }

    private static boolean isMiniMaxOldOrLegacyEndpoint(String endpoint) {
        return MINIMAX_LEGACY_CHAT_COMPLETION_ENDPOINT.equals(endpoint);
    }

    private static int indexOfIgnoreCase(String value, String needle) {
        return value.toLowerCase(java.util.Locale.US).indexOf(needle.toLowerCase(java.util.Locale.US));
    }

    private static boolean looksLikeApiKeyAssignment(String name) {
        String value = name == null ? "" : name.trim();
        if (value.regionMatches(true, 0, "export ", 0, 7)) {
            value = value.substring(7).trim();
        }
        return value.endsWith("API_KEY") ||
                "OPENAI_API_KEY".equals(value) ||
                "ANTHROPIC_API_KEY".equals(value) ||
                "MINIMAX_API_KEY".equals(value);
    }

    private static String stripApiKeyPunctuation(String apiKey) {
        String value = apiKey == null ? "" : apiKey.trim();
        while (value.length() >= 2 && isWrappingQuote(value.charAt(0)) && value.charAt(value.length() - 1) == value.charAt(0)) {
            value = value.substring(1, value.length() - 1).trim();
        }
        while (!value.isEmpty() && isTrailingApiKeyPunctuation(value.charAt(value.length() - 1))) {
            value = value.substring(0, value.length() - 1).trim();
        }
        while (!value.isEmpty() && isWrappingQuote(value.charAt(0))) {
            value = value.substring(1).trim();
        }
        return value;
    }

    private static boolean isWrappingQuote(char value) {
        return value == '\'' || value == '"' || value == '`';
    }

    private static boolean isTrailingApiKeyPunctuation(char value) {
        return value == '\'' || value == '"' || value == '`' || value == ';';
    }

    private String specSystemPrompt(boolean chinese) {
        String language = chinese ? "Use Simplified Chinese for app names, field labels and user-facing text." : "Use English for app names, field labels and user-facing text.";
        return "You are executing an approved engineering plan for a small native Android app. " +
                "Respect the latest approved plan and project history. The target app must be Android 12+, Java + XML, SQLite, local-first. " +
                "Do not introduce Compose or complex third-party dependencies. Keep source isolated and buildable. " +
                VersionUpgradePolicy.prompt() + " " +
                "Return only compact JSON with keys appName, packageName, description, entityName, primaryField, secondaryField. Use ASCII package names. " + language;
    }

    private String projectFilesSystemPrompt(boolean chinese) {
        return projectFilesSystemPromptText(chinese, dependencyPolicyPrompt());
    }

    static String projectFilesSystemPromptForTest(boolean chinese) {
        return projectFilesSystemPromptText(chinese, "Dependency mode is offline safe.");
    }

    private static String projectFilesSystemPromptText(boolean chinese, String dependencyPolicyPrompt) {
        String language = chinese ? "Use Simplified Chinese for app names, labels, and user-facing text." : "Use English for app names, labels, and user-facing text.";
        return "You are the coding phase for an Android engineering agent. Implement the approved engineering plan by returning a complete buildable Android project source tree. " +
                "Target Android 12+, Java + XML, SQLite/local-first when storage is needed. Do not use Kotlin, Compose, DataBinding, or ViewBinding. Avoid complex third-party dependencies. " +
                "Return only compact JSON and no markdown. The JSON object must contain appName, packageName, description, and files. " +
                "files must be an array of objects with path and content. Paths must be relative POSIX paths. " +
                "The file list must include settings.gradle, build.gradle, app/build.gradle, app/src/main/AndroidManifest.xml, all Java source files, XML layouts, and resources needed to compile. " +
                "Keep each source file focused and small, ideally under about 250 lines; split large screens into separate Adapter, Helper, Dialog, or model classes instead of one giant file. " +
                "Use Gradle plugin com.android.application 8.7.3, compileSdk 34, minSdk 31, targetSdk 34, and Java 8-compatible source/target. Do not use Java records, switch expressions, var, lambda arrow syntax, streams-heavy code, org.jetbrains.kotlin.android, kotlinOptions, Kotlin Gradle DSL, or any .kt file. Use anonymous listener classes instead of lambdas, and avoid arrow-style examples in comments/Javadocs/strings. " +
                "Set android.namespace in app/build.gradle and do not set package=\"...\" in AndroidManifest.xml. " +
                VersionUpgradePolicy.prompt() + " " +
                dependencyProvidedResourcePolicyPrompt() + " " +
                databaseContractPolicyPrompt() + " " +
                "Before returning the project, cross-check Java API consistency across every file: every method call must have a matching declaration, every constructor call must match an existing constructor, and every directly accessed DTO/model field such as item.total must actually be declared and visible in that type or replaced with a getter. Keep Activity, Adapter, DAO, helper, and model field names synchronized. " +
                "For aggregate/statistics DTOs used by adapters, do not access item.categoryName, item.percent, or similar display fields unless that exact field exists in the DTO; otherwise add fields/getters or update adapter binding to existing fields. For DAO/helper wiring, pass the exact constructor type, e.g. do not call new CategoryDAO(dbHelper) when CategoryDAO(Context) is declared; use context or change the constructor and all callers consistently. " +
                dependencyPolicyPrompt + " " +
                "Preserve the approved plan's screens, data structure, labels, workflows, and acceptance criteria as source code. " + language;
    }

    private String tasksSystemPrompt(boolean chinese) {
        String language = chinese ? "Use Simplified Chinese for task titles." : "Use English for task titles.";
        return "You split an approved Android engineering plan into a short sequential implementation task list. " +
                "Return only compact JSON with a tasks array. Each task must have title and instruction. " +
                "Use 5 to 12 tasks. Keep each task small enough to implement with one or two file writes. " +
                "The first task should create or update the Gradle project skeleton when needed, and later tasks should add data, screens, interactions, and polish. " +
                "If the approved plan requires a version upgrade, include an implementation task that updates app/build.gradle versionCode and versionName according to that plan. " +
                "Do not include build or install tasks. " + language;
    }

    private String taskOperationsSystemPrompt(boolean chinese) {
        return taskOperationsSystemPromptText(chinese, dependencyPolicyPrompt());
    }

    static String taskOperationsSystemPromptForTest(boolean chinese) {
        return taskOperationsSystemPromptText(chinese, "Dependency mode is offline safe.");
    }

    private static String taskOperationsSystemPromptText(boolean chinese, String dependencyPolicyPrompt) {
        String language = chinese ? "Use Simplified Chinese for user-facing app text when appropriate." : "Use English for user-facing app text.";
        return "You execute one small Android coding task by returning file operations only. " +
                "Return only compact JSON with summary and operations. operations is an array of objects with action, path, and content. " +
                "Supported actions are write and delete. Use write for full-file replacement. Use relative POSIX paths only. Prefer one or two file operations. " +
                "Keep each source file focused and small, ideally under about 250 lines; split large screens into separate Adapter, Helper, Dialog, or model classes instead of one giant file, so each write replaces a small file. " +
                "Do not return an empty operations array; every task response must include at least one write or delete operation that advances the task. " +
                "Do not return markdown, comments outside JSON, explanations, build logs, or base64. " +
                "Keep the generated source buildable with Android Gradle Plugin 8.7.3, compileSdk 34, minSdk 31, targetSdk 34, and Java 8-compatible source/target. " +
                "Use Java + XML only. Do not write Kotlin, .kt files, kotlinOptions, Kotlin Gradle plugins, DataBinding, ViewBinding, Compose, Java lambdas, or arrow syntax. Use anonymous listener classes instead of lambdas, and do not include arrow-style examples in comments/Javadocs/strings. Prefer org.json over Gson unless a Gson dependency is already declared and allowed. " + dependencyPolicyPrompt + " " +
                "When writing Java files, keep package names consistent with Gradle namespace. Set android.namespace in app/build.gradle and do not set package=\"...\" in AndroidManifest.xml. " +
                VersionUpgradePolicy.prompt() + " " +
                dependencyProvidedResourcePolicyPrompt() + " " +
                databaseContractPolicyPrompt() + " " +
                "Before returning operations, cross-check Java API consistency across all touched files: every method call must have a matching declaration, every constructor call must match an existing constructor, and every directly accessed DTO/model field such as item.total must actually be declared and visible in that type or replaced with a getter/setter. Update Activity, Adapter, DAO, helper, and model files together when their APIs interact. " +
                "For aggregate/statistics DTOs used by adapters, do not access item.categoryName, item.percent, or similar display fields unless that exact field exists in the DTO; otherwise add fields/getters or update adapter binding to existing fields. For DAO/helper wiring, pass the exact constructor type, e.g. do not call new CategoryDAO(dbHelper) when CategoryDAO(Context) is declared; use context or change the constructor and all callers consistently. " +
                "Declare every view variable with findViewById from the Activity, inflated root view, or dialog view before using it; never use bare view ids such as fabAdd.setOnClickListener or textIncomeAmount.setText. " +
                "Every R.id.* referenced by Java must exist as android:id=\"@+id/...\" in XML, every R.* resource used in code must exist, and every XML reference such as @mipmap/ic_launcher, @style/AppTheme, @drawable/name, @string/name, @color/name, or @layout/name must have a matching resource file or values entry. " + language;
    }

    private static String dependencyProvidedResourcePolicyPrompt() {
        return "Do not reference dependency-provided XML resources or behavior strings such as @string/appbar_scrolling_view_behavior, and do not use CoordinatorLayout, AppBarLayout, CollapsingToolbarLayout, MaterialToolbar, or app:layout_behavior unless the dependency is explicitly declared and resolvable. In offline-safe projects, use Android SDK layouts such as LinearLayout, FrameLayout, ScrollView, ListView, or plain Toolbar instead.";
    }

    private static String databaseContractPolicyPrompt() {
        return "For SQLite features, keep the database contract synchronized as one unit: DBHelper table names and DBHelper.COL_ column constants, CREATE TABLE SQL, model fields/getters/setters, DAO CRUD/query method signatures, Activity callers, and Adapter binders must all agree. If a caller uses DBHelper.COL_CATEGORY_ID, DBHelper must declare that exact constant; if a screen or helper such as JsonBackup.java calls RecordDao.listAll(), update(Record), delete(long), countByCategory(long), or queryByType(int), the DAO must declare that exact method or the caller must use an existing DAO method.";
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
        return planSystemPromptText(chinese);
    }

    static String planSystemPromptForTest(boolean chinese) {
        return planSystemPromptText(chinese);
    }

    private static String planSystemPromptText(boolean chinese) {
        if (chinese) {
            return "你是安卓工程 Agent 的规划阶段。只输出工程计划，不写源码，不返回 JSON。内置约束：先澄清目标并拆解计划，不直接编码；目标 App 必须是 Android 12+、Java + XML、SQLite、本地优先；不使用 Kotlin、Compose、DataBinding、ViewBinding，不引入复杂第三方依赖；每次改动必须保持现有项目上下文、源码隔离、可构建。版本升级规则：若需求涉及 App 版本、发布版本、构建号、升级、发版、测试版或 APK 迭代，计划必须明确要求同步升级 app/build.gradle 里的 versionCode 和 versionName：versionCode 必须大于当前值，versionName 使用需求指定版本；未指定时递增补丁号；禁止降级。输出必须以“# 工程计划”开头，并包含这些小节：需求理解、页面/功能、数据结构、源码改动点、工程约束、测试清单、验收标准、构建风险。计划要具体到可以交给编码阶段执行。";
        }
        return "You are in the planning phase for an Android engineering agent. Output an engineering plan only: no source code and no JSON. Built-in constraints: clarify the goal and break down the plan before coding; the target app must be Android 12+, Java + XML, SQLite, local-first; do not use Kotlin, Compose, DataBinding, ViewBinding, or complex third-party dependencies; preserve project context, source isolation, and buildability on every change. " + VersionUpgradePolicy.prompt() + " The response must start with '# Engineering Plan' and include: Requirement Understanding, Screens/Features, Data Structure, Source Changes, Engineering Constraints, Test Checklist, Acceptance Criteria, Build Risks. Make it concrete enough for the coding phase to execute.";
    }

    private static JSONObject message(String role, String content) throws Exception {
        JSONObject message = new JSONObject();
        message.put("role", role);
        message.put("content", content == null ? "" : content);
        return message;
    }
}
