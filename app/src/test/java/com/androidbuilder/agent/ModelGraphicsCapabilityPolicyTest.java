package com.androidbuilder.agent;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ModelGraphicsCapabilityPolicyTest {
    @Test
    public void allowListedModelsSupportGraphics() {
        assertTrue(ModelGraphicsCapabilityPolicy.supportsGraphicsGeneration(
                OpenAiClient.PROVIDER_OPENAI, OpenAiClient.OPENAI_MODEL_GPT_55));
        assertTrue(ModelGraphicsCapabilityPolicy.supportsGraphicsGeneration(
                OpenAiClient.PROVIDER_DEEPSEEK, OpenAiClient.DEEPSEEK_MODEL_PRO));
        assertTrue(ModelGraphicsCapabilityPolicy.supportsGraphicsGeneration(
                OpenAiClient.PROVIDER_MINIMAX, OpenAiClient.MINIMAX_MODEL_M3));
    }

    @Test
    public void flashSmallAndUnknownModelsAreRestricted() {
        assertFalse(ModelGraphicsCapabilityPolicy.supportsGraphicsGeneration(
                OpenAiClient.PROVIDER_DEEPSEEK, OpenAiClient.DEEPSEEK_MODEL_FLASH));
        assertFalse(ModelGraphicsCapabilityPolicy.supportsGraphicsGeneration(
                OpenAiClient.PROVIDER_OPENAI, OpenAiClient.OPENAI_MODEL_GPT_5_NANO));
        assertFalse(ModelGraphicsCapabilityPolicy.supportsGraphicsGeneration(
                OpenAiClient.PROVIDER_CUSTOM, "some-custom-model"));
        assertFalse(ModelGraphicsCapabilityPolicy.supportsGraphicsGeneration(
                OpenAiClient.PROVIDER_CUSTOM, null));
    }
}
