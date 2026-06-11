package com.androidbuilder.agent;

import android.content.SharedPreferences;

import com.androidbuilder.model.ChatMessage;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OpenAiClientTest {
    @Test
    public void legacyApiKeyFallsBackOnlyForSavedProvider() {
        FakeSharedPreferences prefs = new FakeSharedPreferences()
                .put(OpenAiClient.KEY_PROVIDER, OpenAiClient.PROVIDER_OPENAI)
                .put(OpenAiClient.KEY_API_KEY, "openai-key");

        assertEquals("openai-key", OpenAiClient.apiKeyForProvider(prefs, OpenAiClient.PROVIDER_OPENAI));
        assertEquals("", OpenAiClient.apiKeyForProvider(prefs, OpenAiClient.PROVIDER_DEEPSEEK));
    }

    @Test
    public void providerScopedConfigOverridesLegacyValues() {
        FakeSharedPreferences prefs = new FakeSharedPreferences()
                .put(OpenAiClient.KEY_PROVIDER, OpenAiClient.PROVIDER_OPENAI)
                .put(OpenAiClient.KEY_API_KEY, "openai-key")
                .put(OpenAiClient.scopedKey(OpenAiClient.KEY_API_KEY, OpenAiClient.PROVIDER_DEEPSEEK), "deepseek-key")
                .put(OpenAiClient.scopedKey(OpenAiClient.KEY_ENDPOINT, OpenAiClient.PROVIDER_DEEPSEEK), "https://deepseek.example/chat")
                .put(OpenAiClient.scopedKey(OpenAiClient.KEY_MODEL, OpenAiClient.PROVIDER_DEEPSEEK), OpenAiClient.DEEPSEEK_MODEL_PRO);

        assertEquals("deepseek-key", OpenAiClient.apiKeyForProvider(prefs, OpenAiClient.PROVIDER_DEEPSEEK));
        assertEquals("https://deepseek.example/chat", OpenAiClient.endpointForProvider(prefs, OpenAiClient.PROVIDER_DEEPSEEK));
        assertEquals(OpenAiClient.DEEPSEEK_MODEL_PRO, OpenAiClient.modelForProvider(prefs, OpenAiClient.PROVIDER_DEEPSEEK));
    }

    @Test
    public void apiKeyForProviderStripsOptionalBearerPrefix() {
        FakeSharedPreferences prefs = new FakeSharedPreferences()
                .put(OpenAiClient.scopedKey(OpenAiClient.KEY_API_KEY, OpenAiClient.PROVIDER_MINIMAX), "Bearer sk-cp-demo");

        assertEquals("sk-cp-demo", OpenAiClient.apiKeyForProvider(prefs, OpenAiClient.PROVIDER_MINIMAX));
    }

    @Test
    public void apiKeyForProviderExtractsCommonPastedMiniMaxKeyFormats() {
        assertEquals("sk-cp-demo", OpenAiClient.normalizedApiKey("Authorization: Bearer sk-cp-demo"));
        assertEquals("sk-cp-demo", OpenAiClient.normalizedApiKey("--header 'Authorization: Bearer sk-cp-demo'"));
        assertEquals("sk-cp-demo", OpenAiClient.normalizedApiKey("OPENAI_API_KEY=sk-cp-demo"));
        assertEquals("sk-cp-demo", OpenAiClient.normalizedApiKey("ANTHROPIC_API_KEY=\"sk-cp-demo\""));
    }

    @Test
    public void customProviderDefaultsToEmptyConfig() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();

        assertEquals("", OpenAiClient.defaultEndpoint(OpenAiClient.PROVIDER_CUSTOM));
        assertEquals("", OpenAiClient.defaultModel(OpenAiClient.PROVIDER_CUSTOM));
        assertEquals("", OpenAiClient.apiKeyForProvider(prefs, OpenAiClient.PROVIDER_CUSTOM));
        assertEquals("", OpenAiClient.endpointForProvider(prefs, OpenAiClient.PROVIDER_CUSTOM));
        assertEquals("", OpenAiClient.modelForProvider(prefs, OpenAiClient.PROVIDER_CUSTOM));
    }

    @Test
    public void exposesOpenAiModelsForDropdownSelection() {
        java.util.List<String> models = java.util.Arrays.asList(OpenAiClient.openAiModels());

        assertEquals(OpenAiClient.OPENAI_MODEL_GPT_54_MINI,
                OpenAiClient.defaultModel(OpenAiClient.PROVIDER_OPENAI));
        assertTrue(models.contains(OpenAiClient.OPENAI_MODEL_GPT_55));
        assertTrue(models.contains(OpenAiClient.OPENAI_MODEL_GPT_54_MINI));
        assertFalse(models.contains("gpt-4.1"));
        assertFalse(models.contains("gpt-4.1-mini"));
        assertFalse(models.contains("gpt-4o"));
        for (String model : models) {
            assertTrue(model, model.startsWith("gpt-5"));
        }
        assertFalse(models.contains("custom-model"));
    }

    @Test
    public void openAiReasoningModelsUseDefaultSamplingParameters() throws Exception {
        JSONObject body = OpenAiClient.chatRequestBodyForTest(
                OpenAiClient.PROVIDER_OPENAI,
                OpenAiClient.OPENAI_MODEL_GPT_55,
                "system",
                Collections.emptyList(),
                "latest",
                0.2,
                true);

        assertEquals(OpenAiClient.OPENAI_MODEL_GPT_55, body.getString("model"));
        assertFalse(body.has("temperature"));
    }

    @Test
    public void nonOpenAiModelsKeepConfiguredTemperature() throws Exception {
        JSONObject body = OpenAiClient.chatRequestBodyForTest(
                OpenAiClient.PROVIDER_DEEPSEEK,
                OpenAiClient.DEEPSEEK_MODEL_FLASH,
                "system",
                Collections.emptyList(),
                "latest",
                0.2,
                true);

        assertEquals(0.2, body.getDouble("temperature"), 0.0);
    }

    @Test
    public void modelReadTimeoutsUseTenMinutes() {
        assertEquals(600000, OpenAiClient.defaultReadTimeoutMsForTest());
        assertEquals(600000, OpenAiClient.tasksReadTimeoutMsForTest());
        assertEquals(600000, OpenAiClient.codingReadTimeoutMsForTest());
    }

    @Test
    public void socketAbortRetriesAllowMultipleTransientInterruptions() {
        assertTrue(OpenAiClient.socketAbortRetriesForTest() >= 2);
    }

    @Test
    public void cloudPolicyRewritePromptPreservesExactPolicyError() {
        String prompt = OpenAiClient.policyRewriteUserPromptForTest(
                "Add add screen",
                "Generated source policy blocked missing XML resource reference: @color/primary in styles.xml.",
                "--- app/src/main/res/values/styles.xml ---\n<item name=\"colorPrimary\">@color/primary</item>",
                3);

        assertTrue(OpenAiClient.policyRewriteSystemPromptForTest(false).contains("concise retry hint"));
        assertTrue(prompt.contains("@color/primary"));
        assertTrue(prompt.contains("styles.xml"));
        assertTrue(prompt.contains("attempt 3"));
        assertTrue(prompt.contains("Do not bypass"));
    }

    @Test
    public void cloudBuildTriagePromptAsksForFocusedRepairHint() {
        String prompt = OpenAiClient.buildFailureTriageUserPromptForTest(
                "AAPT: error: resource color/primary not found.",
                "--- app/src/main/res/values/styles.xml ---\n<item name=\"colorPrimary\">@color/primary</item>");

        assertTrue(OpenAiClient.buildFailureTriageSystemPromptForTest(false).contains("focused repair hint"));
        assertTrue(prompt.contains("resource color/primary not found"));
        assertTrue(prompt.contains("Source API/resource digest"));
    }

    @Test
    public void contextNegotiationPromptRequestsStructuredJson() {
        String system = OpenAiClient.contextNegotiationSystemPromptForTest(false);
        String user = OpenAiClient.contextNegotiationUserPromptForTest(
                "# Engineering Plan\nUpdate records",
                "Fix DAO mismatch",
                "Make RecordDao constructor match callers",
                "--- app/src/main/java/com/example/RecordDao.java ---\nclass RecordDao {}",
                "- Keep CSV export",
                "Generated source policy blocked constructor argument mismatch.",
                false);

        assertTrue(system.contains("Return only compact JSON"));
        assertTrue(system.contains("neededFiles"));
        assertTrue(system.contains("patchIntent"));
        assertTrue(user.contains("Previous failure summary"));
        assertTrue(user.contains("Fix DAO mismatch"));
        assertTrue(user.contains("Current source snapshot"));
    }

    @Test
    public void taskOperationsUserPromptIncludesRetryContext() {
        String prompt = OpenAiClient.taskOperationsUserPromptForTest(
                "# Engineering Plan\nUpdate DAO",
                "Fix DAO",
                "Synchronize constructor",
                "--- app/src/main/java/com/example/RecordDao.java ---\nclass RecordDao {}",
                "- Keep export screen",
                "This is a retry or repair of an existing source tree.\nDo not recreate the project.");

        assertTrue(prompt.contains("Recent user requirements and clarifications"));
        assertTrue(prompt.contains("Additional retry/repair context"));
        assertTrue(prompt.contains("Do not recreate the project"));
        assertTrue(prompt.contains("Execute exactly this task"));
    }

    @Test
    public void taskOperationsUserPromptRendersHermesTaskContract() throws Exception {
        String instruction = HermesTaskContractCodec.appendToInstruction(
                "Create main layout.",
                HermesTaskContractCodec.fromJson(new org.json.JSONObject("{"
                        + "\"allowedPaths\":[\"app/src/main/res/layout/activity_main.xml\"],"
                        + "\"expectedFiles\":[\"app/src/main/res/layout/activity_main.xml\"],"
                        + "\"acceptanceChecks\":[\"No missing IDs\"]"
                        + "}")));

        String prompt = OpenAiClient.taskOperationsUserPromptForTest(
                "# Engineering Plan\nCreate UI",
                "Write layout",
                instruction,
                "(empty)",
                "",
                "");

        assertTrue(prompt.contains("Instruction: Create main layout."));
        assertTrue(prompt.contains("Hermes task contract"));
        assertTrue(prompt.contains("allowedPaths: app/src/main/res/layout/activity_main.xml"));
        assertTrue(prompt.contains("expectedFiles: app/src/main/res/layout/activity_main.xml"));
        assertTrue(prompt.contains("acceptanceChecks: No missing IDs"));
        assertFalse(prompt.contains(HermesTaskContractCodec.START));
    }

    @Test
    public void implementationTaskPromptKeepsImplementationTaskListCompact() {
        String prompt = OpenAiClient.tasksSystemPromptForTest(false);

        assertTrue(prompt.contains("Use 3 to 6 tasks"));
        assertTrue(prompt.contains("Group related values, themes, drawables, menu XML, and layout XML"));
        assertTrue(prompt.contains("Do not split values, themes, drawables, menu, layout, and Java wiring into separate tasks unless"));
        assertTrue(prompt.contains("Escape double quotes inside JSON string values"));
        assertTrue(prompt.contains("allowedPaths"));
        assertTrue(prompt.contains("expectedFiles"));
        assertTrue(prompt.contains("acceptanceChecks"));
        assertTrue(prompt.contains("buildRequiredAfter"));
    }

    @Test
    public void generatedProjectPromptsUseApi24Minimum() {
        String projectPrompt = OpenAiClient.projectFilesSystemPromptForTest(false);
        String taskPrompt = OpenAiClient.taskOperationsSystemPromptForTest(false);

        assertTrue(projectPrompt.contains("minSdk 24"));
        assertTrue(taskPrompt.contains("minSdk 24"));
        assertTrue(projectPrompt.contains("Android 7.0+ compatible"));
        assertTrue(OpenAiClient.planSystemPromptForTest(false).contains("Android 7.0+ compatible"));
        assertFalse(projectPrompt.contains("minSdk 31"));
        assertFalse(taskPrompt.contains("minSdk 31"));
        assertFalse(projectPrompt.contains("Android 12+"));
        assertFalse(OpenAiClient.planSystemPromptForTest(false).contains("Android 12+"));
    }

    @Test
    public void hermesReviewPromptRequestsStructuredDecision() {
        String system = OpenAiClient.hermesReviewSystemPromptForTest(false);
        String user = OpenAiClient.hermesReviewUserPromptForTest(
                "Fix DAO",
                "Synchronize DAO and caller",
                "--- app/src/main/java/com/example/RecordDao.java ---\nclass RecordDao {}",
                "{\"summary\":\"Changed DAO\",\"operations\":[]}",
                "Patch existing DAO only.");

        assertTrue(system.contains("ok"));
        assertTrue(system.contains("rewrite"));
        assertTrue(system.contains("fallback"));
        assertTrue(user.contains("Generated operations JSON"));
        assertTrue(user.contains("Context Scout notes"));
    }

    @Test
    public void normalizesDeepSeekOfficialAndOpenAiCompatibleBaseUrls() {
        assertEquals("https://api.deepseek.com/chat/completions",
                OpenAiClient.normalizedEndpoint(OpenAiClient.PROVIDER_DEEPSEEK, "https://api.deepseek.com"));
        assertEquals("https://api.deepseek.com/v1/chat/completions",
                OpenAiClient.normalizedEndpoint(OpenAiClient.PROVIDER_DEEPSEEK, "https://api.deepseek.com/v1"));
    }

    @Test
    public void identifiesDeepSeekEndpointPresets() {
        assertTrue(OpenAiClient.isDeepSeekOfficialEndpoint("https://api.deepseek.com"));
        assertTrue(OpenAiClient.isDeepSeekOfficialEndpoint("https://api.deepseek.com/chat/completions"));
        assertTrue(OpenAiClient.isDeepSeekOpenAiCompatibleEndpoint("https://api.deepseek.com/v1"));
        assertTrue(OpenAiClient.isDeepSeekOpenAiCompatibleEndpoint("https://api.deepseek.com/v1/chat/completions"));
        assertFalse(OpenAiClient.isDeepSeekOfficialEndpoint("https://proxy.example/v1"));
        assertFalse(OpenAiClient.isDeepSeekOpenAiCompatibleEndpoint("https://proxy.example/v1"));
    }

    @Test
    public void minimaxDefaultUsesOpenAiCompatibleChatCompletionsEndpoint() {
        assertEquals("https://api.minimaxi.com/v1/chat/completions",
                OpenAiClient.defaultEndpoint(OpenAiClient.PROVIDER_MINIMAX));
    }

    @Test
    public void normalizesLegacyMiniMaxEndpointToInternationalOpenAiCompatibleEndpoint() {
        assertEquals("https://api.minimax.io/v1/chat/completions",
                OpenAiClient.normalizedEndpoint(OpenAiClient.PROVIDER_MINIMAX, "https://api.minimax.io/v1/text/chatcompletion_v2"));
    }

    @Test
    public void normalizesMiniMaxBaseUrlToChatCompletionsEndpoint() {
        assertEquals("https://api.minimaxi.com/v1/chat/completions",
                OpenAiClient.normalizedEndpoint(OpenAiClient.PROVIDER_MINIMAX, "https://api.minimaxi.com/v1"));
    }

    @Test
    public void keepsMiniMaxInternationalBaseUrlOnInternationalEndpoint() {
        assertEquals("https://api.minimax.io/v1/chat/completions",
                OpenAiClient.normalizedEndpoint(OpenAiClient.PROVIDER_MINIMAX, "https://api.minimax.io/v1"));
        assertEquals("https://api.minimax.io/v1/chat/completions",
                OpenAiClient.normalizedEndpoint(OpenAiClient.PROVIDER_MINIMAX, "https://api.minimax.io/v1/chat/completions"));
    }

    @Test
    public void identifiesMiniMaxEndpointRegionPresets() {
        assertTrue(OpenAiClient.isMiniMaxChinaEndpoint("https://api.minimaxi.com/v1"));
        assertTrue(OpenAiClient.isMiniMaxChinaEndpoint("https://api.minimaxi.com/v1/chat/completions"));
        assertTrue(OpenAiClient.isMiniMaxInternationalEndpoint("https://api.minimax.io/v1"));
        assertTrue(OpenAiClient.isMiniMaxInternationalEndpoint("https://api.minimax.io/v1/chat/completions"));
        assertFalse(OpenAiClient.isMiniMaxChinaEndpoint("https://proxy.example/v1"));
        assertFalse(OpenAiClient.isMiniMaxInternationalEndpoint("https://proxy.example/v1"));
    }

    @Test
    public void minimaxSupportsCurrentOpenAiCompatibleModels() {
        assertTrue(OpenAiClient.isSupportedMiniMaxModel("MiniMax-M3"));
        assertTrue(OpenAiClient.isSupportedMiniMaxModel("MiniMax-M2.7-highspeed"));
        assertTrue(OpenAiClient.isSupportedMiniMaxModel("MiniMax-M2"));
        assertFalse(OpenAiClient.isSupportedMiniMaxModel("MiniMax-M1"));
    }

    @Test
    public void normalizesLegacyMiniMaxModelsToCurrentDefault() {
        assertEquals(OpenAiClient.MINIMAX_MODEL_M3,
                OpenAiClient.normalizedModel(OpenAiClient.PROVIDER_MINIMAX, "MiniMax-Text-01"));
    }

    @Test
    public void minimaxRequestBodyUsesReasoningSplitToKeepContentParseable() throws Exception {
        JSONObject body = OpenAiClient.chatRequestBodyForTest(
                OpenAiClient.PROVIDER_MINIMAX,
                OpenAiClient.MINIMAX_MODEL_M3,
                "system",
                Collections.singletonList(new ChatMessage(1, 1, "assistant", "previous", 0, null)),
                "latest",
                0.2,
                true);

        assertEquals("MiniMax-M3", body.getString("model"));
        assertTrue(body.getBoolean("reasoning_split"));
        assertFalse(body.has("thinking"));
        assertEquals("previous", body.getJSONArray("messages").getJSONObject(1).getString("content"));
    }

    @Test
    public void miniMaxThinkingOffSendsDisabledThinkingFlag() throws Exception {
        JSONObject body = OpenAiClient.chatRequestBodyForTest(
                OpenAiClient.PROVIDER_MINIMAX,
                OpenAiClient.MINIMAX_MODEL_M3,
                "system",
                Collections.emptyList(),
                "latest",
                0.2,
                false);

        assertTrue(body.getBoolean("reasoning_split"));
        assertEquals("disabled", body.getJSONObject("thinking").getString("type"));
    }

    @Test
    public void deepSeekThinkingOffSendsDisabledThinkingFlag() throws Exception {
        JSONObject body = OpenAiClient.chatRequestBodyForTest(
                OpenAiClient.PROVIDER_DEEPSEEK,
                OpenAiClient.DEEPSEEK_MODEL_PRO,
                "system",
                Collections.emptyList(),
                "latest",
                0.2,
                false);

        assertEquals("disabled", body.getJSONObject("thinking").getString("type"));
    }

    @Test
    public void deepSeekThinkingOnOmitsThinkingFlag() throws Exception {
        JSONObject body = OpenAiClient.chatRequestBodyForTest(
                OpenAiClient.PROVIDER_DEEPSEEK,
                OpenAiClient.DEEPSEEK_MODEL_PRO,
                "system",
                Collections.emptyList(),
                "latest",
                0.2,
                true);

        assertFalse(body.has("thinking"));
    }

    @Test
    public void onlyMiniMaxAndDeepSeekSupportThinkingToggle() {
        assertTrue(OpenAiClient.supportsThinkingToggle(OpenAiClient.PROVIDER_MINIMAX));
        assertTrue(OpenAiClient.supportsThinkingToggle(OpenAiClient.PROVIDER_DEEPSEEK));
        assertFalse(OpenAiClient.supportsThinkingToggle(OpenAiClient.PROVIDER_OPENAI));
        assertFalse(OpenAiClient.supportsThinkingToggle(OpenAiClient.PROVIDER_CUSTOM));
    }

    @Test
    public void parsesMiniMaxOpenAiCompatibleResponse() throws Exception {
        JSONObject response = new JSONObject()
                .put("choices", new org.json.JSONArray()
                        .put(new JSONObject()
                                .put("message", new JSONObject()
                                        .put("content", "{\"ok\":true}"))))
                .put("base_resp", new JSONObject()
                        .put("status_code", 0)
                        .put("status_msg", ""));

        assertEquals("{\"ok\":true}", OpenAiClient.extractChatContentForTest(response));
    }

    @Test
    public void accumulatesStreamingDeltaContentAndIgnoresReasoning() throws Exception {
        String sse = "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"thinking...\"}}]}\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"{\\\"ok\\\":\"}}]}\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"true}\"}}]}\n"
                + "data: [DONE]\n";

        assertEquals("{\"ok\":true}", OpenAiClient.readChatContentForTest(sse));
    }

    @Test
    public void fallsBackToPlainJsonWhenServerIgnoresStream() throws Exception {
        String body = "{\"choices\":[{\"message\":{\"content\":\"plain\"}}]}";

        assertEquals("plain", OpenAiClient.readChatContentForTest(body));
    }

    @Test
    public void rejectsMiniMaxBaseRespErrorsEvenWhenHttpSucceeded() throws Exception {
        JSONObject response = new JSONObject()
                .put("choices", new org.json.JSONArray())
                .put("base_resp", new JSONObject()
                        .put("status_code", 1001)
                        .put("status_msg", "invalid api key"));

        IllegalStateException error = org.junit.Assert.assertThrows(
                IllegalStateException.class,
                () -> OpenAiClient.extractChatContentForTest(response));

        assertTrue(error.getMessage().contains("MiniMax API failed"));
        assertTrue(error.getMessage().contains("invalid api key"));
    }

    @Test
    public void miniMaxUnauthorizedErrorExplainsTokenPlanKeySetup() {
        String message = OpenAiClient.httpErrorMessageForTest(
                OpenAiClient.PROVIDER_MINIMAX,
                401,
                "{\"type\":\"error\",\"error\":{\"type\":\"authorized_error\",\"message\":\"invalid api key (2049)\",\"http_code\":\"401\"}}",
                true);

        assertTrue(message.contains("Token Plan"));
        assertTrue(message.contains("sk-cp"));
        assertTrue(message.contains("https://api.minimaxi.com/v1"));
    }

    private static final class FakeSharedPreferences implements SharedPreferences {
        private final Map<String, Object> values = new HashMap<>();

        FakeSharedPreferences put(String key, String value) {
            values.put(key, value);
            return this;
        }

        @Override
        public Map<String, ?> getAll() {
            return values;
        }

        @Override
        public String getString(String key, String defValue) {
            Object value = values.get(key);
            return value instanceof String ? (String) value : defValue;
        }

        @Override
        public Set<String> getStringSet(String key, Set<String> defValues) {
            return defValues;
        }

        @Override
        public int getInt(String key, int defValue) {
            return defValue;
        }

        @Override
        public long getLong(String key, long defValue) {
            return defValue;
        }

        @Override
        public float getFloat(String key, float defValue) {
            return defValue;
        }

        @Override
        public boolean getBoolean(String key, boolean defValue) {
            return defValue;
        }

        @Override
        public boolean contains(String key) {
            return values.containsKey(key);
        }

        @Override
        public Editor edit() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        }

        @Override
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        }
    }
}
