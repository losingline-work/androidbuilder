package com.androidbuilder.agent;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Decides whether the configured model should be asked to author non-trivial vector graphics. A
 * text-only model hand-writing {@code <vector android:pathData="...">} icons is drawing blind: the
 * paths come out meaningless AND verbose (each icon is hundreds of characters that help overflow the
 * output budget). Only models on a small allow-list of known multimodal/graphics-capable models are
 * trusted with rich graphics; everything else — smaller variants, the flash tier, and any custom or
 * unknown model — is restricted by default to simple shape/built-in drawables.
 *
 * <p>The allow-list is an explicit, easily-edited best guess; when a model's capability is unknown the
 * conservative choice is to restrict.
 */
final class ModelGraphicsCapabilityPolicy {
    private static final Set<String> GRAPHICS_CAPABLE = new HashSet<>(Arrays.asList(
            OpenAiClient.OPENAI_MODEL_GPT_55,
            OpenAiClient.OPENAI_MODEL_GPT_54,
            OpenAiClient.OPENAI_MODEL_GPT_51,
            OpenAiClient.OPENAI_MODEL_GPT_5,
            OpenAiClient.DEEPSEEK_MODEL_PRO,
            OpenAiClient.MINIMAX_MODEL_M3));

    private ModelGraphicsCapabilityPolicy() {
    }

    /** True only for allow-listed multimodal/graphics-capable models; restricted (false) otherwise. */
    static boolean supportsGraphicsGeneration(String provider, String model) {
        return model != null && GRAPHICS_CAPABLE.contains(model.trim());
    }
}
