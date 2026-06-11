package com.androidbuilder.agent;

import com.androidbuilder.backend.BuildBackendSettings;

import java.io.File;

public final class PlanConstraintComposer {
    private PlanConstraintComposer() {
    }

    public static String withPlanningConstraints(String requirement, String dependencyMode, boolean offlineCacheAvailable, boolean confirmRiskyChoices, boolean chinese) {
        String base = requirement == null ? "" : requirement.trim();
        String constraints = planningConstraints(dependencyMode, offlineCacheAvailable, confirmRiskyChoices, chinese);
        if (base.isEmpty()) {
            return constraints;
        }
        return base + "\n\n" + constraints;
    }

    public static String planningConstraints(String dependencyMode, boolean offlineCacheAvailable, boolean confirmRiskyChoices, boolean chinese) {
        if (chinese) {
            return chineseConstraints(dependencyMode, offlineCacheAvailable, confirmRiskyChoices);
        }
        return englishConstraints(dependencyMode, offlineCacheAvailable, confirmRiskyChoices);
    }

    public static String dependencyModeSummary(String dependencyMode, boolean offlineCacheAvailable, boolean chinese) {
        if (BuildBackendSettings.DEPENDENCY_LOCAL_CACHE.equals(dependencyMode)) {
            if (chinese) {
                return offlineCacheAvailable
                        ? "当前依赖模式：本地缓存。计划只能使用已导入本地 Maven 缓存中的依赖；不确定时会降级为 Android SDK 实现或列为待确认项。"
                        : "当前依赖模式：本地缓存，但还没有可用的本地 Maven 缓存。计划会降级为 Android SDK 实现或要求确认。";
            }
            return offlineCacheAvailable
                    ? "Current dependency mode: Local cache. Plans may use only dependencies already imported into the local Maven cache; uncertain features fall back to Android SDK options or Pending User Choices."
                    : "Current dependency mode: Local cache, but no local Maven cache is available. Plans should fall back to Android SDK options or ask for confirmation.";
        }
        if (BuildBackendSettings.DEPENDENCY_ONLINE.equals(dependencyMode)) {
            return chinese
                    ? "当前依赖模式：联网增强。计划可以使用已批准且固定版本的联网依赖；涉及外部服务、权限或多种产品路线时会列为待确认项。"
                    : "Current dependency mode: Online enhanced. Plans may use approved pinned dependencies; external services, permissions, or product-route choices should be listed as Pending User Choices.";
        }
        return chinese
                ? "当前依赖模式：离线安全。计划会优先使用 Android SDK + Java + XML + SQLiteOpenHelper，不规划外部 Maven 依赖。"
                : "Current dependency mode: Offline safe. Plans use Android SDK + Java + XML + SQLiteOpenHelper and do not plan external Maven dependencies.";
    }

    public static boolean offlineCacheAvailable(File offlineMavenDir) {
        if (offlineMavenDir == null || !offlineMavenDir.isDirectory()) {
            return false;
        }
        File[] children = offlineMavenDir.listFiles();
        return children != null && children.length > 0;
    }

    private static String englishConstraints(String dependencyMode, boolean offlineCacheAvailable, boolean confirmRiskyChoices) {
        StringBuilder text = new StringBuilder("Planning constraints set by the current dependency mode:\n");
        if (BuildBackendSettings.DEPENDENCY_LOCAL_CACHE.equals(dependencyMode)) {
            text.append("- Current dependency mode: Local cache.\n")
                    .append("- Plan only dependencies that are already present in the imported local Maven cache. Do not invent or download new Maven dependencies.\n");
            if (!offlineCacheAvailable) {
                text.append("- No local Maven cache is available, so fall back to Android SDK + Java + XML + SQLiteOpenHelper unless the user changes the dependency mode.\n");
            }
        } else if (BuildBackendSettings.DEPENDENCY_ONLINE.equals(dependencyMode)) {
            text.append("- Current dependency mode: Online enhanced.\n")
                    .append("- You may plan approved dependencies when they are necessary, but use pinned versions and keep the implementation Java + XML. Do not plan Kotlin, Compose, DataBinding, or ViewBinding.\n");
        } else {
            text.append("- Current dependency mode: Offline safe.\n")
                    .append("- Plan Android SDK + Java + XML + SQLiteOpenHelper implementations only.\n")
                    .append("- Do not plan Room, Compose, Material Components, Retrofit, OkHttp, Glide, AndroidX additions, annotation processors, Gradle plugins, or any new Maven dependency.\n");
        }
        if (confirmRiskyChoices) {
            text.append("- When a feature has multiple product routes or needs external services, permissions, hardware, maps, payments, cloud sync, accounts, push messaging, or library-backed media loading, include a Pending User Choices section with the recommended dependency-mode-safe default. Do not directly choose a route that the dependency or source guard will block.\n");
        }
        return text.toString().trim();
    }

    private static String chineseConstraints(String dependencyMode, boolean offlineCacheAvailable, boolean confirmRiskyChoices) {
        StringBuilder text = new StringBuilder("计划前约束（由当前依赖模式自动设置）：\n");
        if (BuildBackendSettings.DEPENDENCY_LOCAL_CACHE.equals(dependencyMode)) {
            text.append("- 当前依赖模式：本地缓存。\n")
                    .append("- 只能规划已导入本地 Maven 缓存中的依赖，不要发明或下载新的 Maven 依赖。\n");
            if (!offlineCacheAvailable) {
                text.append("- 当前没有可用的本地 Maven 缓存，因此默认退回 Android SDK + Java + XML + SQLiteOpenHelper 实现，除非用户切换依赖模式。\n");
            }
        } else if (BuildBackendSettings.DEPENDENCY_ONLINE.equals(dependencyMode)) {
            text.append("- 当前依赖模式：联网增强。\n")
                    .append("- 必要时可以规划已批准且固定版本的依赖，但实现仍保持 Java + XML；不要规划 Kotlin、Compose、DataBinding 或 ViewBinding。\n");
        } else {
            text.append("- 当前依赖模式：离线安全。\n")
                    .append("- 必须规划为 Android SDK + Java + XML + SQLiteOpenHelper 本地实现。\n")
                    .append("- 不要规划 Room、Compose、Material Components、Retrofit、OkHttp、Glide、新增 AndroidX、注解处理器、Gradle 插件或任何新的 Maven 依赖。\n");
        }
        if (confirmRiskyChoices) {
            text.append("- 如果某个功能存在多种产品路线，或需要外部服务、权限、硬件、地图、支付、云同步、账号、推送、依赖库图片加载等能力，请在计划中加入“待确认项”，并给出符合当前依赖模式的推荐默认方案；不要直接选择会被依赖或源码守卫拦截的路线。\n");
        }
        return text.toString().trim();
    }
}
