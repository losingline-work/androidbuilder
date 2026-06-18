package com.androidbuilder.agent;

import android.content.Context;
import android.content.SharedPreferences;

import com.androidbuilder.backend.BuildBackendSettings;
import com.androidbuilder.model.ChatMessage;
import com.androidbuilder.model.TaskManifest;

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
    private static final int MODEL_READ_TIMEOUT_MS = 3 * 60 * 1000;
    private static final int DEFAULT_READ_TIMEOUT_MS = MODEL_READ_TIMEOUT_MS;
    private static final int TASKS_READ_TIMEOUT_MS = MODEL_READ_TIMEOUT_MS;
    private static final int CODING_READ_TIMEOUT_MS = MODEL_READ_TIMEOUT_MS;
    private static final int SOCKET_ABORT_RETRIES = 2;
    private static final int SOCKET_ABORT_RETRY_DELAY_MS = 1500;
    private static final int PROGRESS_EMIT_CHARS = 120;

    /** Reports streaming progress while a response is being received. Called on a worker thread. */
    public interface ProgressListener {
        void onProgress(String callTag, int answerChars, int reasoningChars);
    }

    /** Inspects accumulated streamed answer content and may abort runaway or unsafe output. */
    public interface StreamInspector {
        void onContent(String answerSoFar) throws StreamAbortException;
    }

    public static final class StreamAbortException extends Exception {
        public StreamAbortException(String message) {
            super(message);
        }
    }

    public static final String PREFS = "cloud_api";
    public static final String KEY_API_KEY = "api_key";
    public static final String KEY_ENDPOINT = "endpoint";
    public static final String KEY_MODEL = "model";
    public static final String KEY_PROVIDER = "provider";
    public static final String KEY_THINKING = "thinking_enabled";
    public static final String KEY_BATCHED_GENERATION = "batched_generation";
    public static final String PROVIDER_OPENAI = "openai";
    public static final String PROVIDER_DEEPSEEK = "deepseek";
    public static final String PROVIDER_MINIMAX = "minimax";
    // Mainstream OpenAI-compatible providers (same /chat/completions wire format; only base URL + model differ).
    public static final String PROVIDER_ZHIPU = "zhipu";
    public static final String PROVIDER_MOONSHOT = "moonshot";
    public static final String PROVIDER_QWEN = "qwen";
    public static final String PROVIDER_DOUBAO = "doubao";
    public static final String PROVIDER_OPENROUTER = "openrouter";
    public static final String PROVIDER_GROQ = "groq";
    // Native-protocol providers (different request/auth/streaming from OpenAI).
    public static final String PROVIDER_ANTHROPIC = "anthropic";
    public static final String PROVIDER_GEMINI = "gemini";
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

    /** An OpenAI-compatible provider preset: full chat-completions URL + curated model list. */
    static final class ProviderSpec {
        final String id;
        final String defaultEndpoint;
        final String defaultModel;
        final String[] models;

        ProviderSpec(String id, String defaultEndpoint, String defaultModel, String[] models) {
            this.id = id;
            this.defaultEndpoint = defaultEndpoint;
            this.defaultModel = defaultModel;
            this.models = models;
        }
    }

    // OpenAI-compatible mainstream providers, in UI order. Adding one is a single entry — the request body,
    // streaming parse, and Bearer auth are all reused unchanged. Model ids are curated defaults the user can
    // override (they churn; a stale id surfaces only as model-not-found on a real call).
    private static final java.util.LinkedHashMap<String, ProviderSpec> SPECS = new java.util.LinkedHashMap<>();

    static {
        SPECS.put(PROVIDER_ZHIPU, new ProviderSpec(PROVIDER_ZHIPU,
                "https://open.bigmodel.cn/api/paas/v4/chat/completions", "glm-4.6",
                new String[]{"glm-4.6", "glm-4.5", "glm-4.5-air", "glm-4-flash"}));
        SPECS.put(PROVIDER_MOONSHOT, new ProviderSpec(PROVIDER_MOONSHOT,
                "https://api.moonshot.cn/v1/chat/completions", "kimi-k2-0905-preview",
                new String[]{"kimi-k2-0905-preview", "kimi-k2-turbo-preview", "moonshot-v1-128k", "moonshot-v1-32k"}));
        SPECS.put(PROVIDER_QWEN, new ProviderSpec(PROVIDER_QWEN,
                "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", "qwen-plus",
                new String[]{"qwen-max", "qwen-plus", "qwen-turbo", "qwen3-coder-plus"}));
        SPECS.put(PROVIDER_DOUBAO, new ProviderSpec(PROVIDER_DOUBAO,
                "https://ark.cn-beijing.volces.com/api/v3/chat/completions", "doubao-seed-1-6-250615",
                new String[]{"doubao-seed-1-6-250615", "doubao-1-5-pro-32k-250115", "doubao-1-5-pro-256k-250115"}));
        SPECS.put(PROVIDER_OPENROUTER, new ProviderSpec(PROVIDER_OPENROUTER,
                "https://openrouter.ai/api/v1/chat/completions", "anthropic/claude-sonnet-4.5",
                new String[]{"anthropic/claude-sonnet-4.5", "google/gemini-2.5-pro", "deepseek/deepseek-chat",
                        "meta-llama/llama-3.3-70b-instruct", "openai/gpt-4o"}));
        SPECS.put(PROVIDER_GROQ, new ProviderSpec(PROVIDER_GROQ,
                "https://api.groq.com/openai/v1/chat/completions", "llama-3.3-70b-versatile",
                new String[]{"llama-3.3-70b-versatile", "llama-3.1-8b-instant", "qwen-2.5-32b",
                        "deepseek-r1-distill-llama-70b"}));
    }

    /** The OpenAI-compatible preset for this provider, or null if it is not a table provider. */
    static ProviderSpec specFor(String provider) {
        return provider == null ? null : SPECS.get(provider);
    }

    /** The OpenAI-compatible mainstream provider ids, in UI order. */
    public static String[] mainstreamCompatibleProviders() {
        return SPECS.keySet().toArray(new String[0]);
    }

    /** The curated model id list for a provider's picker; empty for custom. */
    public static String[] modelsForProvider(String provider) {
        ProviderSpec spec = specFor(provider);
        if (spec != null) {
            return spec.models.clone();
        }
        if (PROVIDER_OPENAI.equals(provider)) {
            return new String[]{OPENAI_MODEL_GPT_55, OPENAI_MODEL_GPT_54, OPENAI_MODEL_GPT_54_MINI,
                    OPENAI_MODEL_GPT_54_NANO, OPENAI_MODEL_GPT_51, OPENAI_MODEL_GPT_5,
                    OPENAI_MODEL_GPT_5_MINI, OPENAI_MODEL_GPT_5_NANO};
        }
        if (PROVIDER_DEEPSEEK.equals(provider)) {
            return new String[]{DEEPSEEK_MODEL_FLASH, DEEPSEEK_MODEL_PRO};
        }
        if (PROVIDER_MINIMAX.equals(provider)) {
            return new String[]{MINIMAX_MODEL_M3, MINIMAX_MODEL_M27, MINIMAX_MODEL_M27_HIGHSPEED,
                    MINIMAX_MODEL_M25, MINIMAX_MODEL_M25_HIGHSPEED, MINIMAX_MODEL_M21,
                    MINIMAX_MODEL_M21_HIGHSPEED, MINIMAX_MODEL_M2};
        }
        if (PROVIDER_ANTHROPIC.equals(provider)) {
            return anthropicModels();
        }
        if (PROVIDER_GEMINI.equals(provider)) {
            return geminiModels();
        }
        return new String[0];
    }

    // --- Native-protocol providers (Anthropic Messages API, Google Gemini generateContent) ---

    public static final String ANTHROPIC_BASE_URL = "https://api.anthropic.com";
    public static final String ANTHROPIC_MESSAGES_ENDPOINT = ANTHROPIC_BASE_URL + "/v1/messages";
    public static final String ANTHROPIC_VERSION = "2023-06-01";
    public static final String ANTHROPIC_MODEL_OPUS = "claude-opus-4-8";
    public static final String ANTHROPIC_MODEL_SONNET = "claude-sonnet-4-6";
    public static final String ANTHROPIC_MODEL_HAIKU = "claude-haiku-4-5";

    public static final String GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta";
    public static final String GEMINI_STREAM_SUFFIX = ":streamGenerateContent?alt=sse";
    public static final String GEMINI_MODEL_FLASH = "gemini-2.5-flash";
    public static final String GEMINI_MODEL_PRO = "gemini-2.5-pro";

    static String[] anthropicModels() {
        return new String[]{ANTHROPIC_MODEL_OPUS, ANTHROPIC_MODEL_SONNET, ANTHROPIC_MODEL_HAIKU};
    }

    static String[] geminiModels() {
        return new String[]{GEMINI_MODEL_FLASH, GEMINI_MODEL_PRO, "gemini-2.5-flash-lite", "gemini-2.0-flash"};
    }

    /** True for providers whose request body, auth, and streaming differ from the OpenAI wire format. */
    static boolean isNativeProvider(String provider) {
        return PROVIDER_ANTHROPIC.equals(provider) || PROVIDER_GEMINI.equals(provider);
    }
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
        return completeChat(specSystemPrompt(chinese), messages, "Latest approved implementation request: " + latestPrompt, 0.2, chinese, DEFAULT_READ_TIMEOUT_MS, true);
    }

    public String createProjectFilesJson(List<ChatMessage> messages, String latestPrompt, boolean chinese) throws Exception {
        return completeChat(projectFilesSystemPrompt(chinese), messages, "Latest approved engineering plan to implement: " + latestPrompt, 0.2, chinese, CODING_READ_TIMEOUT_MS, true);
    }

    public String createEngineeringPlan(List<ChatMessage> messages, String latestPrompt, boolean chinese) throws Exception {
        return completeChat(planSystemPrompt(chinese), messages, "Latest requirement or plan change: " + latestPrompt, 0.3, chinese, DEFAULT_READ_TIMEOUT_MS);
    }

    public String createImplementationTasks(String plan, boolean chinese) throws Exception {
        return completeChat(tasksSystemPrompt(chinese), java.util.Collections.emptyList(), "Approved engineering plan:\n\n" + plan, 0.2, chinese, TASKS_READ_TIMEOUT_MS, true);
    }

    /** Pre-build code review: returns the reviewer's JSON findings ({@link CodeReviewParser} parses it). */
    public String reviewGeneratedCode(String sourceSnapshot, boolean chinese, String callTag) throws Exception {
        return completeChat(
                CodeReviewPrompt.systemPrompt(chinese),
                java.util.Collections.emptyList(),
                CodeReviewPrompt.userPrompt(sourceSnapshot, chinese),
                0.1,
                chinese,
                CODING_READ_TIMEOUT_MS,
                true,
                callTag);
    }

    public String createTaskManifest(String plan, String taskTitle, String taskInstruction, String sourceSnapshot, String recentRequirements, String retryContext, boolean chinese, String callTag) throws Exception {
        return completeChat(
                taskManifestSystemPrompt(chinese),
                java.util.Collections.emptyList(),
                taskOperationsUserPrompt(plan, taskTitle, instructionWithGraphicsPolicy(taskInstruction, chinese), sourceSnapshot, recentRequirements, retryContext, ""),
                0.1,
                chinese,
                CODING_READ_TIMEOUT_MS,
                true,
                callTag);
    }

    public String createTaskOperations(String plan, String taskTitle, String taskInstruction, String sourceSnapshot, String recentRequirements, boolean chinese) throws Exception {
        return createTaskOperations(plan, taskTitle, taskInstruction, sourceSnapshot, recentRequirements, "", chinese);
    }

    public String createTaskOperations(String plan, String taskTitle, String taskInstruction, String sourceSnapshot, String recentRequirements, String retryContext, boolean chinese) throws Exception {
        return createTaskOperations(plan, taskTitle, taskInstruction, sourceSnapshot, recentRequirements, retryContext, chinese, "");
    }

    public String createTaskOperations(String plan, String taskTitle, String taskInstruction, String sourceSnapshot, String recentRequirements, String retryContext, boolean chinese, String callTag) throws Exception {
        return createTaskOperations(plan, taskTitle, taskInstruction, sourceSnapshot, recentRequirements, retryContext, "", chinese, callTag);
    }

    public String createTaskOperations(String plan, String taskTitle, String taskInstruction, String sourceSnapshot, String recentRequirements, String retryContext, String previousDraftSection, boolean chinese, String callTag) throws Exception {
        return createTaskOperations(plan, taskTitle, taskInstruction, sourceSnapshot, recentRequirements, retryContext, previousDraftSection, chinese, callTag, null);
    }

    public String createTaskOperations(String plan, String taskTitle, String taskInstruction, String sourceSnapshot, String recentRequirements, String retryContext, String previousDraftSection, boolean chinese, String callTag, StreamInspector streamInspector) throws Exception {
        return completeChat(
                taskOperationsSystemPrompt(chinese),
                java.util.Collections.emptyList(),
                taskOperationsUserPrompt(plan, taskTitle, instructionWithGraphicsPolicy(taskInstruction, chinese), sourceSnapshot, recentRequirements, retryContext, previousDraftSection),
                0.2,
                chinese,
                CODING_READ_TIMEOUT_MS,
                true,
                callTag,
                streamInspector);
    }

    public String createTaskOperationsBatch(String plan, String taskTitle, String taskInstruction, String sourceSnapshot, String recentRequirements, String retryContext, List<TaskManifest.Entry> batchFiles, String completedFilesContext, boolean chinese, String callTag, StreamInspector streamInspector) throws Exception {
        return completeChat(
                taskOperationsSystemPrompt(chinese),
                java.util.Collections.emptyList(),
                taskOperationsBatchUserPrompt(plan, taskTitle, instructionWithGraphicsPolicy(taskInstruction, chinese), sourceSnapshot, recentRequirements, retryContext, batchFiles, completedFilesContext),
                0.2,
                chinese,
                CODING_READ_TIMEOUT_MS,
                true,
                callTag,
                streamInspector);
    }

    public String negotiateTaskContext(String plan, String taskTitle, String taskInstruction, String sourceSnapshot, String recentRequirements, String previousFailure, boolean chinese) throws Exception {
        return negotiateTaskContext(plan, taskTitle, taskInstruction, sourceSnapshot, recentRequirements, previousFailure, chinese, "");
    }

    public String negotiateTaskContext(String plan, String taskTitle, String taskInstruction, String sourceSnapshot, String recentRequirements, String previousFailure, boolean chinese, String callTag) throws Exception {
        return completeChat(
                contextNegotiationSystemPrompt(chinese),
                java.util.Collections.emptyList(),
                contextNegotiationUserPrompt(plan, taskTitle, taskInstruction, sourceSnapshot, recentRequirements, previousFailure, chinese),
                0.0,
                chinese,
                DEFAULT_READ_TIMEOUT_MS,
                true,
                callTag);
    }

    public String reviewTaskOperations(String taskTitle, String taskInstruction, String sourceSnapshot, String operationsJson, String contextScoutNotes, boolean chinese) throws Exception {
        return reviewTaskOperations(taskTitle, taskInstruction, sourceSnapshot, operationsJson, contextScoutNotes, chinese, "");
    }

    public String reviewTaskOperations(String taskTitle, String taskInstruction, String sourceSnapshot, String operationsJson, String contextScoutNotes, boolean chinese, String callTag) throws Exception {
        return completeChat(
                hermesReviewSystemPrompt(chinese),
                java.util.Collections.emptyList(),
                hermesReviewUserPrompt(taskTitle, taskInstruction, sourceSnapshot, operationsJson, contextScoutNotes),
                0.0,
                chinese,
                DEFAULT_READ_TIMEOUT_MS,
                true,
                callTag);
    }

    public String createPolicyRewriteHint(String taskInstruction, String policyError, String focusedSnapshot, int attempt, boolean chinese) throws Exception {
        return completeChat(
                policyRewriteSystemPrompt(chinese),
                java.util.Collections.emptyList(),
                policyRewriteUserPrompt(taskInstruction, policyError, focusedSnapshot, attempt),
                0.0,
                chinese,
                DEFAULT_READ_TIMEOUT_MS).trim();
    }

    public String createBuildFailureTriageHint(String buildLog, String focusedSnapshot, boolean chinese) throws Exception {
        return completeChat(
                buildFailureTriageSystemPrompt(chinese),
                java.util.Collections.emptyList(),
                buildFailureTriageUserPrompt(buildLog, focusedSnapshot),
                0.0,
                chinese,
                DEFAULT_READ_TIMEOUT_MS).trim();
    }

    private String completeChat(String systemPrompt, List<ChatMessage> messages, String latestUserMessage, double temperature, boolean chinese, int readTimeoutMs) throws Exception {
        return completeChat(systemPrompt, messages, latestUserMessage, temperature, chinese, readTimeoutMs, false);
    }

    private String completeChat(String systemPrompt, List<ChatMessage> messages, String latestUserMessage, double temperature, boolean chinese, int readTimeoutMs, boolean structuredOutput) throws Exception {
        return completeChat(systemPrompt, messages, latestUserMessage, temperature, chinese, readTimeoutMs, structuredOutput, "");
    }

    private String completeChat(String systemPrompt, List<ChatMessage> messages, String latestUserMessage, double temperature, boolean chinese, int readTimeoutMs, boolean structuredOutput, String callTag) throws Exception {
        return completeChat(systemPrompt, messages, latestUserMessage, temperature, chinese, readTimeoutMs, structuredOutput, callTag, null);
    }

    private String completeChat(String systemPrompt, List<ChatMessage> messages, String latestUserMessage, double temperature, boolean chinese, int readTimeoutMs, boolean structuredOutput, String callTag, StreamInspector streamInspector) throws Exception {
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
        boolean thinkingEnabled = effectiveThinking(thinkingEnabledForProvider(prefs, provider), structuredOutput);
        JSONObject body;
        String requestUrl = endpoint;
        if (PROVIDER_ANTHROPIC.equals(provider)) {
            body = anthropicRequestBody(model, systemPrompt, messages, latestUserMessage);
        } else if (PROVIDER_GEMINI.equals(provider)) {
            body = geminiRequestBody(model, systemPrompt, messages, latestUserMessage, temperature);
            requestUrl = geminiStreamUrl(endpoint, model); // Gemini puts the model in the URL path
        } else {
            body = chatRequestBody(provider, model, systemPrompt, messages, latestUserMessage, temperature, thinkingEnabled);
        }

        for (int attempt = 0; attempt <= SOCKET_ABORT_RETRIES; attempt++) {
            try {
                return executeChatRequest(requestUrl, apiKey, body, readTimeoutMs, provider, chinese, callTag, streamInspector);
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

    // Cap the response so a large generation actually fits. Left unset, OpenAI-compatible providers fall
    // back to a small default (~4K tokens), which truncated big task responses mid-array ("Unterminated
    // array") and looped the task to exhaustion. 8192 is the standard max output for the supported models
    // (DeepSeek 8K; OpenAI/MiniMax allow more), so it raises the budget without exceeding any model's
    // limit, and also bounds runaway repetition.
    static final int MAX_OUTPUT_TOKENS = 8192;

    private static JSONObject chatRequestBody(String provider, String model, String systemPrompt, List<ChatMessage> messages, String latestUserMessage, double temperature, boolean thinkingEnabled) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("stream", true);
        body.put("max_tokens", MAX_OUTPUT_TOKENS);
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

    private String executeChatRequest(String endpoint, String apiKey, JSONObject body, int readTimeoutMs, String provider, boolean chinese, String callTag) throws Exception {
        return executeChatRequest(endpoint, apiKey, body, readTimeoutMs, provider, chinese, callTag, null);
    }

    private String executeChatRequest(String endpoint, String apiKey, JSONObject body, int readTimeoutMs, String provider, boolean chinese, String callTag, StreamInspector streamInspector) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(readTimeoutMs);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        if (PROVIDER_ANTHROPIC.equals(provider)) {
            // Anthropic uses x-api-key + a version header, NOT Authorization: Bearer.
            connection.setRequestProperty("x-api-key", apiKey);
            connection.setRequestProperty("anthropic-version", ANTHROPIC_VERSION);
        } else if (PROVIDER_GEMINI.equals(provider)) {
            connection.setRequestProperty("x-goog-api-key", apiKey);
        } else {
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        }
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
            if (PROVIDER_ANTHROPIC.equals(provider)) {
                return readNativeStreamContent(reader, OpenAiClient::extractAnthropicDelta, progressListener, callTag, streamInspector);
            }
            if (PROVIDER_GEMINI.equals(provider)) {
                return readNativeStreamContent(reader, OpenAiClient::extractGeminiDelta, progressListener, callTag, streamInspector);
            }
            return readChatContent(reader, progressListener, callTag, streamInspector);
        }
    }

    // --- Native request bodies + streaming readers (Anthropic Messages API, Gemini generateContent) ---

    private static JSONObject anthropicRequestBody(String model, String system, List<ChatMessage> messages, String latestUser) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("max_tokens", MAX_OUTPUT_TOKENS); // required by the Messages API
        body.put("stream", true);
        body.put("system", system); // top-level string, not a message
        JSONArray chat = new JSONArray();
        for (ChatMessage message : messages) {
            if ("user".equals(message.role) || "assistant".equals(message.role)) {
                chat.put(new JSONObject().put("role", message.role).put("content", message.content));
            }
        }
        chat.put(new JSONObject().put("role", "user").put("content", latestUser));
        body.put("messages", chat);
        return body;
    }

    static JSONObject anthropicRequestBodyForTest(String model, String system, List<ChatMessage> messages, String latestUser) throws Exception {
        return anthropicRequestBody(model, system, messages, latestUser);
    }

    private static JSONObject geminiRequestBody(String model, String system, List<ChatMessage> messages, String latestUser, double temperature) throws Exception {
        JSONObject body = new JSONObject(); // NOTE: no top-level "model" — the model lives in the URL path
        JSONArray contents = new JSONArray();
        for (ChatMessage message : messages) {
            if ("user".equals(message.role)) {
                contents.put(geminiContent("user", message.content));
            } else if ("assistant".equals(message.role)) {
                contents.put(geminiContent("model", message.content)); // Gemini calls the assistant role "model"
            }
        }
        contents.put(geminiContent("user", latestUser));
        body.put("contents", contents);
        body.put("systemInstruction", new JSONObject().put("parts",
                new JSONArray().put(new JSONObject().put("text", system))));
        body.put("generationConfig", new JSONObject()
                .put("maxOutputTokens", MAX_OUTPUT_TOKENS).put("temperature", temperature));
        return body;
    }

    private static JSONObject geminiContent(String role, String text) throws Exception {
        return new JSONObject().put("role", role).put("parts",
                new JSONArray().put(new JSONObject().put("text", text)));
    }

    static JSONObject geminiRequestBodyForTest(String model, String system, List<ChatMessage> messages, String latestUser, double temperature) throws Exception {
        return geminiRequestBody(model, system, messages, latestUser, temperature);
    }

    /** The full Gemini streaming URL: {base}/models/{model}:streamGenerateContent?alt=sse. */
    static String geminiStreamUrl(String base, String model) {
        String value = base == null ? "" : base.trim();
        if (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value + "/models/" + model + GEMINI_STREAM_SUFFIX;
    }

    /** Extracts one Anthropic text delta: only a content_block_delta carrying a text_delta yields text. */
    static String extractAnthropicDelta(JSONObject payload) {
        if (payload == null || !"content_block_delta".equals(payload.optString("type", ""))) {
            return "";
        }
        JSONObject delta = payload.optJSONObject("delta");
        if (delta == null || !"text_delta".equals(delta.optString("type", ""))) {
            return ""; // thinking_delta / signature_delta etc. carry no answer text
        }
        return delta.isNull("text") ? "" : delta.optString("text", "");
    }

    /** Extracts the text from one Gemini chunk: candidates[0].content.parts[*].text (skips non-text parts). */
    static String extractGeminiDelta(JSONObject payload) {
        JSONArray candidates = payload == null ? null : payload.optJSONArray("candidates");
        JSONObject first = candidates == null || candidates.length() == 0 ? null : candidates.optJSONObject(0);
        JSONObject content = first == null ? null : first.optJSONObject("content");
        JSONArray parts = content == null ? null : content.optJSONArray("parts");
        if (parts == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.optJSONObject(i);
            if (part != null && !part.isNull("text")) {
                text.append(part.optString("text", ""));
            }
        }
        return text.toString();
    }

    interface NativeDeltaExtractor {
        String extract(JSONObject payload);
    }

    /**
     * Reads a native SSE stream (Anthropic/Gemini), accumulating text via {@code extractor}. Terminates on
     * connection close (Gemini sends no {@code [DONE]}; an inert {@code [DONE]} is tolerated). Mirrors
     * {@link #readChatContent}'s progress emission but leaves the OpenAI-compatible path untouched.
     */
    static String readNativeStreamContent(BufferedReader reader, NativeDeltaExtractor extractor,
            ProgressListener listener, String callTag, StreamInspector streamInspector) throws Exception {
        StringBuilder answer = new StringBuilder();
        int lastEmitted = -1;
        String line;
        while ((line = reader.readLine()) != null) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("data:")) {
                continue; // ignore SSE event:/id:/blank/comment lines
            }
            String payload = trimmed.substring(5).trim();
            if (payload.isEmpty() || "[DONE]".equals(payload)) {
                continue;
            }
            try {
                answer.append(extractor.extract(new JSONObject(payload)));
            } catch (Exception ignored) {
                // tolerate keep-alive comments / non-JSON frames
            }
            if (listener != null || streamInspector != null) {
                if (answer.length() - lastEmitted >= PROGRESS_EMIT_CHARS) {
                    lastEmitted = answer.length();
                    if (listener != null) {
                        listener.onProgress(cleanCallTag(callTag), answer.length(), 0);
                    }
                    if (streamInspector != null) {
                        streamInspector.onContent(answer.toString());
                    }
                }
            }
        }
        if (listener != null) {
            listener.onProgress(cleanCallTag(callTag), answer.length(), 0);
        }
        if (streamInspector != null) {
            streamInspector.onContent(answer.toString());
        }
        return answer.toString();
    }

    static String readNativeStreamContentForTest(String sse, NativeDeltaExtractor extractor) throws Exception {
        return readNativeStreamContent(new BufferedReader(new java.io.StringReader(sse)), extractor, null, "", null);
    }

    /**
     * Reads an OpenAI-compatible streaming (SSE) chat response, accumulating the answer content
     * and reporting progress. Falls back to parsing a single non-streamed JSON body if the server
     * ignored {@code stream:true}.
     */
    static String readChatContent(BufferedReader reader, ProgressListener listener) throws Exception {
        return readChatContent(reader, listener, "");
    }

    static String readChatContent(BufferedReader reader, ProgressListener listener, String callTag) throws Exception {
        return readChatContent(reader, listener, callTag, null);
    }

    static String readChatContent(BufferedReader reader, ProgressListener listener, String callTag, StreamInspector streamInspector) throws Exception {
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
                            // Guard against explicit JSON null: org.json's optString returns the literal
                            // string "null" (not the fallback) for a value of JSONObject.NULL, which a
                            // provider sends for content during reasoning-only deltas. Appending that
                            // pollutes the answer with runs of "null" (seen corrupting generated plans).
                            if (!delta.isNull("content")) {
                                answer.append(delta.optString("content", ""));
                            }
                            if (!delta.isNull("reasoning_content")) {
                                reasoningChars += delta.optString("reasoning_content", "").length();
                            }
                        }
                    }
                } catch (Exception ignored) {
                    // Tolerate keep-alive comments and non-JSON data frames.
                }
                if (listener != null || streamInspector != null) {
                    int total = answer.length() + reasoningChars;
                    if (total - lastEmitted >= PROGRESS_EMIT_CHARS) {
                        lastEmitted = total;
                        if (listener != null) {
                            listener.onProgress(cleanCallTag(callTag), answer.length(), reasoningChars);
                        }
                        if (streamInspector != null) {
                            streamInspector.onContent(answer.toString());
                        }
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
            listener.onProgress(cleanCallTag(callTag), answer.length(), reasoningChars);
        }
        if (streamInspector != null) {
            streamInspector.onContent(answer.toString());
        }
        return answer.toString();
    }

    private static String cleanCallTag(String callTag) {
        return callTag == null ? "" : callTag.trim();
    }

    static String readChatContentForTest(String sse) throws Exception {
        return readChatContent(new BufferedReader(new java.io.StringReader(sse)), null);
    }

    static String readChatContentForTest(String sse, StreamInspector streamInspector) throws Exception {
        return readChatContent(new BufferedReader(new java.io.StringReader(sse)), null, "", streamInspector);
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
        if (PROVIDER_ANTHROPIC.equals(provider)) {
            return ANTHROPIC_MESSAGES_ENDPOINT;
        }
        if (PROVIDER_GEMINI.equals(provider)) {
            return GEMINI_BASE_URL;
        }
        ProviderSpec spec = specFor(provider);
        if (spec != null) {
            return spec.defaultEndpoint;
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
        // Native providers have non-/chat/completions endpoints: Anthropic /v1/messages (append to a bare
        // base); Gemini keeps a bare base (the model-in-path URL is built at request time), never appended to.
        if (PROVIDER_ANTHROPIC.equals(provider)) {
            return value.endsWith("/v1/messages") ? value : value + "/v1/messages";
        }
        if (PROVIDER_GEMINI.equals(provider)) {
            return value;
        }
        // A hand-pasted bare base for Zhipu (/api/paas/v4) or Ark/Doubao (/api/v3) needs the chat path; the
        // /v1 family is covered by the rule below (Qwen compatible-mode/v1, Moonshot/Groq/OpenRouter /v1).
        if (value.endsWith("/api/paas/v4") || value.endsWith("/api/v3")) {
            return value + CHAT_COMPLETIONS_PATH;
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
        if (PROVIDER_ANTHROPIC.equals(provider)) {
            return ANTHROPIC_MODEL_OPUS;
        }
        if (PROVIDER_GEMINI.equals(provider)) {
            return GEMINI_MODEL_FLASH;
        }
        ProviderSpec spec = specFor(provider);
        if (spec != null) {
            return spec.defaultModel;
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

    public static boolean batchedGenerationEnabled(SharedPreferences prefs) {
        return prefs == null || !"false".equals(prefs.getString(KEY_BATCHED_GENERATION, "true"));
    }

    static boolean effectiveThinkingForTest(boolean userEnabled, boolean structuredOutput) {
        return effectiveThinking(userEnabled, structuredOutput);
    }

    private static boolean effectiveThinking(boolean userEnabled, boolean structuredOutput) {
        return userEnabled && !structuredOutput;
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

    private static String truncatePrompt(String value, int limit) {
        String text = value == null ? "" : value;
        if (text.length() <= limit) {
            return text;
        }
        return text.substring(0, Math.max(0, limit - 15)) + "\n...[truncated]";
    }

    private static String tailPrompt(String value, int limit) {
        String text = value == null ? "" : value;
        if (text.length() <= limit) {
            return text;
        }
        return "...[truncated]\n" + text.substring(text.length() - limit);
    }

    private String specSystemPrompt(boolean chinese) {
        String language = chinese ? "Use Simplified Chinese for app names, field labels and user-facing text." : "Use English for app names, field labels and user-facing text.";
        return "You are executing an approved engineering plan for a small native Android app. " +
                "Respect the latest approved plan and project history. The target app must be Android 7.0+ compatible, Java + XML, SQLite, local-first. " +
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
                "Target Android 7.0+ compatible apps, Java + XML, SQLite/local-first when storage is needed. Do not use Kotlin, Compose, DataBinding, or ViewBinding. Avoid complex third-party dependencies. " +
                "Return only compact JSON and no markdown. The JSON object must contain appName, packageName, description, and files. " +
                "files must be an array of objects with path and content. Paths must be relative POSIX paths. " +
                "The file list must include settings.gradle, build.gradle, app/build.gradle, app/src/main/AndroidManifest.xml, all Java source files, XML layouts, and resources needed to compile. " +
                "Keep each source file focused and small, ideally under about 250 lines; split large screens into separate Adapter, Helper, Dialog, or model classes instead of one giant file. " +
                "Use Gradle plugin com.android.application 8.7.3, compileSdk 34, minSdk 24, targetSdk 34, and Java 8-compatible source/target. Do not use Java records, switch expressions, var, lambda arrow syntax, streams-heavy code, org.jetbrains.kotlin.android, kotlinOptions, Kotlin Gradle DSL, or any .kt file. Use anonymous listener classes instead of lambdas, and avoid arrow-style examples in comments/Javadocs/strings. " +
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
        return tasksSystemPromptText(chinese);
    }

    static String tasksSystemPromptForTest(boolean chinese) {
        return tasksSystemPromptText(chinese);
    }

    private static String tasksSystemPromptText(boolean chinese) {
        String language = chinese ? "Use Simplified Chinese for task titles." : "Use English for task titles.";
        return "You split an approved Android engineering plan into a short sequential implementation task list. " +
                "Return only compact JSON with a tasks array. Each task must have title and instruction. Escape double quotes inside JSON string values, for example use \\\"...\\\" for Gradle/XML snippets, or use single quotes in prose. " +
                "Each task may also include Hermes task contract fields: allowedPaths, expectedFiles, forbiddenPaths, acceptanceChecks, riskNotes, dependsOn, produces, rollbackScope, riskLevel, and buildRequiredAfter. Use arrays for list fields and boolean for buildRequiredAfter. " +
                "Use dependsOn and produces to expose a safe execution graph. Use allowedPaths and forbiddenPaths precisely so Hermes can decide safe parallel batches. Tasks may run in safe parallel only when dependencies are satisfied and allowed paths do not overlap; do not claim broad tasks are parallel-safe. " +
                "Use 3 to 6 tasks. Keep each task as a cohesive coarse phase rather than many tiny file-write tasks. " +
                "The first task should create or update the Gradle project skeleton when needed, and later tasks should add data, screens, interactions, and polish. " +
                "When a new project skeleton is needed, the first task must create Gradle files, app/src/main/AndroidManifest.xml, and base values/themes resources before Java wiring. " +
                "Keep Gradle/build configuration in its own task when it changes. Group related values, themes, drawables, menu XML, and layout XML when they support the same screen or navigation shell. " +
                "A later layout/drawable task may also add missing app/src/main/res/values entries it references, such as strings, colors, dimens, or styles. " +
                "Do not split values, themes, drawables, menu, layout, and Java wiring into separate tasks unless the plan is unusually large or a previous validation failure requires a narrower retry. " +
                "Do not combine Gradle configuration with Java source wiring in the same task. " +
                "If the approved plan requires a version upgrade, include an implementation task that updates app/build.gradle versionCode and versionName according to that plan. " +
                "Do not include build or install tasks. " + language;
    }

    /** Appends the graphics restriction to the task instruction (the user prompt) for restricted models;
     *  a weak model follows the concrete task instruction more reliably than the system prompt. */
    private String instructionWithGraphicsPolicy(String taskInstruction, boolean chinese) {
        return (taskInstruction == null ? "" : taskInstruction)
                + graphicsRestrictionClause(currentProvider(), currentModel(), chinese);
    }

    private String taskOperationsSystemPrompt(boolean chinese) {
        return taskOperationsSystemPromptText(chinese, dependencyPolicyPrompt())
                + graphicsRestrictionClause(currentProvider(), currentModel(), chinese);
    }

    private String taskManifestSystemPrompt(boolean chinese) {
        return taskManifestSystemPromptText(chinese)
                + graphicsRestrictionClause(currentProvider(), currentModel(), chinese);
    }

    static String taskManifestSystemPromptForTest(boolean chinese) {
        return taskManifestSystemPromptText(chinese);
    }

    static String taskOperationsSystemPromptForTest(boolean chinese) {
        return taskOperationsSystemPromptText(chinese, "Dependency mode is offline safe.");
    }

    /**
     * When the model is not graphics-capable, forbid blind hand-authored vector drawables and steer to
     * simple, reliable, low-token alternatives. Empty for allow-listed multimodal models.
     */
    static String graphicsRestrictionClause(String provider, String model, boolean chinese) {
        if (ModelGraphicsCapabilityPolicy.supportsGraphicsGeneration(provider, model)) {
            return "";
        }
        if (chinese) {
            return " 本模型为纯文本模型（非多模态），无法预览渲染结果：禁止手写 <vector> 矢量图（android:pathData）——盲画既不准又浪费输出。"
                    + "即使任务说明提到 vector icon，也改用以下方式（按优先级）：(1) 系统内置 drawable，如 @android:drawable/ic_menu_add、ic_input_add、ic_menu_search 等；"
                    + "(2) 简单 <shape> drawable（rectangle/oval + solid/gradient 颜色 + corners）用于背景、分隔线、按钮/卡片底；(3) 实在需要图标处用纯色 <shape> 占位。"
                    + "绝不输出复杂 android:pathData，每个 drawable 控制在数行内。";
        }
        return " This is a text-only (non-multimodal) model that cannot preview rendered output: do NOT hand-author <vector> drawables (android:pathData) — drawing blind is both wrong and wasteful. "
                + "Even if a task says \"vector icon\", use instead, in order: (1) built-in framework drawables like @android:drawable/ic_menu_add, ic_input_add, ic_menu_search; "
                + "(2) simple <shape> drawables (rectangle/oval, solid/gradient color, corners) for backgrounds, dividers, button/card surfaces; (3) a plain colored <shape> placeholder where a decorative icon would go. "
                + "Never emit complex android:pathData, and keep each drawable a few lines.";
    }

    static String taskOperationsUserPromptForTest(String plan, String taskTitle, String taskInstruction, String sourceSnapshot, String recentRequirements, String retryContext) {
        return taskOperationsUserPrompt(plan, taskTitle, taskInstruction, sourceSnapshot, recentRequirements, retryContext, "");
    }

    static String taskOperationsUserPromptForTest(String plan, String taskTitle, String taskInstruction, String sourceSnapshot, String recentRequirements, String retryContext, String previousDraftSection) {
        return taskOperationsUserPrompt(plan, taskTitle, taskInstruction, sourceSnapshot, recentRequirements, retryContext, previousDraftSection);
    }

    static String taskOperationsBatchUserPromptForTest(String plan, String taskTitle, String taskInstruction, String sourceSnapshot, String recentRequirements, String retryContext, List<TaskManifest.Entry> batchFiles, String completedFilesContext) {
        return taskOperationsBatchUserPrompt(plan, taskTitle, taskInstruction, sourceSnapshot, recentRequirements, retryContext, batchFiles, completedFilesContext);
    }

    private static String taskOperationsUserPrompt(String plan, String taskTitle, String taskInstruction, String sourceSnapshot, String recentRequirements, String retryContext, String previousDraftSection) {
        String requirementsSection = recentRequirements == null || recentRequirements.trim().isEmpty()
                ? ""
                : "\n\nRecent user requirements and clarifications (honor these even if the plan omits them):\n" + recentRequirements.trim();
        String retrySection = retryContext == null || retryContext.trim().isEmpty()
                ? ""
                : "\n\nAdditional retry/repair context:\n" + retryContext.trim();
        String draftSection = previousDraftSection == null || previousDraftSection.trim().isEmpty()
                ? ""
                : "\n\n" + previousDraftSection.trim()
                + "\n\nYou are CORRECTING your previous draft for this task, not rewriting it. Return only operations for files you are changing or adding; every other operation from your previous draft is preserved automatically and must NOT be resent. To remove a file from your previous draft that should not be written at all, return {\"action\":\"drop\",\"path\":\"...\"} for it. Keep the same JSON contract (summary + operations), unless missing prerequisites make the task unsafe within the current boundary; in that case return blocked=true with blockedReason and prerequisiteWork.";
        String cleanInstruction = HermesTaskContractCodec.stripFromInstruction(taskInstruction);
        String contractContext = HermesTaskContractCodec.promptContextFromInstruction(taskInstruction);
        String contractSection = contractContext.isEmpty()
                ? ""
                : "\n\n" + contractContext;
        return "Approved engineering plan:\n\n" + PlanContentSanitizer.clean(plan)
                + requirementsSection
                + retrySection
                + draftSection
                + "\n\nCurrent source tree:\n" + sourceSnapshot
                + "\n\nExecute exactly this task:\nTitle: " + taskTitle
                + "\nInstruction: " + cleanInstruction
                + contractSection;
    }

    private static String taskOperationsBatchUserPrompt(String plan, String taskTitle, String taskInstruction, String sourceSnapshot, String recentRequirements, String retryContext, List<TaskManifest.Entry> batchFiles, String completedFilesContext) {
        String base = taskOperationsUserPrompt(plan, taskTitle, taskInstruction, sourceSnapshot, recentRequirements, retryContext, "");
        String completed = completedFilesContext == null || completedFilesContext.trim().isEmpty()
                ? "(none)"
                : completedFilesContext.trim();
        return base
                + "\n\nGenerate complete file operations for exactly these files:\n"
                + batchFileList(batchFiles)
                + "\n\nAUTHORITATIVE API CONTRACT - the files below are already accepted and frozen. "
                + "Call their classes using the EXACT method names, parameter types, constructor signatures, "
                + "and field/constant names shown; do not re-guess or rename them. If you need a method or field "
                + "that does not exist on a frozen class, you must conform to what exists, not invent a new member:\n"
                + completed
                + "\n\nDo not include any unrequested file. Return only summary and operations JSON for this batch.";
    }

    private static String batchFileList(List<TaskManifest.Entry> files) {
        if (files == null || files.isEmpty()) {
            return "(empty)";
        }
        StringBuilder builder = new StringBuilder();
        for (TaskManifest.Entry file : files) {
            if (file == null) {
                continue;
            }
            builder.append("- ")
                    .append(file.action == null ? "" : file.action)
                    .append(' ')
                    .append(file.path == null ? "" : file.path);
            if (file.intent != null && !file.intent.trim().isEmpty()) {
                builder.append(": ").append(file.intent.trim());
            }
            builder.append('\n');
        }
        return builder.toString().trim();
    }

    private static String taskOperationsSystemPromptText(boolean chinese, String dependencyPolicyPrompt) {
        String language = chinese ? "Use Simplified Chinese for user-facing app text when appropriate." : "Use English for user-facing app text.";
        return "You execute one small Android coding task by returning file operations only. " +
                "Return only compact JSON with summary and operations. operations is an array of objects with action, path, and content. " +
                "Supported actions are write, edit, and delete. Use write for full-file replacement (content). Use edit for a small precise change to a file that already exists: provide find and replace strings instead of content, where find is a snippet that occurs EXACTLY ONCE in the current file (include enough surrounding context to be unique); the snippet is replaced verbatim. If you are unsure the find snippet is unique or present, resend the whole file with write. Use relative POSIX paths only. Simple tasks should still prefer one or two file operations; a resource or layout phase may return a small cohesive batch when those files must be written together. " +
                "Keep each source file focused and small, ideally under about 250 lines; split large screens into separate Adapter, Helper, Dialog, or model classes instead of one giant file, so each write replaces a small file. " +
                "Do not return an empty operations array; every task response must include at least one write or delete operation that advances the task. If a missing prerequisite file or resource makes the task unsafe to complete within its current boundary, return a compact blocked response instead of guessing or writing unrelated files: {\"summary\":\"...\",\"blocked\":true,\"blockedReason\":\"...\",\"prerequisiteWork\":\"...\"}. " +
                "Do not return markdown, comments outside JSON, explanations, build logs, or base64. " +
                "Keep the generated source buildable with Android Gradle Plugin 8.7.3, compileSdk 34, minSdk 24, targetSdk 34, and Java 8-compatible source/target. " +
                "Use Java + XML only. Do not write Kotlin, .kt files, kotlinOptions, Kotlin Gradle plugins, DataBinding, ViewBinding, Compose, Java lambdas, or arrow syntax. Use anonymous listener classes instead of lambdas, and do not include arrow-style examples in comments/Javadocs/strings. Prefer org.json over Gson unless a Gson dependency is already declared and allowed. " + dependencyPolicyPrompt + " " +
                "When writing Java files, keep package names consistent with Gradle namespace. Set android.namespace in app/build.gradle and do not set package=\"...\" in AndroidManifest.xml. " +
                VersionUpgradePolicy.prompt() + " " +
                dependencyProvidedResourcePolicyPrompt() + " " +
                databaseContractPolicyPrompt() + " " +
                "Before returning operations, cross-check Java API consistency across all touched files: every method call must have a matching declaration, every constructor call must match an existing constructor, and every directly accessed DTO/model field such as item.total must actually be declared and visible in that type or replaced with a getter/setter. Update Activity, Adapter, DAO, helper, and model files together when their APIs interact. " +
                "For aggregate/statistics DTOs used by adapters, do not access item.categoryName, item.percent, or similar display fields unless that exact field exists in the DTO; otherwise add fields/getters or update adapter binding to existing fields. For DAO/helper wiring, pass the exact constructor type, e.g. do not call new CategoryDAO(dbHelper) when CategoryDAO(Context) is declared; use context or change the constructor and all callers consistently. " +
                "Declare every view variable with findViewById from the Activity, inflated root view, or dialog view before using it; never use bare view ids such as fabAdd.setOnClickListener or textIncomeAmount.setText. " +
                "Every R.id.* referenced by Java must exist as android:id=\"@+id/...\" in XML, every R.* resource used in code must exist, and every XML reference such as @mipmap/ic_launcher, @style/AppTheme, @drawable/name, @string/name, @color/name, or @layout/name must have a matching resource file or values entry. " +
                "When the current source tree includes a resource index, treat it as the only authoritative resource truth table. Every R.id/R.layout/R.string/R.color/R.drawable/R.mipmap/R.style reference in Java must appear verbatim in that resource index. Never invent a new resource name: if the exact name you want is not in the index, bind to the nearest existing indexed name (for example use an indexed bg_color_dot rather than a made-up bg_welcome_dot), or, if no suitable resource exists, return blocked with prerequisiteWork naming it. Conversely, every name listed here EXISTS - you may reference it from Java without seeing the XML body. " +
                "The snapshot inventory (full text + API digest + coverage note) is complete: a Java file absent from all of them does not exist yet, and creating it is part of your task when needed. " + language;
    }

    private static String taskManifestSystemPromptText(boolean chinese) {
        String language = chinese ? "Use Simplified Chinese for summary and intent." : "Use English for summary and intent.";
        return "You plan one Android coding task by returning a file manifest only. "
                + "Return only compact JSON with summary, blocked, blockedReason, prerequisiteWork, and files. "
                + "files is an array of objects with path, action, and intent. action must be write or delete. "
                + "List every file this task will write or delete, using canonical relative POSIX paths such as app/src/main/res/values/strings.xml, app/src/main/java/..., app/src/main/AndroidManifest.xml, and app/build.gradle. "
                + "Do not include file content in the manifest. intent must be one concise sentence describing the purpose and key API choice for that file. "
                + "If a missing prerequisite makes the task unsafe within its current boundary, return blocked=true with blockedReason and prerequisiteWork instead of guessing. "
                + "Keep the manifest focused and within " + com.androidbuilder.model.TaskManifest.MAX_FILES + " files. " + language;
    }

    private String contextNegotiationSystemPrompt(boolean chinese) {
        return contextNegotiationSystemPromptText(chinese);
    }

    static String contextNegotiationSystemPromptForTest(boolean chinese) {
        return contextNegotiationSystemPromptText(chinese);
    }

    private static String contextNegotiationSystemPromptText(boolean chinese) {
        String language = chinese ? "Use Simplified Chinese for notes." : "Use English for notes.";
        return "You are Hermes Context Scout, the context negotiation step before Android code generation. "
                + "Do not write code and do not return file operations. "
                + "Decide whether the supplied source snapshot is enough for the next small patch. "
                + "Return only compact JSON with keys ready, neededFiles, focusTerms, riskNotes, and patchIntent. "
                + "neededFiles must contain relative POSIX paths only. patchIntent must be concise and must tell the coding model to modify existing files, not recreate the project. "
                + "Request a file in neededFiles only if it plausibly already exists. The snapshot inventory is complete - a Java file absent from full text and the API digest does not exist. Never set ready=false solely because files that do not exist yet are missing; state in patchIntent that you will create them. "
                + language;
    }

    static String contextNegotiationUserPromptForTest(String plan, String taskTitle, String taskInstruction, String sourceSnapshot, String recentRequirements, String previousFailure, boolean chinese) {
        return contextNegotiationUserPrompt(plan, taskTitle, taskInstruction, sourceSnapshot, recentRequirements, previousFailure, chinese);
    }

    private static String contextNegotiationUserPrompt(String plan, String taskTitle, String taskInstruction, String sourceSnapshot, String recentRequirements, String previousFailure, boolean chinese) {
        String requirements = recentRequirements == null || recentRequirements.trim().isEmpty()
                ? "(none)"
                : recentRequirements.trim();
        String failure = previousFailure == null || previousFailure.trim().isEmpty()
                ? "(none)"
                : truncatePrompt(previousFailure.trim(), 3000);
        return "Approved engineering plan:\n" + plan
                + "\n\nTask title:\n" + taskTitle
                + "\n\nTask instruction:\n" + taskInstruction
                + "\n\nRecent user requirements:\n" + requirements
                + "\n\nPrevious failure summary:\n" + failure
                + "\n\nCurrent source snapshot:\n" + truncatePrompt(sourceSnapshot, 12000)
                + "\n\nReturn JSON only. If more context is needed, name the exact source files or symbols. The patchIntent must say how to patch the existing source without recreating the project.";
    }

    private String hermesReviewSystemPrompt(boolean chinese) {
        return hermesReviewSystemPromptText(chinese);
    }

    static String hermesReviewSystemPromptForTest(boolean chinese) {
        return hermesReviewSystemPromptText(chinese);
    }

    private static String hermesReviewSystemPromptText(boolean chinese) {
        String language = chinese ? "Use Simplified Chinese for summary and rewriteInstruction." : "Use English for summary and rewriteInstruction.";
        return "You are HermesReviewer, a pre-apply reviewer for Android file operations. "
                + "Review the generated operations against the task, source snapshot, and Context Scout notes. "
                + "Return only compact JSON with keys decision, summary, and rewriteInstruction. "
                + "decision must be one of ok, rewrite, or fallback. "
                + "Use ok when the patch is focused and cross-file Java APIs look consistent. "
                + "Use rewrite ONLY when the patch is too broad or recreates the whole project, or when there is an obvious cross-file Java API mismatch - a call to a method, constructor, or field that no class in scope declares. "
                + "Do NOT flag missing Android resources: @drawable/@color/@dimen/@string/@style/@menu/@id/@layout references or R.* references are verified by the build's resource linker (aapt), and a referenced resource may legitimately be provided by another task or by a library, so never request creating, inlining, or 'declaring all referenced resources'. "
                + "Use fallback only if the input is insufficient to review. Do not return markdown. " + language;
    }

    static String hermesReviewUserPromptForTest(String taskTitle, String taskInstruction, String sourceSnapshot, String operationsJson, String contextScoutNotes) {
        return hermesReviewUserPrompt(taskTitle, taskInstruction, sourceSnapshot, operationsJson, contextScoutNotes);
    }

    private static String hermesReviewUserPrompt(String taskTitle, String taskInstruction, String sourceSnapshot, String operationsJson, String contextScoutNotes) {
        String notes = contextScoutNotes == null || contextScoutNotes.trim().isEmpty()
                ? "(none)"
                : contextScoutNotes.trim();
        return "Task title:\n" + taskTitle
                + "\n\nTask instruction:\n" + taskInstruction
                + "\n\nContext Scout notes:\n" + truncatePrompt(notes, 3000)
                + "\n\nCurrent source snapshot:\n" + truncatePrompt(sourceSnapshot, 12000)
                + "\n\nGenerated operations JSON:\n" + truncatePrompt(operationsJson, 12000)
                + "\n\nReturn JSON only. If decision is rewrite, rewriteInstruction must be concrete and scoped to one retry.";
    }

    private String policyRewriteSystemPrompt(boolean chinese) {
        return policyRewriteSystemPromptText(chinese);
    }

    static String policyRewriteSystemPromptForTest(boolean chinese) {
        return policyRewriteSystemPromptText(chinese);
    }

    private static String policyRewriteSystemPromptText(boolean chinese) {
        String language = chinese ? "Use Simplified Chinese." : "Use English.";
        return "You are a cloud Android source guard reviewer. Produce one concise retry hint for the next coding attempt. "
                + "Focus only on the deterministic policy error and the source digest. Preserve the exact blocked file and symbol. "
                + "For missing XML resources, say which values or resource XML file to add, or which caller to change to an existing resource. "
                + "For missing methods/fields, say which API to add or which caller to update. For Java lambdas, require anonymous inner classes and no arrow tokens. "
                + "Do not bypass AndroidSourceGuard, do not suggest weakening validation, and do not return markdown. "
                + "Return plain text only, one or two short sentences. " + language;
    }

    static String policyRewriteUserPromptForTest(String taskInstruction, String policyError, String focusedSnapshot, int attempt) {
        return policyRewriteUserPrompt(taskInstruction, policyError, focusedSnapshot, attempt);
    }

    private static String policyRewriteUserPrompt(String taskInstruction, String policyError, String focusedSnapshot, int attempt) {
        return "Retry attempt " + attempt
                + "\n\nOriginal task instruction:\n" + truncatePrompt(taskInstruction, 1800)
                + "\n\nDeterministic policy error:\n" + truncatePrompt(policyError, 1200)
                + "\n\nSource API/resource digest:\n" + truncatePrompt(focusedSnapshot, 4000)
                + "\n\nReturn the smallest useful retry hint. Do not bypass validation.";
    }

    private String buildFailureTriageSystemPrompt(boolean chinese) {
        return buildFailureTriageSystemPromptText(chinese);
    }

    static String buildFailureTriageSystemPromptForTest(boolean chinese) {
        return buildFailureTriageSystemPromptText(chinese);
    }

    private static String buildFailureTriageSystemPromptText(boolean chinese) {
        String language = chinese ? "Use Simplified Chinese." : "Use English.";
        return "You are a cloud Android build-log triage reviewer. Produce one focused repair hint for the coding model. "
                + "Identify the root source error from the Gradle/AAPT/javac log, ignore noisy stack frames, and name the exact file/resource/symbol to add or change. "
                + "Prefer Java + XML + Android SDK fixes. Do not suggest Kotlin, Compose, DataBinding, ViewBinding, new dependencies, or validation bypasses. "
                + "Return plain text only, one or two short sentences. " + language;
    }

    static String buildFailureTriageUserPromptForTest(String buildLog, String focusedSnapshot) {
        return buildFailureTriageUserPrompt(buildLog, focusedSnapshot);
    }

    private static String buildFailureTriageUserPrompt(String buildLog, String focusedSnapshot) {
        return "Build log tail:\n" + tailPrompt(buildLog, 5000)
                + "\n\nSource API/resource digest:\n" + truncatePrompt(focusedSnapshot, 4000)
                + "\n\nReturn the smallest focused repair hint.";
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
        // The planner must know the real dependency capability, otherwise plans bake in libraries
        // the guard will reject and every task fights the policy (e.g. MPAndroidChart loops).
        return planSystemPromptText(chinese) + " " + planDependencyCapabilityPrompt();
    }

    private String planDependencyCapabilityPrompt() {
        String mode = BuildBackendSettings.dependencyMode(context);
        if (BuildBackendSettings.DEPENDENCY_ONLINE.equals(mode)) {
            return "Dependency capability for this plan (supersedes the generic no-third-party rule): " +
                    DependencyCatalog.promptSummary() +
                    " Plan features only around these libraries, the trusted androidx/material groups, or the Android SDK.";
        }
        return "Dependency capability for this plan: builds run offline, so plan only Android SDK/Java/XML/SQLite features. Do not promise third-party chart, image, animation, or network libraries.";
    }

    static String planSystemPromptForTest(boolean chinese) {
        return planSystemPromptText(chinese);
    }

    private static String planSystemPromptText(boolean chinese) {
        if (chinese) {
            return "你是安卓工程 Agent 的规划阶段。只输出工程计划，不写源码，不返回 JSON。内置约束：先澄清目标并拆解计划，不直接编码；目标 App 必须兼容 Android 7.0+、Java + XML、SQLite、本地优先；不使用 Kotlin、Compose、DataBinding、ViewBinding，不引入复杂第三方依赖；每次改动必须保持现有项目上下文、源码隔离、可构建。版本升级规则：若需求涉及 App 版本、发布版本、构建号、升级、发版、测试版或 APK 迭代，计划必须明确要求同步升级 app/build.gradle 里的 versionCode 和 versionName：versionCode 必须大于当前值，versionName 使用需求指定版本；未指定时递增补丁号；禁止降级。输出必须以“# 工程计划”开头，并包含这些小节：需求理解、页面/功能、数据结构、源码改动点、工程约束、测试清单、验收标准、构建风险。计划要具体到可以交给编码阶段执行。";
        }
        return "You are in the planning phase for an Android engineering agent. Output an engineering plan only: no source code and no JSON. Built-in constraints: clarify the goal and break down the plan before coding; the target app must be Android 7.0+ compatible, Java + XML, SQLite, local-first; do not use Kotlin, Compose, DataBinding, ViewBinding, or complex third-party dependencies; preserve project context, source isolation, and buildability on every change. " + VersionUpgradePolicy.prompt() + " The response must start with '# Engineering Plan' and include: Requirement Understanding, Screens/Features, Data Structure, Source Changes, Engineering Constraints, Test Checklist, Acceptance Criteria, Build Risks. Make it concrete enough for the coding phase to execute.";
    }

    private static JSONObject message(String role, String content) throws Exception {
        JSONObject message = new JSONObject();
        message.put("role", role);
        message.put("content", content == null ? "" : content);
        return message;
    }
}
