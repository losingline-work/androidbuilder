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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
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
            ProcessResult version = runCommand(sourceWorkDir, log, listener, job, gradle.getAbsolutePath(), "--version");
            if (version.exitCode != 0) {
                String error = summarizeFailure("Gradle smoke test failed", version);
                repository.updateBuildJob(job.id, "failed", "embedded_runtime_gradle_start_failed", log.getAbsolutePath(), null, error, job.retryCount);
                repository.addMessage(job.projectId, "assistant", "Build failed before Gradle could start:\n" + error, job.id);
                listener.onJobChanged(job.projectId, job.id);
                return;
            }

            ProcessResult build = runCommand(sourceWorkDir, log, listener, job, gradle.getAbsolutePath(), "--no-daemon", "assembleDebug", "--stacktrace");
            File apk = findApk(sourceWorkDir);
            if (build.exitCode == 0 && apk != null) {
                File artifact = new File(jobDir, "app-debug.apk");
                FileUtils.copyRecursively(apk, artifact);
                repository.addArtifact(job.projectId, job.id, "apk", artifact.getAbsolutePath());
                repository.updateBuildJob(job.id, "success", "embedded_runtime_finished", log.getAbsolutePath(), artifact.getAbsolutePath(), null, job.retryCount);
                repository.addMessage(job.projectId, "assistant", "Build result: success: APK built with embedded runtime", job.id);
            } else {
                String error = summarizeFailure("embedded runtime build exited with " + build.exitCode + (apk == null ? " and did not produce an APK" : ""), build);
                FileUtils.appendText(log, error + "\n");
                repository.updateBuildJob(job.id, "failed", "embedded_runtime_finished", log.getAbsolutePath(), null, error, job.retryCount);
                repository.addMessage(job.projectId, "assistant", "Build result: failed\n" + error, job.id);
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
        runtime.ensureGradleLauncherWrapper();
        FileUtils.appendText(log, "Embedded runtime root: " + runtime.root().getAbsolutePath() + "\n");
    }

    private File gradleExecutable(File sourceWorkDir) {
        File appGradle = runtime.androidBuilderGradle();
        if (appGradle.exists()) {
            appGradle.setExecutable(true);
            return appGradle;
        }
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
        StringBuilder next = new StringBuilder();
        for (String line : existing.split("\\R", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("org.gradle.jvmargs=") ||
                    trimmed.startsWith("org.gradle.daemon=") ||
                    trimmed.startsWith("org.gradle.workers.max=") ||
                    trimmed.startsWith("kotlin.compiler.execution.strategy=") ||
                    trimmed.startsWith("android.aapt2FromMavenOverride=")) {
                continue;
            }
            if (line.isEmpty() && next.length() == 0) {
                continue;
            }
            next.append(line).append('\n');
        }
        next.append("org.gradle.daemon=false\n");
        next.append("org.gradle.workers.max=1\n");
        next.append("org.gradle.jvmargs=-Djava.io.tmpdir=")
                .append(runtime.tmp().getAbsolutePath())
                .append(" -Djdk.lang.Process.launchMechanism=VFORK -Dfile.encoding=UTF-8\n");
        next.append("kotlin.compiler.execution.strategy=in-process\n");
        next.append("android.aapt2FromMavenOverride=")
                .append(new File(runtime.bin(), "aapt2").getAbsolutePath())
                .append('\n');
        FileUtils.writeText(gradleProperties, next.toString());
    }

    private ProcessResult runCommand(File sourceWorkDir, File log, Listener listener, BuildJobRecord job, String... command) throws Exception {
        List<String> shellCommand = new ArrayList<>();
        shellCommand.add("/system/bin/sh");
        shellCommand.addAll(Arrays.asList(command));
        FileUtils.appendText(log, "\n$ " + join(shellCommand) + "\n");
        ProcessBuilder builder = new ProcessBuilder(shellCommand);
        builder.directory(sourceWorkDir);
        builder.redirectErrorStream(true);
        Map<String, String> env = new HashMap<>(builder.environment());
        env.put("HOME", runtime.home().getAbsolutePath());
        env.put("PREFIX", runtime.usr().getAbsolutePath());
        env.put("ANDROID_HOME", runtime.androidSdk().getAbsolutePath());
        env.put("ANDROID_SDK_ROOT", runtime.androidSdk().getAbsolutePath());
        env.put("JAVA_HOME", new File(runtime.usr(), "lib/jvm/java-21-openjdk").getAbsolutePath());
        env.put("GRADLE_USER_HOME", new File(runtime.home(), ".gradle").getAbsolutePath());
        env.put("TMPDIR", runtime.tmp().getAbsolutePath());
        env.put("LANG", "C.UTF-8");
        env.put("TERM", "dumb");
        env.remove("JAVA_TOOL_OPTIONS");
        env.remove("JDK_JAVA_OPTIONS");
        env.remove("JAVA_OPTS");
        env.remove("GRADLE_OPTS");
        env.remove("_JAVA_OPTIONS");
        env.put("LD_LIBRARY_PATH", new File(runtime.usr(), "lib").getAbsolutePath() + ":" +
                new File(runtime.usr(), "lib/jvm/java-21-openjdk/lib").getAbsolutePath() + ":" +
                new File(runtime.usr(), "lib/jvm/java-21-openjdk/lib/server").getAbsolutePath());
        env.put("PATH", runtime.bin().getAbsolutePath() + ":" + env.getOrDefault("PATH", ""));
        new File(runtime.home(), ".gradle").mkdirs();
        runtime.tmp().mkdirs();
        builder.environment().clear();
        builder.environment().putAll(env);
        Process process = builder.start();
        StringBuilder tail = new StringBuilder();
        long lastNotifyAt = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                FileUtils.appendText(log, line + "\n");
                tail.append(line).append('\n');
                if (tail.length() > 5000) {
                    tail.delete(0, tail.length() - 5000);
                }
                long now = System.currentTimeMillis();
                if (now - lastNotifyAt > 600) {
                    lastNotifyAt = now;
                    listener.onJobChanged(job.projectId, job.id);
                }
            }
        }
        int exitCode = process.waitFor();
        listener.onJobChanged(job.projectId, job.id);
        return new ProcessResult(exitCode, tail.toString().trim());
    }

    private String summarizeFailure(String prefix, ProcessResult result) {
        String tail = result.tail == null ? "" : result.tail.trim();
        if (tail.length() > 1800) {
            tail = tail.substring(tail.length() - 1800);
        }
        return prefix + (tail.isEmpty() ? "" : "\n\nLast log:\n" + tail);
    }

    private String join(List<String> command) {
        StringBuilder value = new StringBuilder();
        for (String part : command) {
            if (value.length() > 0) {
                value.append(' ');
            }
            value.append(part);
        }
        return value.toString();
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

    private static class ProcessResult {
        final int exitCode;
        final String tail;

        ProcessResult(int exitCode, String tail) {
            this.exitCode = exitCode;
            this.tail = tail;
        }
    }
}
