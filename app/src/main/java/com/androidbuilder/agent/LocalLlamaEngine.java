package com.androidbuilder.agent;

import java.io.File;

public final class LocalLlamaEngine implements AutoCloseable {
    private static final String LIBRARY_NAME = "local_guard_llama";
    private static final boolean LIBRARY_LOADED;

    static {
        boolean loaded;
        try {
            System.loadLibrary(LIBRARY_NAME);
            loaded = true;
        } catch (Throwable ignored) {
            loaded = false;
        }
        LIBRARY_LOADED = loaded;
    }

    private long handle;

    private LocalLlamaEngine(long handle) {
        this.handle = handle;
    }

    public static boolean isNativeAvailable() {
        return LIBRARY_LOADED && isArm64Device();
    }

    public static LocalLlamaEngine create(String modelPath, int contextSize, int threads) {
        if (!isNativeAvailable()) {
            throw new IllegalStateException("Local llama native runtime is unavailable.");
        }
        long handle = nativeCreate(modelPath, contextSize, threads);
        if (handle == 0L) {
            throw new IllegalStateException("Local llama engine failed to load model.");
        }
        return new LocalLlamaEngine(handle);
    }

    public static LocalLlamaEngine create(File modelFile, int contextSize, int threads) {
        return create(modelFile.getAbsolutePath(), contextSize, threads);
    }

    public synchronized String generate(String prompt, int maxTokens, float temperature) {
        return generate(prompt, maxTokens, temperature, "", "root");
    }

    public synchronized String generate(String prompt, int maxTokens, float temperature, String grammar, String grammarRoot) {
        if (handle == 0L) {
            throw new IllegalStateException("Local llama engine is closed.");
        }
        return nativeGenerate(
                handle,
                prompt == null ? "" : prompt,
                maxTokens,
                temperature,
                grammar == null ? "" : grammar,
                grammarRoot == null || grammarRoot.trim().isEmpty() ? "root" : grammarRoot);
    }

    @Override
    public synchronized void close() {
        if (handle != 0L) {
            nativeClose(handle);
            handle = 0L;
        }
    }

    private static boolean isArm64Device() {
        try {
            for (String abi : android.os.Build.SUPPORTED_ABIS) {
                if ("arm64-v8a".equals(abi)) {
                    return true;
                }
            }
            return false;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static native long nativeCreate(String modelPath, int contextSize, int threads);

    private static native String nativeGenerate(long handle, String prompt, int maxTokens, float temperature, String grammar, String grammarRoot);

    private static native void nativeClose(long handle);
}
