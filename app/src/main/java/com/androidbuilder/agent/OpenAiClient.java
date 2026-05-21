package com.androidbuilder.agent;

import android.content.Context;
import android.content.SharedPreferences;

import com.androidbuilder.model.ChatMessage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class OpenAiClient {
    public static final String PREFS = "cloud_api";
    public static final String KEY_API_KEY = "api_key";
    public static final String KEY_ENDPOINT = "endpoint";
    public static final String KEY_MODEL = "model";
    public static final String KEY_PROVIDER = "provider";
    public static final String PROVIDER_OPENAI = "openai";
    public static final String PROVIDER_DEEPSEEK = "deepseek";
    public static final String PROVIDER_CUSTOM = "custom";

    private final SharedPreferences prefs;

    public OpenAiClient(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean isConfigured() {
        return !prefs.getString(KEY_API_KEY, "").trim().isEmpty();
    }

    public String createSpecJson(List<ChatMessage> messages, String latestPrompt, boolean chinese) throws Exception {
        if (!isConfigured()) {
            return null;
        }
        String provider = prefs.getString(KEY_PROVIDER, PROVIDER_OPENAI);
        String endpoint = prefs.getString(KEY_ENDPOINT, defaultEndpoint(provider));
        String model = prefs.getString(KEY_MODEL, defaultModel(provider));
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("temperature", 0.2);
        JSONArray chat = new JSONArray();
        chat.put(message("system", systemPrompt(chinese)));
        for (ChatMessage message : messages) {
            if ("user".equals(message.role) || "assistant".equals(message.role)) {
                chat.put(message(message.role, message.content));
            }
        }
        chat.put(message("user", "Latest request: " + latestPrompt));
        body.put("messages", chat);

        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(60000);
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

    public static String defaultEndpoint(String provider) {
        if (PROVIDER_DEEPSEEK.equals(provider)) {
            return "https://api.deepseek.com/chat/completions";
        }
        return "https://api.openai.com/v1/chat/completions";
    }

    public static String defaultModel(String provider) {
        if (PROVIDER_DEEPSEEK.equals(provider)) {
            return "deepseek-v4-flash";
        }
        return "gpt-4.1-mini";
    }

    private String systemPrompt(boolean chinese) {
        String language = chinese ? "Use Simplified Chinese for app names, field labels and user-facing text." : "Use English for app names, field labels and user-facing text.";
        return "You design small native Android CRUD apps. Return only compact JSON with keys appName, packageName, description, entityName, primaryField, secondaryField. Use ASCII package names. " + language;
    }

    private JSONObject message(String role, String content) throws Exception {
        JSONObject message = new JSONObject();
        message.put("role", role);
        message.put("content", content == null ? "" : content);
        return message;
    }
}
