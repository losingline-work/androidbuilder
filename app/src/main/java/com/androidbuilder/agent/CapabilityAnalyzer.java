package com.androidbuilder.agent;

import com.androidbuilder.backend.BuildBackendSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class CapabilityAnalyzer {
    private CapabilityAnalyzer() {
    }

    public static CapabilityAssessment assess(String text, String dependencyMode, boolean offlineCacheAvailable) {
        String value = text == null ? "" : text.toLowerCase(Locale.ROOT);
        List<String> risks = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();
        boolean externalLibraries = addExternalLibraryRisks(value, risks);
        boolean hardwareOrService = addPlatformRisks(value, risks);
        boolean blocks = false;

        if (BuildBackendSettings.DEPENDENCY_OFFLINE_SAFE.equals(dependencyMode) && externalLibraries) {
            blocks = true;
            suggestions.add("切换到“联网增强”，或把需求改写为仅使用 Android SDK、Kotlin、XML 和 SQLiteOpenHelper。");
        } else if (BuildBackendSettings.DEPENDENCY_LOCAL_CACHE.equals(dependencyMode) && externalLibraries && !offlineCacheAvailable) {
            blocks = true;
            risks.add("本计划需要外部库，但尚未导入本地 Maven 缓存。");
            suggestions.add("先导入 offline-maven.zip，或切换到“联网增强”。");
        } else if (BuildBackendSettings.DEPENDENCY_LOCAL_CACHE.equals(dependencyMode) && externalLibraries) {
            suggestions.add("确认 offline-maven.zip 包含计划中提到的库版本，否则构建会被依赖策略拦截。");
        }

        if (hardwareOrService) {
            suggestions.add("涉及设备能力或外部服务时，建议先生成最小可用版本，再分步增加权限、异常处理和验收测试。");
        }

        return new CapabilityAssessment(blocks, risks, suggestions);
    }

    private static boolean addExternalLibraryRisks(String value, List<String> risks) {
        boolean found = false;
        found |= addRiskIfPresent(value, risks, "room", "Room 需要 AndroidX/注解处理依赖，离线安全模式无法直接构建。");
        found |= addRiskIfPresent(value, risks, "compose", "Compose 需要额外 Gradle 插件和 Maven 依赖，当前默认生成链路不支持。");
        found |= addRiskIfPresent(value, risks, "retrofit", "Retrofit 需要外部 Maven 依赖。");
        found |= addRiskIfPresent(value, risks, "okhttp", "OkHttp 需要外部 Maven 依赖。");
        found |= addRiskIfPresent(value, risks, "material", "Material Components 需要外部 Maven 依赖。");
        found |= addRiskIfPresent(value, risks, "androidx", "AndroidX 库需要外部 Maven 依赖。");
        found |= addRiskIfPresent(value, risks, "viewbinding", "ViewBinding 需要 Android Gradle 生成绑定类，离线安全模式会拦截。");
        found |= addRiskIfPresent(value, risks, "databinding", "DataBinding 需要额外构建支持，离线安全模式会拦截。");
        found |= addRiskIfPresent(value, risks, "glide", "Glide 需要外部 Maven 依赖。");
        found |= addRiskIfPresent(value, risks, "coil", "Coil 需要外部 Maven 依赖。");
        return found;
    }

    private static boolean addPlatformRisks(String value, List<String> risks) {
        boolean found = false;
        found |= addRiskIfPresent(value, risks, "地图", "地图能力通常需要 SDK、密钥或在线服务。");
        found |= addRiskIfPresent(value, risks, "map", "Map features usually require an SDK, API key, or online service.");
        found |= addRiskIfPresent(value, risks, "支付", "支付能力需要平台 SDK、签名配置和服务端校验。");
        found |= addRiskIfPresent(value, risks, "payment", "Payment features require provider SDKs, signing setup, and server validation.");
        found |= addRiskIfPresent(value, risks, "蓝牙", "蓝牙能力需要权限、设备兼容处理和真机测试。");
        found |= addRiskIfPresent(value, risks, "bluetooth", "Bluetooth features require permissions, compatibility handling, and device testing.");
        found |= addRiskIfPresent(value, risks, "相机", "相机能力需要权限、文件共享配置和真机测试。");
        found |= addRiskIfPresent(value, risks, "camera", "Camera features require permissions, file sharing setup, and device testing.");
        return found;
    }

    private static boolean addRiskIfPresent(String value, List<String> risks, String keyword, String risk) {
        if (!value.contains(keyword)) {
            return false;
        }
        if (!risks.contains(risk)) {
            risks.add(risk);
        }
        return true;
    }
}
