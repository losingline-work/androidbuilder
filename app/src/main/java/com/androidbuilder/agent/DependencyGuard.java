package com.androidbuilder.agent;

import com.androidbuilder.backend.BuildBackendSettings;
import com.androidbuilder.model.FileOperation;
import com.androidbuilder.model.TaskOperations;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DependencyGuard {
    private static final Pattern QUOTED_COORDINATE = Pattern.compile("[\"']([A-Za-z0-9_.-]+):([A-Za-z0-9_.-]+):([A-Za-z0-9_.+\\-]+)[\"']");
    private static final Pattern PLUGIN_ID = Pattern.compile("id\\s*(?:\\(\\s*)?[\"']([^\"']+)[\"']");
    private static final Set<String> ALLOWED_PLUGINS = new HashSet<>(Arrays.asList(
            "com.android.application"
    ));
    private static final Set<String> ALLOWED_BUILD_TOOLING = new HashSet<>(Arrays.asList(
            "com.android.tools.build:gradle"
    ));

    private final String mode;
    private final File offlineMavenDir;

    public DependencyGuard(String mode, File offlineMavenDir) {
        this.mode = mode == null ? BuildBackendSettings.DEPENDENCY_OFFLINE_SAFE : mode;
        this.offlineMavenDir = offlineMavenDir;
    }

    public void validate(TaskOperations taskOperations) {
        for (FileOperation operation : taskOperations.operations) {
            if ("delete".equals(operation.action)) {
                continue;
            }
            validateContent(operation.path, operation.content == null ? "" : operation.content);
        }
    }

    public void validateContent(String path, String content) {
        String lowerPath = path == null ? "" : path.toLowerCase();
        if (lowerPath.endsWith(".gradle") || lowerPath.endsWith(".gradle.kts")) {
            validateGradle(content);
        }
        if (lowerPath.endsWith(".kt") || lowerPath.endsWith(".java")) {
            validateSourceImports(content);
        }
    }

    private void validateGradle(String content) {
        rejectKotlinGradleConfiguration(content);
        if (BuildBackendSettings.DEPENDENCY_ONLINE.equals(mode)) {
            rejectOnlineBindingOrCompose(content);
        }
        if (BuildBackendSettings.DEPENDENCY_OFFLINE_SAFE.equals(mode)) {
            rejectBindingOrCompose(content);
        }
        if (BuildBackendSettings.DEPENDENCY_LOCAL_CACHE.equals(mode) && usesBindingOrCompose(content) && !hasAnyLocalArtifact("androidx.databinding", "viewbinding")) {
            throw new IllegalArgumentException("Dependency policy blocked dataBinding/viewBinding because offline-maven.zip does not contain androidx.databinding:viewbinding.");
        }
        Matcher pluginMatcher = PLUGIN_ID.matcher(content);
        while (pluginMatcher.find()) {
            String plugin = pluginMatcher.group(1);
            if (!ALLOWED_PLUGINS.contains(plugin)) {
                throw new IllegalArgumentException("Dependency policy blocked Gradle plugin: " + plugin);
            }
        }
        Matcher dependencyMatcher = QUOTED_COORDINATE.matcher(content);
        while (dependencyMatcher.find()) {
            String group = dependencyMatcher.group(1);
            String name = dependencyMatcher.group(2);
            String version = dependencyMatcher.group(3);
            if (isAllowedBuildTooling(group, name)) {
                continue;
            }
            if (BuildBackendSettings.DEPENDENCY_ONLINE.equals(mode) && !OnlineDependencyPolicy.isApproved(group, name, version)) {
                throw new IllegalArgumentException("Dependency policy blocked unapproved online Maven dependency: " + group + ":" + name + ":" + version);
            }
            if (BuildBackendSettings.DEPENDENCY_OFFLINE_SAFE.equals(mode)) {
                throw new IllegalArgumentException("Dependency policy blocked Maven dependency: " + group + ":" + name + ":" + version);
            }
            if (BuildBackendSettings.DEPENDENCY_LOCAL_CACHE.equals(mode) && !hasLocalArtifact(group, name, version)) {
                throw new IllegalArgumentException("Dependency policy blocked missing local Maven artifact: " + group + ":" + name + ":" + version);
            }
        }
    }

    private void rejectKotlinGradleConfiguration(String content) {
        if (content == null) {
            return;
        }
        if (content.matches("(?s).*\\bkotlinOptions\\s*\\{.*")
                || content.contains("org.jetbrains.kotlin.android")
                || content.contains("kotlin-android")
                || content.contains("org.jetbrains.kotlin:kotlin-gradle-plugin")
                || content.matches("(?s).*\\bkotlin\\s*\\(\\s*[\"']android[\"']\\s*\\).*")) {
            throw new IllegalArgumentException("Dependency policy blocked Kotlin Gradle configuration. Use Java source files and compileOptions only.");
        }
    }

    private void validateSourceImports(String content) {
        if (BuildBackendSettings.DEPENDENCY_ONLINE.equals(mode)) {
            return;
        }
        String blocked = firstBlockedImport(content);
        if (blocked != null) {
            throw new IllegalArgumentException("Dependency policy blocked source import: " + blocked);
        }
    }

    private void rejectBindingOrCompose(String content) {
        if (usesBindingOrCompose(content)) {
            throw new IllegalArgumentException("Dependency policy blocked dataBinding/viewBinding/Compose. Use findViewById and plain XML.");
        }
    }

    private void rejectOnlineBindingOrCompose(String content) {
        if (usesBindingOrCompose(content)) {
            throw new IllegalArgumentException("Dependency policy blocked dataBinding/viewBinding. Use findViewById and plain XML.");
        }
    }

    private boolean usesBindingOrCompose(String content) {
        return content.matches("(?s).*\\b(dataBinding|viewBinding|compose)\\s*(=\\s*)?true\\b.*") ||
                content.contains("buildFeatures { compose") ||
                content.contains("composeOptions");
    }

    private String firstBlockedImport(String content) {
        String[] prefixes = {
                "import androidx.core",
                "import androidx.databinding",
                "import androidx.compose",
                "import androidx.room",
                "import retrofit2",
                "import okhttp3",
                "import com.google.android.material"
        };
        for (String prefix : prefixes) {
            if (content.contains(prefix)) {
                return prefix.replace("import ", "");
            }
        }
        if (content.matches("(?s).*import\\s+.*\\.databinding\\..*Binding.*")) {
            return "*.databinding.*Binding";
        }
        return null;
    }

    private boolean isAllowedBuildTooling(String group, String name) {
        return ALLOWED_BUILD_TOOLING.contains(group + ":" + name);
    }

    private boolean hasAnyLocalArtifact(String group, String name) {
        File groupDir = new File(offlineMavenDir, group.replace('.', '/') + "/" + name);
        File[] versions = groupDir.listFiles();
        return versions != null && versions.length > 0;
    }

    private boolean hasLocalArtifact(String group, String name, String version) {
        File versionDir = new File(offlineMavenDir, group.replace('.', '/') + "/" + name + "/" + version);
        return new File(versionDir, name + "-" + version + ".aar").exists() ||
                new File(versionDir, name + "-" + version + ".jar").exists() ||
                new File(versionDir, name + "-" + version + ".pom").exists();
    }
}
