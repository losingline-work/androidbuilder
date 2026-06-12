package com.androidbuilder.backend;

import android.content.Context;

import com.androidbuilder.agent.BuildLogContextExtractor;
import com.androidbuilder.data.AppRepository;
import com.androidbuilder.embeddedruntime.EmbeddedRuntime;
import com.androidbuilder.model.BuildJobRecord;
import com.androidbuilder.util.ActiveWorkRegistry;
import com.androidbuilder.util.AppSettings;
import com.androidbuilder.util.FileUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

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
        new Thread(() -> {
            ActiveWorkRegistry.begin(context, job.projectId, context.getString(com.androidbuilder.R.string.foreground_work_building));
            try {
                runBuild(job, listener);
            } finally {
                ActiveWorkRegistry.end(context, job.projectId);
            }
        }, "embedded-runtime-build").start();
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
            if (!runtime.hasMinimumTools()) {
                String error = context.getString(com.androidbuilder.R.string.embedded_runtime_toolchain_incomplete, runtime.missingTools()) + "\n";
                FileUtils.appendText(log, error);
                repository.updateBuildJob(job.id, "failed", "embedded_runtime_missing_tools", log.getAbsolutePath(), null, error, job.retryCount);
                repository.addMessage(job.projectId, "assistant", buildFailureMessage(error), job.id);
                listener.onJobChanged(job.projectId, job.id);
                return;
            }
            prepareAndroidGradleProject(sourceWorkDir);
            File initScript = dependencyInitScript(sourceWorkDir);
            if (BuildBackendSettings.DEPENDENCY_ONLINE.equals(BuildBackendSettings.dependencyMode(context)) &&
                    projectUsesExternalDependencies(sourceWorkDir) &&
                    !canReachOnlineMaven()) {
                String error = context.getString(com.androidbuilder.R.string.dependency_network_failed, "Google Maven / Maven Central");
                FileUtils.appendText(log, error + "\n");
                repository.updateBuildJob(job.id, "failed", "dependency_network_unavailable", log.getAbsolutePath(), null, error, job.retryCount);
                repository.addMessage(job.projectId, "assistant", buildFailureMessage(error), job.id);
                listener.onJobChanged(job.projectId, job.id);
                return;
            }
            FileUtils.appendText(log, "Applied Android Gradle compatibility: compileSdk " + runtime.compileSdkApi() + ", Java source/target 8\n");
            repository.updateBuildJob(job.id, "building", "embedded_runtime", log.getAbsolutePath(), null, null, job.retryCount);
            listener.onJobChanged(job.projectId, job.id);

            File gradle = gradleExecutable(sourceWorkDir);
            if (gradle == null) {
                String error = context.getString(
                        com.androidbuilder.R.string.embedded_runtime_gradle_missing,
                        new File(runtime.bin(), "gradle").getAbsolutePath(),
                        new File(sourceWorkDir, "gradlew").getAbsolutePath()) + "\n";
                FileUtils.appendText(log, error);
                repository.updateBuildJob(job.id, "failed", "embedded_runtime_missing_tools", log.getAbsolutePath(), null, error, job.retryCount);
                repository.addMessage(job.projectId, "assistant", buildFailureMessage(error), job.id);
                listener.onJobChanged(job.projectId, job.id);
                return;
            }

            FileUtils.appendText(log, "Running embedded build with " + gradle.getAbsolutePath() + "\n");
            ProcessResult version = runCommand(sourceWorkDir, log, listener, job, gradle.getAbsolutePath(), "--version");
            if (version.exitCode != 0) {
                boolean timeout = version.exitCode == 124;
                String error = summarizeFailure(timeout
                        ? localized("Gradle 冒烟测试超时", "Gradle smoke test timed out")
                        : localized("Gradle 冒烟测试失败", "Gradle smoke test failed"), version);
                repository.updateBuildJob(job.id, "failed", timeout ? "embedded_runtime_timeout" : "embedded_runtime_gradle_start_failed", log.getAbsolutePath(), null, error, job.retryCount);
                repository.addMessage(job.projectId, "assistant", buildFailureMessage(error), job.id);
                listener.onJobChanged(job.projectId, job.id);
                return;
            }

            // Online mode: resolve third-party dependencies first, so a bad coordinate fails in
            // seconds with a clean, classifiable error instead of burning a full build.
            if (BuildBackendSettings.DEPENDENCY_ONLINE.equals(BuildBackendSettings.dependencyMode(context))
                    && projectUsesExternalDependencies(sourceWorkDir)) {
                File resolveScript = dependencyResolutionPreflightScript(sourceWorkDir);
                FileUtils.appendText(log, "Resolving third-party dependencies before the full build...\n");
                ProcessResult resolve = runCommand(sourceWorkDir, log, listener, job,
                        gradle.getAbsolutePath(),
                        "--init-script", resolveScript.getAbsolutePath(),
                        "--no-daemon", "--console=plain",
                        "abResolveDebugDeps");
                if (resolve.exitCode != 0) {
                    boolean timeout = resolve.exitCode == 124;
                    String error = summarizeFailure(timeout
                            ? localized("依赖解析超时", "dependency resolution timed out")
                            : localized("依赖解析失败：声明的依赖无法从当前仓库解析", "dependency_resolution_failed: a declared dependency could not be resolved from the configured repositories"), resolve);
                    FileUtils.appendText(log, error + "\n");
                    repository.updateBuildJob(job.id, "failed", timeout ? "embedded_runtime_timeout" : "dependency_resolution_failed", log.getAbsolutePath(), null, error, job.retryCount);
                    repository.addMessage(job.projectId, "assistant", buildFailureMessage(error), job.id);
                    listener.onJobChanged(job.projectId, job.id);
                    return;
                }
                FileUtils.appendText(log, "Dependency resolution OK.\n");
            }

            List<String> buildArgs = new ArrayList<>();
            buildArgs.add(gradle.getAbsolutePath());
            if (initScript != null) {
                buildArgs.add("--init-script");
                buildArgs.add(initScript.getAbsolutePath());
            }
            buildArgs.add("--no-daemon");
            buildArgs.add("--console=plain");
            buildArgs.add("assembleDebug");
            buildArgs.add("--stacktrace");
            ProcessResult build = runCommand(sourceWorkDir, log, listener, job, buildArgs.toArray(new String[0]));
            File apk = findApk(sourceWorkDir);
            if (build.exitCode == 0 && apk != null) {
                File artifact = new File(jobDir, "app-debug.apk");
                FileUtils.copyRecursively(apk, artifact);
                repository.addArtifact(job.projectId, job.id, "apk", artifact.getAbsolutePath());
                repository.updateBuildJob(job.id, "success", "embedded_runtime_finished", log.getAbsolutePath(), artifact.getAbsolutePath(), null, job.retryCount);
                repository.addMessage(job.projectId, "assistant", context.getString(com.androidbuilder.R.string.build_summary_success), job.id);
            } else {
                boolean timeout = build.exitCode == 124;
                String error = summarizeFailure(timeout
                        ? localized("内置运行环境构建超时", "embedded runtime build timed out")
                        : localized(
                                "内置运行环境构建退出码 " + build.exitCode + (apk == null ? "，且没有产出 APK" : ""),
                                "embedded runtime build exited with " + build.exitCode + (apk == null ? " and did not produce an APK" : "")),
                        build);
                FileUtils.appendText(log, error + "\n");
                repository.updateBuildJob(job.id, "failed", timeout ? "embedded_runtime_timeout" : "embedded_runtime_finished", log.getAbsolutePath(), null, error, job.retryCount);
                repository.addMessage(job.projectId, "assistant", buildFailureMessage(error), job.id);
            }
        } catch (Exception error) {
            String message = error.getMessage() == null ? error.toString() : error.getMessage();
            try {
                FileUtils.appendText(log, localized("内置运行环境错误：", "Embedded runtime error: ") + message + "\n");
            } catch (Exception ignored) {
            }
            repository.updateBuildJob(job.id, "failed", "embedded_runtime_error", log.getAbsolutePath(), null, message, job.retryCount);
            repository.addMessage(job.projectId, "assistant", buildFailureMessage(message), job.id);
        }
        listener.onJobChanged(job.projectId, job.id);
    }

    private String buildFailureMessage(String detail) {
        return context.getString(com.androidbuilder.R.string.build_summary_failed, firstDetailLine(detail));
    }

    private String localized(String chineseText, String englishText) {
        return AppSettings.isChinese(context) ? chineseText : englishText;
    }

    private String firstDetailLine(String detail) {
        if (detail != null) {
            String[] lines = detail.split("\\r?\\n");
            for (String line : lines) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    return trimmed.length() > 240 ? trimmed.substring(0, 240).trim() + "..." : trimmed;
                }
            }
        }
        return context.getString(com.androidbuilder.R.string.build_summary_detail_missing);
    }

    private void initializeLayout(File log) throws Exception {
        runtime.initializeLayout();
        runtime.ensureAndroidSdkWrappers();
        runtime.ensureAndroidSdkMetadata();
        runtime.ensureAndroidSdkLicenses();
        runtime.ensureGradleLauncherWrapper();
        FileUtils.appendText(log, "Embedded runtime root: " + runtime.root().getAbsolutePath() + "\n");
        FileUtils.appendText(log, runtime.sdkSummary());
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
        String packageName = inferPackageName(sourceWorkDir);
        ensureGradlePluginConfiguration(sourceWorkDir);
        rewriteAndroidBuildFiles(sourceWorkDir);
        ensureAndroidNamespace(sourceWorkDir, packageName);
        rewriteAndroidManifests(sourceWorkDir);

        File gradleProperties = new File(sourceWorkDir, "gradle.properties");
        String existing = gradleProperties.exists() ? FileUtils.readText(gradleProperties) : "";
        FileUtils.writeText(gradleProperties, AndroidGradleNormalizer.normalizeGradleProperties(
                existing,
                projectUsesAndroidXDependencies(sourceWorkDir),
                new File(runtime.bin(), "aapt2").getAbsolutePath()));
    }

    private void ensureGradlePluginConfiguration(File sourceWorkDir) throws Exception {
        File settings = new File(sourceWorkDir, "settings.gradle");
        if (settings.exists()) {
            FileUtils.writeText(settings, AndroidGradleNormalizer.ensureSettingsPluginManagement(FileUtils.readText(settings)));
        }
        File rootBuild = new File(sourceWorkDir, "build.gradle");
        String existing = rootBuild.exists() ? FileUtils.readText(rootBuild) : "";
        FileUtils.writeText(rootBuild, AndroidGradleNormalizer.ensureRootAndroidApplicationPlugin(existing));
    }

    /**
     * Registers a cheap task that resolves the app's debug runtime classpath. Running it before
     * assembleDebug surfaces "Could not find group:artifact:version" within seconds, which the
     * failure classifier maps to a model-repairable dependency substitution.
     */
    private File dependencyResolutionPreflightScript(File sourceWorkDir) throws Exception {
        File init = new File(sourceWorkDir, "androidbuilder-resolve-deps.gradle");
        FileUtils.writeText(init,
                "allprojects { project ->\n" +
                        "    project.tasks.register('abResolveDebugDeps') {\n" +
                        "        doLast {\n" +
                        "            def configuration = project.configurations.findByName('debugRuntimeClasspath')\n" +
                        "            if (configuration != null) {\n" +
                        "                configuration.resolvedConfiguration.rethrowFailure()\n" +
                        "            }\n" +
                        "        }\n" +
                        "    }\n" +
                        "}\n");
        return init;
    }

    private File dependencyInitScript(File sourceWorkDir) throws Exception {
        if (!BuildBackendSettings.DEPENDENCY_LOCAL_CACHE.equals(BuildBackendSettings.dependencyMode(context))) {
            return null;
        }
        File offlineMaven = BuildBackendSettings.offlineMavenDir(context);
        if (!offlineMaven.exists()) {
            return null;
        }
        File init = new File(sourceWorkDir, "androidbuilder-offline-maven.gradle");
        String repo = offlineMaven.getAbsolutePath().replace("\\", "\\\\").replace("'", "\\'");
        FileUtils.writeText(init,
                "allprojects {\n" +
                        "    repositories { maven { url uri('" + repo + "') }; " +
                        "maven { url 'https://maven.aliyun.com/repository/google' }; " +
                        "maven { url 'https://maven.aliyun.com/repository/public' }; " +
                        "maven { url 'https://maven.aliyun.com/repository/gradle-plugin' }; " +
                        "google(); mavenCentral() }\n" +
                        "}\n" +
                        "settingsEvaluated { settings ->\n" +
                        "    settings.pluginManagement.repositories { maven { url = uri('" + repo + "') }; " +
                        "maven { url 'https://maven.aliyun.com/repository/google' }; " +
                        "maven { url 'https://maven.aliyun.com/repository/public' }; " +
                        "maven { url 'https://maven.aliyun.com/repository/gradle-plugin' }; " +
                        "google(); gradlePluginPortal(); mavenCentral() }\n" +
                        "}\n");
        return init;
    }

    private void rewriteAndroidBuildFiles(File dir) throws Exception {
        if (dir == null || !dir.exists()) {
            return;
        }
        if (dir.isFile()) {
            String name = dir.getName();
            if ("build.gradle".equals(name) || "build.gradle.kts".equals(name)) {
                rewriteAndroidBuildFile(dir);
            }
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            rewriteAndroidBuildFiles(child);
        }
    }

    private void rewriteAndroidManifests(File dir) throws Exception {
        if (dir == null || !dir.exists()) {
            return;
        }
        if (dir.isFile()) {
            if ("AndroidManifest.xml".equals(dir.getName())) {
                String manifest = FileUtils.readText(dir);
                String rewritten = manifest.replaceFirst("(<manifest\\b[^>]*?)\\s+package\\s*=\\s*[\"'][^\"']+[\"']", "$1");
                if (!manifest.equals(rewritten)) {
                    FileUtils.writeText(dir, rewritten);
                }
            }
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            rewriteAndroidManifests(child);
        }
    }

    private String inferPackageName(File sourceWorkDir) throws Exception {
        String manifestPackage = manifestPackage(new File(sourceWorkDir, "app/src/main/AndroidManifest.xml"));
        if (isPackageName(manifestPackage)) {
            return manifestPackage;
        }
        String gradlePackage = gradleApplicationId(new File(sourceWorkDir, "app/build.gradle"));
        if (!isPackageName(gradlePackage)) {
            gradlePackage = gradleApplicationId(new File(sourceWorkDir, "app/build.gradle.kts"));
        }
        if (isPackageName(gradlePackage)) {
            return gradlePackage;
        }
        String sourcePackage = sourcePackage(sourceWorkDir);
        if (isPackageName(sourcePackage)) {
            return sourcePackage;
        }
        return "com.generated.app";
    }

    private String manifestPackage(File manifestFile) throws Exception {
        if (!manifestFile.exists()) {
            return "";
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("<manifest\\b[^>]*\\s+package\\s*=\\s*[\"']([^\"']+)[\"']")
                .matcher(FileUtils.readText(manifestFile));
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private String gradleApplicationId(File buildFile) throws Exception {
        if (!buildFile.exists()) {
            return "";
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\\bapplicationId\\s*(?:=\\s*)?[\"']([^\"']+)[\"']")
                .matcher(FileUtils.readText(buildFile));
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private String sourcePackage(File dir) throws Exception {
        if (dir == null || !dir.exists()) {
            return "";
        }
        if (dir.isFile()) {
            String name = dir.getName();
            if (!name.endsWith(".kt") && !name.endsWith(".java")) {
                return "";
            }
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("(?m)^\\s*package\\s+([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+)\\s*$")
                    .matcher(FileUtils.readText(dir));
            return matcher.find() ? matcher.group(1).trim() : "";
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return "";
        }
        for (File child : children) {
            String found = sourcePackage(child);
            if (isPackageName(found)) {
                return found;
            }
        }
        return "";
    }

    private void ensureAndroidNamespace(File sourceWorkDir, String packageName) throws Exception {
        if (!isPackageName(packageName)) {
            return;
        }
        File buildFile = new File(sourceWorkDir, "app/build.gradle");
        boolean kotlinDsl = false;
        if (!buildFile.exists()) {
            buildFile = new File(sourceWorkDir, "app/build.gradle.kts");
            kotlinDsl = true;
        }
        if (!buildFile.exists()) {
            return;
        }
        String build = FileUtils.readText(buildFile);
        if (!build.contains("android {") || build.matches("(?s).*\\bnamespace\\s*(=\\s*)?[\"'][^\"']+[\"'].*")) {
            return;
        }
        String namespaceLine = kotlinDsl
                ? "\n    namespace = \"" + packageName + "\""
                : "\n    namespace '" + packageName + "'";
        int androidIndex = build.indexOf("android {");
        int openBrace = build.indexOf('{', androidIndex);
        if (openBrace >= 0) {
            build = build.substring(0, openBrace + 1) + namespaceLine + build.substring(openBrace + 1);
            FileUtils.writeText(buildFile, build);
        }
    }

    private boolean isPackageName(String value) {
        return value != null && value.matches("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+");
    }

    private boolean projectUsesExternalDependencies(File dir) throws Exception {
        if (dir == null || !dir.exists()) {
            return false;
        }
        if (dir.isFile()) {
            String name = dir.getName();
            if ("build.gradle".equals(name) || "build.gradle.kts".equals(name)) {
                String text = FileUtils.readText(dir);
                return text.matches("(?s).*['\"][A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.+\\-]+['\"].*") ||
                        text.matches("(?s).*\\b(dataBinding|viewBinding|compose)\\s*(=\\s*)?true\\b.*");
            }
            return false;
        }
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (projectUsesExternalDependencies(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean projectUsesAndroidXDependencies(File dir) throws Exception {
        if (dir == null || !dir.exists()) {
            return false;
        }
        if (dir.isFile()) {
            String name = dir.getName();
            if (!"build.gradle".equals(name) && !"build.gradle.kts".equals(name)) {
                return false;
            }
            String text = FileUtils.readText(dir);
            return text.matches("(?s).*['\"]androidx\\.[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.+\\-]+['\"].*") ||
                    text.matches("(?s).*['\"]com\\.google\\.android\\.material:material:[A-Za-z0-9_.+\\-]+['\"].*");
        }
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (projectUsesAndroidXDependencies(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean canReachOnlineMaven() {
        return canReachMaven("https://dl.google.com/dl/android/maven2/") &&
                canReachMaven("https://repo.maven.apache.org/maven2/");
    }

    private boolean canReachMaven(String url) {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("HEAD");
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            int code = connection.getResponseCode();
            return code >= 200 && code < 500;
        } catch (Exception error) {
            return false;
        }
    }

    private void rewriteAndroidBuildFile(File buildFile) throws Exception {
        int api = runtime.compileSdkApi();
        String build = FileUtils.readText(buildFile);
        boolean kotlinDsl = buildFile.getName().endsWith(".kts");
        build = build.replaceAll("compileSdk\\s+\\d+", "compileSdk " + api);
        build = build.replaceAll("compileSdk\\s*=\\s*\\d+", "compileSdk = " + api);
        build = build.replaceAll("compileSdkVersion\\s+\\d+", "compileSdkVersion " + api);
        build = build.replaceAll("compileSdkVersion\\(\\s*\\d+\\s*\\)", "compileSdkVersion(" + api + ")");
        build = build.replaceAll("targetSdk\\s+\\d+", "targetSdk " + api);
        build = build.replaceAll("targetSdk\\s*=\\s*\\d+", "targetSdk = " + api);
        build = build.replaceAll("targetSdkVersion\\s+\\d+", "targetSdkVersion " + api);
        build = build.replaceAll("targetSdkVersion\\(\\s*\\d+\\s*\\)", "targetSdkVersion(" + api + ")");
        build = disableBindingFeatures(build);
        build = normalizeJvmTargets(build, kotlinDsl);
        FileUtils.writeText(buildFile, build);
    }

    private String disableBindingFeatures(String build) {
        return build
                .replaceAll("(?m)^(\\s*dataBinding\\s+)true(\\s*)$", "$1false$2")
                .replaceAll("(?m)^(\\s*viewBinding\\s+)true(\\s*)$", "$1false$2")
                .replaceAll("(?m)^(\\s*dataBinding\\s*=\\s*)true(\\s*)$", "$1false$2")
                .replaceAll("(?m)^(\\s*viewBinding\\s*=\\s*)true(\\s*)$", "$1false$2");
    }

    private String normalizeJvmTargets(String build, boolean kotlinDsl) {
        return AndroidGradleNormalizer.normalizeJvmTargets(build, kotlinDsl);
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
        env.put("ANDROID_USER_HOME", new File(runtime.home(), ".android").getAbsolutePath());
        env.put("ANDROID_SDK_HOME", runtime.home().getAbsolutePath());
        env.put("JAVA_HOME", new File(runtime.usr(), "lib/jvm/java-21-openjdk").getAbsolutePath());
        env.put("GRADLE_USER_HOME", new File(runtime.home(), ".gradle").getAbsolutePath());
        env.put("TMPDIR", runtime.tmp().getAbsolutePath());
        env.put("LANG", "C.UTF-8");
        env.put("TERM", "dumb");
        String javaProcessOptions = "-Duser.home=" + runtime.home().getAbsolutePath() +
                " -Djava.io.tmpdir=" + runtime.tmp().getAbsolutePath() +
                " -Djdk.lang.Process.launchMechanism=VFORK" +
                " -Dfile.encoding=UTF-8";
        env.put("JAVA_TOOL_OPTIONS", javaProcessOptions);
        env.put("JDK_JAVA_OPTIONS", javaProcessOptions);
        env.remove("JAVA_OPTS");
        env.remove("GRADLE_OPTS");
        env.remove("_JAVA_OPTIONS");
        env.put("LD_LIBRARY_PATH", new File(runtime.usr(), "lib").getAbsolutePath() + ":" +
                new File(runtime.usr(), "lib/jvm/java-21-openjdk/lib").getAbsolutePath() + ":" +
                new File(runtime.usr(), "lib/jvm/java-21-openjdk/lib/server").getAbsolutePath());
        env.put("PATH", runtime.bin().getAbsolutePath() + ":" + env.getOrDefault("PATH", ""));
        new File(runtime.home(), ".gradle").mkdirs();
        new File(runtime.home(), ".android").mkdirs();
        runtime.tmp().mkdirs();
        builder.environment().clear();
        builder.environment().putAll(env);
        Process process = builder.start();
        StringBuilder head = new StringBuilder();
        StringBuilder tail = new StringBuilder();
        StringBuilder failureContext = new StringBuilder();
        long lastNotifyAt = 0;
        int contextLines = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            AtomicLong lastOutputAt = new AtomicLong(System.currentTimeMillis());
            AtomicBoolean timedOut = new AtomicBoolean(false);
            AtomicReference<String> timeoutMessage = new AtomicReference<>("");
            long startedAt = lastOutputAt.get();
            Thread watchdog = new Thread(() -> {
                while (process.isAlive() && !Thread.currentThread().isInterrupted()) {
                    long now = System.currentTimeMillis();
                    if (BuildTimeoutPolicy.exceededTotalTimeout(now, startedAt) ||
                            BuildTimeoutPolicy.exceededIdleTimeout(now, lastOutputAt.get())) {
                        timedOut.set(true);
                        timeoutMessage.set(BuildTimeoutPolicy.timeoutMessage(now, startedAt, lastOutputAt.get(), AppSettings.isChinese(context)));
                        destroyProcess(process);
                        return;
                    }
                    try {
                        Thread.sleep(1000L);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
            }, "embedded-build-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();
            String line;
            while ((line = reader.readLine()) != null) {
                lastOutputAt.set(System.currentTimeMillis());
                FileUtils.appendText(log, line + "\n");
                if (head.length() < 5000) {
                    head.append(line).append('\n');
                }
                tail.append(line).append('\n');
                if (tail.length() > 5000) {
                    tail.delete(0, tail.length() - 5000);
                }
                if (isImportantBuildLine(line)) {
                    contextLines = 28;
                }
                if (contextLines > 0) {
                    failureContext.append(line).append('\n');
                    if (failureContext.length() > 20000) {
                        failureContext.delete(0, failureContext.length() - 20000);
                    }
                    contextLines--;
                }
                long now = System.currentTimeMillis();
                if (now - lastNotifyAt > 600) {
                    lastNotifyAt = now;
                    listener.onJobChanged(job.projectId, job.id);
                }
            }
            int exitCode = process.waitFor();
            watchdog.interrupt();
            if (timedOut.get()) {
                String message = timeoutMessage.get();
                if (message == null || message.isEmpty()) {
                    message = BuildTimeoutPolicy.timeoutMessage(System.currentTimeMillis(), startedAt, lastOutputAt.get(), AppSettings.isChinese(context));
                }
                FileUtils.appendText(log, message + "\n");
                tail.append(message).append('\n');
                failureContext.append(message).append('\n');
                listener.onJobChanged(job.projectId, job.id);
                return new ProcessResult(124, head.toString().trim(), failureContext.toString().trim(), tail.toString().trim());
            }
            listener.onJobChanged(job.projectId, job.id);
            return new ProcessResult(exitCode, head.toString().trim(), failureContext.toString().trim(), tail.toString().trim());
        }
    }

    private void destroyProcess(Process process) {
        process.destroy();
        try {
            Thread.sleep(3000L);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        if (process.isAlive()) {
            process.destroyForcibly();
        }
    }

    private String summarizeFailure(String prefix, ProcessResult result) {
        String head = result.head == null ? "" : result.head.trim();
        String context = result.context == null ? "" : result.context.trim();
        String tail = result.tail == null ? "" : result.tail.trim();
        if (head.length() > 1800) {
            head = head.substring(0, 1800);
        }
        if (context.length() > 4200) {
            context = context.substring(context.length() - 4200);
        }
        if (tail.length() > 1800) {
            tail = tail.substring(tail.length() - 1800);
        }
        String javaDiagnostics = BuildLogContextExtractor.javaCompileDiagnostics(
                (result.head == null ? "" : result.head) + "\n" +
                        (result.context == null ? "" : result.context) + "\n" +
                        (result.tail == null ? "" : result.tail),
                7000);
        String missingFieldHints = BuildLogContextExtractor.missingFieldHints(
                (result.head == null ? "" : result.head) + "\n" +
                        (result.context == null ? "" : result.context) + "\n" +
                        (result.tail == null ? "" : result.tail));
        StringBuilder summary = new StringBuilder(prefix);
        if (!head.isEmpty()) {
            summary.append("\n\nFirst log:\n").append(head);
        }
        if (!missingFieldHints.isEmpty()) {
            summary.append("\n\nJava API consistency hints:\n").append(missingFieldHints);
        }
        if (!javaDiagnostics.isEmpty()) {
            summary.append("\n\nJava compile diagnostics:\n").append(javaDiagnostics);
        }
        if (!context.isEmpty() && !context.equals(head) && !context.equals(tail)) {
            summary.append("\n\nFailure context:\n").append(context);
        }
        if (!tail.isEmpty() && !tail.equals(head)) {
            summary.append("\n\nLast log:\n").append(tail);
        }
        return summary.toString();
    }

    private boolean isImportantBuildLine(String line) {
        if (line == null) {
            return false;
        }
        String lower = line.toLowerCase(java.util.Locale.ROOT);
        return line.contains(" FAILED") ||
                line.contains("* What went wrong") ||
                line.contains("Execution failed for task") ||
                line.contains("Manifest merger failed") ||
                line.contains("Namespace not specified") ||
                line.contains("Android resource linking failed") ||
                line.contains("Unresolved reference") ||
                line.contains("package=\"") ||
                lower.contains(" error:") ||
                lower.startsWith("error:") ||
                lower.startsWith("e: ");
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
        final String head;
        final String context;
        final String tail;

        ProcessResult(int exitCode, String head, String context, String tail) {
            this.exitCode = exitCode;
            this.head = head;
            this.context = context;
            this.tail = tail;
        }
    }
}
