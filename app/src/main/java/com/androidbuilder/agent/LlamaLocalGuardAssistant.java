package com.androidbuilder.agent;

import android.content.Context;

import com.androidbuilder.model.TaskOperations;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class LlamaLocalGuardAssistant implements LocalGuardAssistant {
    private static final int CONTEXT_SIZE = 4096;
    private static final int MAX_TOKENS = 512;
    private static final float TEMPERATURE = 0.0f;
    private static final int TIMEOUT_SECONDS = 45;
    private static final int PROMPT_CHAR_LIMIT = 9000;

    private final Context context;
    // One reused worker serializes all native calls so a model is never loaded concurrently.
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "local-guard-llama");
        thread.setDaemon(true);
        return thread;
    });
    private LocalLlamaEngine engine;
    private String engineKey = "";

    LlamaLocalGuardAssistant(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public LocalGuardResult reviewOperations(String plan, String taskTitle, String taskInstruction, String sourceSnapshot, TaskOperations operations) {
        if (!shouldRun(LocalGuardTrigger.PREFLIGHT)) {
            return LocalGuardResult.unusable("");
        }
        LocalGuardResult heuristic = LocalGuardHeuristics.reviewOperations(sourceSnapshot, operations);
        if (heuristic.usable && heuristic.decision == LocalGuardResult.Decision.REWRITE) {
            return heuristic;
        }
        String prompt = LocalGuardPromptBuilder.reviewOperationsPrompt(plan, taskTitle, taskInstruction, sourceSnapshot, operations);
        return generate(prompt);
    }

    @Override
    public LocalGuardResult rewritePolicyFailure(String taskInstruction, String policyError, String focusedSnapshot, int attempt) {
        if (!shouldRun(LocalGuardTrigger.POLICY_ERROR)) {
            return LocalGuardResult.unusable("");
        }
        LocalGuardResult heuristic = LocalGuardHeuristics.rewritePolicyFailure(policyError);
        if (heuristic.usable && heuristic.decision == LocalGuardResult.Decision.REWRITE) {
            return heuristic;
        }
        String prompt = LocalGuardPromptBuilder.policyFailurePrompt(taskInstruction, policyError, focusedSnapshot, attempt);
        return generate(prompt);
    }

    @Override
    public LocalGuardResult triageBuildFailure(String buildLog, String focusedSnapshot) {
        if (!shouldRun(LocalGuardTrigger.TRIAGE)) {
            return LocalGuardResult.unusable("");
        }
        if (buildLog == null || buildLog.trim().isEmpty()) {
            return LocalGuardResult.unusable("");
        }
        return generate(LocalGuardPromptBuilder.triageBuildFailurePrompt(buildLog, focusedSnapshot));
    }

    private boolean shouldRun(LocalGuardTrigger trigger) {
        if (!LocalGuardSettings.isEnabled(context)) {
            return false;
        }
        LocalGuardMode mode = LocalGuardSettings.mode(context);
        if (trigger == LocalGuardTrigger.PREFLIGHT) {
            return mode.shouldPreflight();
        }
        if (trigger == LocalGuardTrigger.TRIAGE) {
            return mode.shouldTriageBuildFailure();
        }
        return mode.shouldRewritePolicyError();
    }

    private LocalGuardResult generate(String prompt) {
        if (!LocalGuardSettings.isModelReady(context)) {
            return LocalGuardResult.unusable("Local guard unavailable: import a GGUF model in Settings.");
        }
        if (!LocalLlamaEngine.isNativeAvailable()) {
            return LocalGuardResult.unusable("Local guard unavailable: llama.cpp native runtime is not available for this device/build.");
        }
        if (prompt != null && prompt.length() > PROMPT_CHAR_LIMIT) {
            return LocalGuardResult.unusable("Local guard skipped: compact prompt is still too large for the local context window.");
        }
        Future<LocalGuardResult> future;
        try {
            future = worker.submit(() -> {
                LocalLlamaEngine ready = ensureEngine();
                String output = ready.generate(
                        prompt,
                        MAX_TOKENS,
                        TEMPERATURE,
                        LocalGuardJsonGrammar.grammar(),
                        LocalGuardJsonGrammar.root());
                return LocalGuardResultParser.parse(output);
            });
        } catch (RejectedExecutionException error) {
            return LocalGuardResult.unusable("Local guard unavailable: assistant was released.");
        }
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException error) {
            // Leave the cached engine in place; the native call finishes on the serialized worker
            // and the loaded model is reused by the next call instead of being reloaded.
            future.cancel(true);
            return LocalGuardResult.unusable("Local guard timed out after " + TIMEOUT_SECONDS + " seconds; continuing without blocking the cloud flow.");
        } catch (Exception error) {
            String message = error.getMessage() == null ? error.toString() : error.getMessage();
            return LocalGuardResult.unusable("Local guard unavailable: " + message);
        }
    }

    /**
     * Lazily loads the GGUF model once and reuses it. The KV cache is cleared per generation in
     * native code, so reuse is safe; the model is only reloaded when the imported file changes.
     * Runs on the single worker thread, so creation is naturally serialized.
     */
    private synchronized LocalLlamaEngine ensureEngine() {
        File modelFile = LocalGuardSettings.modelFile(context);
        int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
        String key = modelFile.getAbsolutePath() + "|" + modelFile.length() + "|" + CONTEXT_SIZE + "|" + threads;
        if (engine != null && key.equals(engineKey)) {
            return engine;
        }
        closeEngine();
        engine = LocalLlamaEngine.create(modelFile, CONTEXT_SIZE, threads);
        engineKey = key;
        return engine;
    }

    @Override
    public synchronized void close() {
        closeEngine();
        worker.shutdownNow();
    }

    private synchronized void closeEngine() {
        if (engine != null) {
            try {
                engine.close();
            } catch (Throwable ignored) {
                // Best-effort release.
            }
            engine = null;
            engineKey = "";
        }
    }

    private enum LocalGuardTrigger {
        PREFLIGHT,
        POLICY_ERROR,
        TRIAGE
    }
}
