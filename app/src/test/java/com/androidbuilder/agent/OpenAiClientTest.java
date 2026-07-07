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
    public void restrictedModelGetsGraphicsRestrictionClause() {
        String clause = OpenAiClient.graphicsRestrictionClause(
                OpenAiClient.PROVIDER_DEEPSEEK, OpenAiClient.DEEPSEEK_MODEL_FLASH, false);
        assertTrue(clause.contains("android:pathData"));
        assertTrue(clause.toLowerCase(java.util.Locale.ROOT).contains("shape"));
    }

    @Test
    public void graphicsCapableModelGetsNoRestrictionClause() {
        assertEquals("", OpenAiClient.graphicsRestrictionClause(
                OpenAiClient.PROVIDER_OPENAI, OpenAiClient.OPENAI_MODEL_GPT_55, false));
        assertEquals("", OpenAiClient.graphicsRestrictionClause(
                OpenAiClient.PROVIDER_OPENAI, OpenAiClient.OPENAI_MODEL_GPT_55, true));
    }

    @Test
    public void requestBodyCapsOutputTokensSoLargeResponsesDoNotTruncate() throws Exception {
        JSONObject body = OpenAiClient.chatRequestBodyForTest(
                OpenAiClient.PROVIDER_DEEPSEEK,
                "deepseek-v4-flash",
                "system",
                Collections.emptyList(),
                "latest",
                0.2,
                true);

        assertEquals(OpenAiClient.MAX_OUTPUT_TOKENS, body.getInt("max_tokens"));
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
    public void modelReadTimeoutsUseThreeMinutes() {
        assertEquals(180000, OpenAiClient.defaultReadTimeoutMsForTest());
        assertEquals(180000, OpenAiClient.tasksReadTimeoutMsForTest());
        assertEquals(180000, OpenAiClient.codingReadTimeoutMsForTest());
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
        assertTrue(system.contains("Request a file in neededFiles only if it plausibly already exists"));
        assertTrue(system.contains("Never set ready=false solely because files that do not exist yet are missing"));
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
    public void taskOperationsUserPromptPlacesDraftCorrectionBeforeSourceTree() {
        String prompt = OpenAiClient.taskOperationsUserPromptForTest(
                "# Engineering Plan\nUpdate DAO",
                "Fix DAO",
                "Synchronize constructor",
                "--- app/src/main/java/com/example/RecordDao.java ---\nclass RecordDao {}",
                "",
                "Retry context",
                "Previous draft manifest:\n- write app/src/main/java/com/example/RecordDao.java");

        assertTrue(prompt.contains("You are CORRECTING your previous draft for this task, not rewriting it."));
        assertTrue(prompt.contains("===BLOCKED==="));
        assertTrue(prompt.contains("===PREREQ==="));
        assertTrue(prompt.contains("===DROP relative/posix/path==="));
        assertTrue(prompt.indexOf("Previous draft manifest") < prompt.indexOf("Current source tree"));
        assertTrue(prompt.indexOf("You are CORRECTING") < prompt.indexOf("Current source tree"));
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
        assertTrue(prompt.contains("When a new project skeleton is needed, the first task must create Gradle files, app/src/main/AndroidManifest.xml, and base values/themes resources before Java wiring"));
        assertTrue(prompt.contains("A later layout/drawable task may also add missing app/src/main/res/values entries it references"));
        assertTrue(prompt.contains("Escape double quotes inside JSON string values"));
        assertTrue(prompt.contains("allowedPaths"));
        assertTrue(prompt.contains("expectedFiles"));
        assertTrue(prompt.contains("acceptanceChecks"));
        assertTrue(prompt.contains("buildRequiredAfter"));
    }

    @Test
    public void taskSplitPromptRequestsParallelContracts() {
        String prompt = OpenAiClient.tasksSystemPromptForTest(false);

        assertTrue(prompt.contains("dependsOn"));
        assertTrue(prompt.contains("produces"));
        assertTrue(prompt.contains("allowedPaths"));
        assertTrue(prompt.contains("safe parallel"));
    }

    @Test
    public void taskManifestPromptRequestsFileListWithoutContent() {
        String prompt = OpenAiClient.taskManifestSystemPromptForTest(false);

        assertTrue(prompt.contains("summary"));
        assertTrue(prompt.contains("files"));
        assertTrue(prompt.contains("path"));
        assertTrue(prompt.contains("intent"));
        assertTrue(prompt.contains("Do not include file content"));
        assertTrue(prompt.contains("blockedReason"));
    }

    @Test
    public void taskOperationsBatchPromptScopesGenerationToRequestedFiles() {
        String prompt = OpenAiClient.taskOperationsBatchUserPromptForTest(
                "# Plan",
                "Resources",
                "Write resources.",
                "(empty)",
                "",
                "",
                java.util.Collections.singletonList(new com.androidbuilder.model.TaskManifest.Entry(
                        "app/src/main/res/values/strings.xml",
                        "write",
                        "base strings")),
                "--- app/src/main/res/values/colors.xml ---\n<resources />");

        assertTrue(prompt.contains("Generate complete file operations for exactly these files"));
        assertTrue(prompt.contains("app/src/main/res/values/strings.xml"));
        assertTrue(prompt.contains("base strings"));
        assertTrue(prompt.contains("AUTHORITATIVE API CONTRACT"));
        assertTrue(prompt.contains("EXACT method names"));
        assertTrue(prompt.contains("Do not include any unrequested file"));
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
    public void taskOperationsPromptTreatsResourceIndexAsAuthoritative() {
        String prompt = OpenAiClient.taskOperationsSystemPromptForTest(false);

        assertTrue(prompt.contains("resource index"));
        assertTrue(prompt.contains("only authoritative resource truth table"));
        assertTrue(prompt.contains("Never invent a new resource name"));
        assertTrue(prompt.contains("return blocked with prerequisiteWork naming it"));
        assertTrue(prompt.contains("Conversely, every name listed here EXISTS"));
        assertTrue(prompt.contains("The snapshot inventory (full text + API digest + coverage note) is complete"));
        assertTrue(prompt.contains("creating it is part of your task when needed"));
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
    public void structuredOutputDisablesEffectiveThinkingEvenWhenUserEnabledIt() {
        assertFalse(OpenAiClient.effectiveThinkingForTest(true, true));
        assertFalse(OpenAiClient.effectiveThinkingForTest(false, true));
        assertFalse(OpenAiClient.effectiveThinkingForTest(false, false));
        assertTrue(OpenAiClient.effectiveThinkingForTest(true, false));
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
    public void streamInspectorCanAbortStreamingResponseWithOriginalMessage() throws Exception {
        String content = repeat("x", 130);
        String sse = "data: {\"choices\":[{\"delta\":{\"content\":\"" + content + "\"}}]}\n"
                + "data: [DONE]\n";

        OpenAiClient.StreamAbortException error = org.junit.Assert.assertThrows(
                OpenAiClient.StreamAbortException.class,
                () -> OpenAiClient.readChatContentForTest(sse, answerSoFar -> {
                    if (answerSoFar.length() >= 130) {
                        throw new OpenAiClient.StreamAbortException("stop now");
                    }
                }));

        assertEquals("stop now", error.getMessage());
    }

    @Test
    public void fallsBackToPlainJsonWhenServerIgnoresStream() throws Exception {
        String body = "{\"choices\":[{\"message\":{\"content\":\"plain\"}}]}";

        assertEquals("plain", OpenAiClient.readChatContentForTest(body));
    }

    @Test
    public void explicitNullContentDeltasDoNotPolluteAnswerWithLiteralNull() throws Exception {
        // Providers send content:null while streaming reasoning-only deltas; org.json's optString turns
        // JSONObject.NULL into the literal string "null", which used to corrupt the answer.
        String sse = "data: {\"choices\":[{\"delta\":{\"content\":null,\"reasoning_content\":\"thinking\"}}]}\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":null}}]}\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"# Plan\"}}]}\n"
                + "data: [DONE]\n";

        assertEquals("# Plan", OpenAiClient.readChatContentForTest(sse));
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

    private static String repeat(String value, int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            builder.append(value);
        }
        return builder.toString();
    }

    @Test
    public void mainstreamCompatibleProvidersResolveToFullChatEndpointsAndCuratedModels() {
        for (String provider : OpenAiClient.mainstreamCompatibleProviders()) {
            String endpoint = OpenAiClient.defaultEndpoint(provider);
            assertTrue(provider + " endpoint: " + endpoint, endpoint.endsWith("/chat/completions"));
            String[] models = OpenAiClient.modelsForProvider(provider);
            assertTrue(provider + " has models", models.length > 0);
            // the curated default must be one of the listed models
            assertTrue(provider + " default in list",
                    java.util.Arrays.asList(models).contains(OpenAiClient.defaultModel(provider)));
        }
        // The expected providers are present, in UI order.
        assertEquals(java.util.Arrays.asList(
                OpenAiClient.PROVIDER_ZHIPU, OpenAiClient.PROVIDER_MOONSHOT,
                OpenAiClient.PROVIDER_QWEN, OpenAiClient.PROVIDER_DOUBAO, OpenAiClient.PROVIDER_OPENROUTER,
                OpenAiClient.PROVIDER_GROQ),
                java.util.Arrays.asList(OpenAiClient.mainstreamCompatibleProviders()));
        assertEquals("https://openrouter.ai/api/v1/chat/completions",
                OpenAiClient.defaultEndpoint(OpenAiClient.PROVIDER_OPENROUTER));
    }

    @Test
    public void specForReturnsNullForNonTableProviders() {
        assertEquals(null, OpenAiClient.specFor(OpenAiClient.PROVIDER_OPENAI));
        assertEquals(null, OpenAiClient.specFor(OpenAiClient.PROVIDER_CUSTOM));
        assertEquals(null, OpenAiClient.specFor("unknown"));
        assertTrue(OpenAiClient.specFor(OpenAiClient.PROVIDER_ZHIPU) != null);
    }

    @Test
    public void normalizedEndpointAppendsChatPathForBareBases() {
        // Zhipu (/api/paas/v4), Ark/Doubao (/api/v3), and the /v1 family all resolve to a full chat URL.
        assertEquals("https://open.bigmodel.cn/api/paas/v4/chat/completions",
                OpenAiClient.normalizedEndpoint(OpenAiClient.PROVIDER_ZHIPU, "https://open.bigmodel.cn/api/paas/v4/"));
        assertEquals("https://ark.cn-beijing.volces.com/api/v3/chat/completions",
                OpenAiClient.normalizedEndpoint(OpenAiClient.PROVIDER_DOUBAO, "https://ark.cn-beijing.volces.com/api/v3"));
        assertEquals("https://api.moonshot.cn/v1/chat/completions",
                OpenAiClient.normalizedEndpoint(OpenAiClient.PROVIDER_MOONSHOT, "https://api.moonshot.cn/v1"));
        // A full endpoint passes through unchanged.
        assertEquals("https://api.groq.com/openai/v1/chat/completions",
                OpenAiClient.normalizedEndpoint(OpenAiClient.PROVIDER_GROQ, "https://api.groq.com/openai/v1/chat/completions"));
    }

    @Test
    public void slashAndPrefixedModelIdsPassThroughVerbatim() {
        // OpenRouter slash ids and Ark 'ep-' ids must never be snapped to a default.
        assertEquals("anthropic/claude-sonnet-4.5",
                OpenAiClient.normalizedModel(OpenAiClient.PROVIDER_OPENROUTER, "anthropic/claude-sonnet-4.5"));
        assertEquals("ep-20240101-abcde",
                OpenAiClient.normalizedModel(OpenAiClient.PROVIDER_DOUBAO, "ep-20240101-abcde"));
        // blank falls back to the curated default
        assertEquals("glm-4.6", OpenAiClient.normalizedModel(OpenAiClient.PROVIDER_ZHIPU, ""));
    }

    @Test
    public void newProvidersSendPlainOpenAiBodyWithTemperatureAndNoReasoningKeys() throws Exception {
        for (String provider : OpenAiClient.mainstreamCompatibleProviders()) {
            JSONObject body = OpenAiClient.chatRequestBodyForTest(
                    provider, OpenAiClient.defaultModel(provider), "system",
                    Collections.emptyList(), "latest", 0.2, true);
            assertEquals(provider, OpenAiClient.defaultModel(provider), body.getString("model"));
            assertTrue(provider + " streams", body.getBoolean("stream"));
            assertEquals(provider, OpenAiClient.maxOutputTokensFor(provider, OpenAiClient.defaultModel(provider)),
                    body.getInt("max_tokens"));
            assertTrue(provider + " keeps temperature", body.has("temperature"));
            assertFalse(provider + " has no reasoning_split", body.has("reasoning_split"));
            assertFalse(provider + " has no thinking key", body.has("thinking"));
        }
    }

    @Test
    public void existingProviderRequestBodiesStayByteIdentical() throws Exception {
        // Regression: routing new providers through the registry must not drift the existing bodies. Assert
        // the exact key set (order-independent — org.json key order is not guaranteed) + the distinguishing
        // provider tweaks (temperature / reasoning_split / thinking).
        JSONObject openai = OpenAiClient.chatRequestBodyForTest(OpenAiClient.PROVIDER_OPENAI, "gpt-5.5", "sys",
                Collections.emptyList(), "u", 0.2, true);
        assertEquals(4, openai.length()); // model, stream, max_tokens, messages (gpt-5.5 omits temperature)
        assertEquals("gpt-5.5", openai.getString("model"));
        assertFalse(openai.has("temperature"));
        assertFalse(openai.has("reasoning_split"));
        assertFalse(openai.has("thinking"));

        JSONObject minimax = OpenAiClient.chatRequestBodyForTest(OpenAiClient.PROVIDER_MINIMAX, "MiniMax-M3", "sys",
                Collections.emptyList(), "u", 0.2, true);
        assertEquals(6, minimax.length()); // + temperature + reasoning_split
        assertEquals(0.2, minimax.getDouble("temperature"), 0.0001);
        assertTrue(minimax.getBoolean("reasoning_split"));
        assertFalse(minimax.has("thinking"));

        JSONObject deepseek = OpenAiClient.chatRequestBodyForTest(OpenAiClient.PROVIDER_DEEPSEEK, "deepseek-v4-flash",
                "sys", Collections.emptyList(), "u", 0.2, false); // thinking OFF
        assertEquals(6, deepseek.length()); // + temperature + thinking
        assertEquals("disabled", deepseek.getJSONObject("thinking").getString("type"));
        assertFalse(deepseek.has("reasoning_split"));
    }

    @Test
    public void maxOutputTokensRaisedOnlyForVerifiedProviders() throws Exception {
        // Verified direct-endpoint output caps: zhipu GLM (128K) and MiniMax (131K) get a conservative 16K.
        assertEquals(16384, OpenAiClient.maxOutputTokensFor(OpenAiClient.PROVIDER_ZHIPU, "glm-4.6"));
        assertEquals(16384, OpenAiClient.maxOutputTokensFor(OpenAiClient.PROVIDER_MINIMAX, "MiniMax-M3"));
        // Unverified providers stay at the safe shared default (an over-cap value would 400 every call).
        assertEquals(OpenAiClient.MAX_OUTPUT_TOKENS,
                OpenAiClient.maxOutputTokensFor(OpenAiClient.PROVIDER_DEEPSEEK, "deepseek-v4-flash"));
        assertEquals(OpenAiClient.MAX_OUTPUT_TOKENS,
                OpenAiClient.maxOutputTokensFor(OpenAiClient.PROVIDER_OPENAI, "gpt-5.5"));
        assertEquals(OpenAiClient.MAX_OUTPUT_TOKENS,
                OpenAiClient.maxOutputTokensFor(OpenAiClient.PROVIDER_MOONSHOT, "kimi-k2.6"));

        // The verified value flows into the request body.
        JSONObject zhipu = OpenAiClient.chatRequestBodyForTest(OpenAiClient.PROVIDER_ZHIPU, "glm-4.6", "sys",
                Collections.emptyList(), "u", 0.2, true);
        assertEquals(16384, zhipu.getInt("max_tokens"));
        JSONObject deepseek = OpenAiClient.chatRequestBodyForTest(OpenAiClient.PROVIDER_DEEPSEEK, "deepseek-v4-flash",
                "sys", Collections.emptyList(), "u", 0.2, true);
        assertEquals(OpenAiClient.MAX_OUTPUT_TOKENS, deepseek.getInt("max_tokens"));
    }

    @Test
    public void flagshipNewModelsAreGraphicsCapableButTiersAreRestricted() {
        assertEquals("", OpenAiClient.graphicsRestrictionClause(OpenAiClient.PROVIDER_ZHIPU, "glm-4.6", false));
        assertEquals("", OpenAiClient.graphicsRestrictionClause(OpenAiClient.PROVIDER_QWEN, "qwen-max", false));
        assertEquals("", OpenAiClient.graphicsRestrictionClause(
                OpenAiClient.PROVIDER_OPENROUTER, "anthropic/claude-sonnet-4.5", false));
        // a restricted small/flash tier still gets the restriction clause
        assertFalse(OpenAiClient.graphicsRestrictionClause(
                OpenAiClient.PROVIDER_GROQ, "llama-3.1-8b-instant", false).isEmpty());
    }

    @Test
    public void nativeProvidersResolveEndpointsModelsAndFlag() {
        assertTrue(OpenAiClient.isNativeProvider(OpenAiClient.PROVIDER_ANTHROPIC));
        assertTrue(OpenAiClient.isNativeProvider(OpenAiClient.PROVIDER_GEMINI));
        assertFalse(OpenAiClient.isNativeProvider(OpenAiClient.PROVIDER_OPENAI));
        assertFalse(OpenAiClient.isNativeProvider(OpenAiClient.PROVIDER_OPENROUTER));
        assertEquals("https://api.anthropic.com/v1/messages",
                OpenAiClient.defaultEndpoint(OpenAiClient.PROVIDER_ANTHROPIC));
        assertEquals(OpenAiClient.ANTHROPIC_MODEL_OPUS, OpenAiClient.defaultModel(OpenAiClient.PROVIDER_ANTHROPIC));
        assertEquals("https://generativelanguage.googleapis.com/v1beta",
                OpenAiClient.defaultEndpoint(OpenAiClient.PROVIDER_GEMINI));
        assertEquals(OpenAiClient.GEMINI_MODEL_FLASH, OpenAiClient.defaultModel(OpenAiClient.PROVIDER_GEMINI));
        // Anthropic normalizes a bare base by appending /v1/messages; Gemini keeps the bare base.
        assertEquals("https://api.anthropic.com/v1/messages",
                OpenAiClient.normalizedEndpoint(OpenAiClient.PROVIDER_ANTHROPIC, "https://api.anthropic.com"));
        assertEquals("https://generativelanguage.googleapis.com/v1beta",
                OpenAiClient.normalizedEndpoint(OpenAiClient.PROVIDER_GEMINI, "https://generativelanguage.googleapis.com/v1beta/"));
    }

    @Test
    public void anthropicRequestBodyUsesMessagesApiShape() throws Exception {
        JSONObject body = OpenAiClient.anthropicRequestBodyForTest("claude-opus-4-8", "sys",
                java.util.Arrays.asList(msg("user", "hi"), msg("assistant", "yo")), "now");
        assertEquals("claude-opus-4-8", body.getString("model"));
        assertEquals(OpenAiClient.MAX_OUTPUT_TOKENS, body.getInt("max_tokens"));
        assertTrue(body.getBoolean("stream"));
        assertEquals("sys", body.getString("system")); // top-level string, not a message
        assertFalse(body.has("temperature"));
        org.json.JSONArray msgs = body.getJSONArray("messages");
        assertEquals(3, msgs.length());
        assertEquals("user", msgs.getJSONObject(0).getString("role"));
        assertEquals("assistant", msgs.getJSONObject(1).getString("role"));
        assertEquals("now", msgs.getJSONObject(2).getString("content"));
    }

    @Test
    public void geminiRequestBodyMapsRolesAndPutsModelInUrl() throws Exception {
        JSONObject body = OpenAiClient.geminiRequestBodyForTest("gemini-2.5-flash", "sys",
                java.util.Arrays.asList(msg("user", "hi"), msg("assistant", "yo")), "now", 0.2);
        assertFalse("model lives in the URL, not the body", body.has("model"));
        org.json.JSONArray contents = body.getJSONArray("contents");
        assertEquals(3, contents.length());
        assertEquals("user", contents.getJSONObject(0).getString("role"));
        assertEquals("model", contents.getJSONObject(1).getString("role")); // assistant -> model
        assertEquals("now", contents.getJSONObject(2).getJSONArray("parts").getJSONObject(0).getString("text"));
        assertEquals("sys", body.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text"));
        assertEquals(OpenAiClient.MAX_OUTPUT_TOKENS, body.getJSONObject("generationConfig").getInt("maxOutputTokens"));
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse",
                OpenAiClient.geminiStreamUrl("https://generativelanguage.googleapis.com/v1beta/", "gemini-2.5-flash"));
    }

    @Test
    public void anthropicStreamExtractsOnlyTextDeltas() throws Exception {
        String sse = "event: message_start\n"
                + "data: {\"type\":\"message_start\",\"message\":{\"id\":\"x\"}}\n\n"
                + "event: content_block_delta\n"
                + "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello\"}}\n\n"
                + "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"hmm\"}}\n\n"
                + "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\" world\"}}\n\n"
                + "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n";
        assertEquals("Hello world",
                OpenAiClient.readNativeStreamContentForTest(sse, OpenAiClient::extractAnthropicDelta));
    }

    @Test
    public void geminiStreamConcatenatesPartText() throws Exception {
        String sse = "data: {\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"Hello\"}]}}]}\n\n"
                + "data: {\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\" world\"}]}}]}\n\n";
        // No [DONE] from Gemini — termination is EOF.
        assertEquals("Hello world",
                OpenAiClient.readNativeStreamContentForTest(sse, OpenAiClient::extractGeminiDelta));
    }

    @Test
    public void nativeDeltaExtractorsIgnoreNonTextPayloads() {
        assertEquals("", OpenAiClient.extractAnthropicDelta(new JSONObject()));
        assertEquals("", OpenAiClient.extractGeminiDelta(new JSONObject()));
    }

    private static ChatMessage msg(String role, String content) {
        return new ChatMessage(0, 0, role, content, 0, null);
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
