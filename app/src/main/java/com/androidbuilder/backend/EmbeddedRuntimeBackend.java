package com.androidbuilder.backend;

import android.content.Context;

import com.androidbuilder.data.AppRepository;
import com.androidbuilder.embeddedruntime.EmbeddedRuntime;
import com.androidbuilder.model.BuildJobRecord;
import com.androidbuilder.util.FileUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class EmbeddedRuntimeBackend implements BuildBackend {
    private final Context context;
    private final AppRepository repository;
    private final EmbeddedRuntime runtime;

    public EmbeddedRuntimeBackend(Context context, AppRepository repository) {
        this.context = context.getApplicationContext();
        this.repository = repository;
        this.runtime = new EmbeddedRuntime(this.context);
    }

    @Override
    public String id() {
        return BuildBackendSettings.EMBEDDED;
    }

    @Override
    public void build(BuildJobRecord job, Listener listener) {
        new Thread(() -> runBuild(job, listener), "embedded-runtime-build").start();
    }

    private void runBuild(BuildJobRecord job, Listener listener) {
        File jobDir = repository.jobDir(job.projectId, job.id);
        File log = new File(jobDir, "build.log");
        try {
            initializeLayout(log);
            File workDir = runtime.work(job.projectId, job.id);
            File sourceWorkDir = new File(workDir, "source");
            FileUtils.deleteRecursively(workDir);
            FileUtils.copyRecursively(repository.sourceDir(job.projectId), sourceWorkDir);
            prepareAndroidGradleProject(sourceWorkDir);
            repository.updateBuildJob(job.id, "building", "embedded_runtime", log.getAbsolutePath(), null, null, job.retryCount);
            listener.onJobChanged(job.projectId, job.id);

            if (!runtime.hasMinimumTools()) {
                String error = "Embedded runtime toolchain is incomplete.\n" +
                        "Missing: " + runtime.missingTools() + "\n" +
                        "Install a full Android arm64 runtime bootstrap, then retry the build.\n";
                FileUtils.appendText(log, error);
                repository.updateBuildJob(job.id, "failed", "embedded_runtime_missing_tools", log.getAbsolutePath(), null, error, job.retryCount);
                repository.addMessage(job.projectId, "assistant", "Embedded build failed: Android runtime toolchain is incomplete.", job.id);
                listener.onJobChanged(job.projectId, job.id);
                return;
            }

            File gradle = gradleExecutable(sourceWorkDir);
            if (gradle == null) {
                String error = "Embedded runtime is initialized, but no Gradle executable was found.\n" +
                        "Expected one of:\n" +
                        " - " + new File(runtime.bin(), "gradle").getAbsolutePath() + "\n" +
                        " - " + new File(sourceWorkDir, "gradlew").getAbsolutePath() + "\n" +
                        "Install or bundle a GPL-compatible Android arm64 bootstrap that provides shell/coreutils/JDK/Gradle/Android SDK/aapt2.\n";
                FileUtils.appendText(log, error);
                repository.updateBuildJob(job.id, "failed", "embedded_runtime_missing_tools", log.getAbsolutePath(), null, error, job.retryCount);
                repository.addMessage(job.projectId, "assistant", "Embedded build failed: missing runtime tools. See build log.", job.id);
                listener.onJobChanged(job.projectId, job.id);
                return;
            }

            FileUtils.appendText(log, "Running embedded build with " + gradle.getAbsolutePath() + "\n");
            int exitCode = runGradle(gradle, sourceWorkDir, log);
            File apk = findApk(sourceWorkDir);
            if (exitCode == 0 && apk != null) {
                File artifact = new File(jobDir, "app-debug.apk");
                FileUtils.copyRecursively(apk, artifact);
                repository.addArtifact(job.projectId, job.id, "apk", artifact.getAbsolutePath());
                repository.updateBuildJob(job.id, "success", "embedded_runtime_finished", log.getAbsolutePath(), artifact.getAbsolutePath(), null, job.retryCount);
                repository.addMessage(job.projectId, "assistant", "Build result: success: APK built with embedded runtime", job.id);
            } else {
                String error = "failed: embedded runtime build exited with " + exitCode + (apk == null ? " and did not produce an APK" : "");
                FileUtils.appendText(log, error + "\n");
                repository.updateBuildJob(job.id, "failed", "embedded_runtime_finished", log.getAbsolutePath(), null, error, job.retryCount);
                repository.addMessage(job.projectId, "assistant", "Build result: " + error, job.id);
            }
        } catch (Exception error) {
            try {
                FileUtils.appendText(log, "Embedded runtime error: " + error.getMessage() + "\n");
            } catch (Exception ignored) {
            }
            repository.updateBuildJob(job.id, "failed", "embedded_runtime_error", log.getAbsolutePath(), null, error.getMessage(), job.retryCount);
            repository.addMessage(job.projectId, "assistant", "Embedded build failed: " + error.getMessage(), job.id);
        }
        listener.onJobChanged(job.projectId, job.id);
    }

    private void initializeLayout(File log) throws Exception {
        runtime.initializeLayout();
        runtime.ensureAndroidSdkWrappers();
        FileUtils.appendText(log, "Embedded runtime root: " + runtime.root().getAbsolutePath() + "\n");
    }

    private File gradleExecutable(File sourceWorkDir) {
        File gradlew = new File(sourceWorkDir, "gradlew");
        if (gradlew.exists()) {
            gradlew.setExecutable(true);
            return gradlew;
        }
        File runtimeGradle = new File(runtime.bin(), "gradle");
        if (runtimeGradle.exists()) {
            runtimeGradle.setExecutable(true);
            return runtimeGradle;
        }
        return null;
    }

    private void prepareAndroidGradleProject(File sourceWorkDir) throws Exception {
        FileUtils.writeText(new File(sourceWorkDir, "local.properties"),
                "sdk.dir=" + runtime.androidSdk().getAbsolutePath() + "\n");

        File gradleProperties = new File(sourceWorkDir, "gradle.properties");
        String existing = gradleProperties.exists() ? FileUtils.readText(gradleProperties) : "";
        StringBuilder next = new StringBuilder(existing);
        if (next.length() > 0 && next.charAt(next.length() - 1) != '\n') {
            next.append('\n');
        }
        if (!existing.contains("android.aapt2FromMavenOverride=")) {
            next.append("android.aapt2FromMavenOverride=")
                    .append(new File(runtime.bin(), "aapt2").getAbsolutePath())
                    .append('\n');
        }
        FileUtils.writeText(gradleProperties, next.toString());
    }

    private int runGradle(File gradle, File sourceWorkDir, File log) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(gradle.getAbsolutePath(), "assembleDebug");
        builder.directory(sourceWorkDir);
        builder.redirectErrorStream(true);
        Map<String, String> env = new HashMap<>(builder.environment());
        env.put("HOME", runtime.home().getAbsolutePath());
        env.put("PREFIX", runtime.usr().getAbsolutePath());
        env.put("ANDROID_HOME", runtime.androidSdk().getAbsolutePath());
        env.put("ANDROID_SDK_ROOT", runtime.androidSdk().getAbsolutePath());
        env.put("PATH", runtime.bin().getAbsolutePath() + ":" + env.getOrDefault("PATH", ""));
        builder.environment().clear();
        builder.environment().putAll(env);
        Process process = builder.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                FileUtils.appendText(log, line + "\n");
            }
        }
        return process.waitFor();
    }

    private File findApk(File dir) {
        if (!dir.exists()) {
            return null;
        }
        if (dir.isFile()) {
            return dir.getName().endsWith(".apk") ? dir : null;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return null;
        }
        for (File child : children) {
            File found = findApk(child);
            if (found != null && found.getAbsolutePath().contains("/build/outputs/apk/")) {
                return found;
            }
        }
        return null;
    }
}
