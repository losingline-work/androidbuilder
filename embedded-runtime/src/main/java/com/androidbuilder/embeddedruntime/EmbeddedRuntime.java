package com.androidbuilder.embeddedruntime;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class EmbeddedRuntime {
    private final File root;

    public EmbeddedRuntime(Context context) {
        this.root = new File(context.getFilesDir(), "runtime");
    }

    public File root() {
        return root;
    }

    public File home() {
        return new File(root, "home");
    }

    public File tmp() {
        return new File(home(), "tmp");
    }

    public File usr() {
        return new File(root, "usr");
    }

    public File bin() {
        return new File(usr(), "bin");
    }

    public File androidSdk() {
        return new File(usr(), "android-sdk");
    }

    public File androidSdkLicenses() {
        return new File(androidSdk(), "licenses");
    }

    public File buildTools() {
        return new File(androidSdk(), "build-tools/35.0.0");
    }

    public File buildToolsForApi(int api) {
        return new File(androidSdk(), "build-tools/" + api + ".0.0");
    }

    public int compileSdkApi() {
        int api = highestInstalledPlatformApi();
        return api > 0 ? api : 34;
    }

    public File work(long projectId, long jobId) {
        return new File(root, "work/" + projectId + "/" + jobId);
    }

    public File licenses() {
        return new File(root, "licenses");
    }

    public void initializeLayout() throws IOException {
        mkdir(root());
        mkdir(home());
        mkdir(tmp());
        mkdir(usr());
        mkdir(bin());
        mkdir(androidSdk());
        mkdir(androidSdkLicenses());
        mkdir(buildTools());
        mkdir(licenses());
        ensureAndroidSdkMetadata();
        ensureAndroidSdkLicenses();
        File notice = new File(licenses(), "TERMUX-GPLV3-NOTICE.txt");
        if (!notice.exists()) {
            writeText(notice, "This runtime is prepared for GPLv3-compatible Termux components. If GPLv3 code or binaries are bundled or distributed, provide corresponding source and preserve license notices.\n");
        }
        ensureGradleLauncherWrapper();
    }

    public void installBootstrap(InputStream zipStream) throws IOException {
        initializeLayout();
        try (ZipInputStream zip = new ZipInputStream(zipStream)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = normalizeEntryName(entry.getName());
                if (name.isEmpty()) {
                    continue;
                }
                File target = targetForEntry(name);
                if (!isInside(root(), target)) {
                    throw new IOException("Unsafe bootstrap entry: " + entry.getName());
                }
                File parent = target.getParentFile();
                if (parent != null) {
                    mkdir(parent);
                }
                try (FileOutputStream out = new FileOutputStream(target)) {
                    copy(zip, out);
                }
                if (isExecutablePath(target)) {
                    target.setExecutable(true, false);
                }
            }
        }
        ensureAndroidSdkWrappers();
        ensureAndroidSdkMetadata();
        ensureAndroidSdkLicenses();
        ensureGradleLauncherWrapper();
    }

    public boolean hasMinimumTools() {
        return missingTools().isEmpty();
    }

    public List<String> missingTools() {
        List<String> missing = new ArrayList<>();
        if (!new File(bin(), "gradle").exists()) {
            missing.add("usr/bin/gradle");
        }
        if (!new File(bin(), "java").exists()) {
            missing.add("usr/bin/java");
        }
        if (!new File(bin(), "aapt2").exists()) {
            missing.add("usr/bin/aapt2");
        }
        if (highestInstalledPlatformApi() == 0) {
            missing.add("usr/android-sdk/platforms/android-XX/android.jar");
        }
        return missing;
    }

    public String sdkSummary() {
        File platforms = new File(androidSdk(), "platforms");
        StringBuilder summary = new StringBuilder();
        summary.append("Android SDK root: ").append(androidSdk().getAbsolutePath()).append('\n');
        summary.append("Installed platforms: ");
        File[] entries = platforms.listFiles(file -> file.isDirectory() && file.getName().startsWith("android-"));
        if (entries == null || entries.length == 0) {
            summary.append("(none)");
        } else {
            boolean first = true;
            for (File entry : entries) {
                if (!first) {
                    summary.append(", ");
                }
                first = false;
                summary.append(entry.getName());
                summary.append(new File(entry, "android.jar").exists() ? "[android.jar]" : "[missing android.jar]");
                summary.append(new File(entry, "source.properties").exists() ? "[source.properties]" : "[missing source.properties]");
                summary.append(new File(entry, "package.xml").exists() ? "[package.xml]" : "[missing package.xml]");
                summary.append(new File(entry, "build.prop").exists() ? "[build.prop]" : "[missing build.prop]");
                summary.append(new File(entry, "core-for-system-modules.jar").exists() ? "[core-for-system-modules.jar]" : "[missing core-for-system-modules.jar]");
            }
        }
        summary.append('\n');
        summary.append("Selected compileSdk: ").append(compileSdkApi()).append('\n');
        File selectedBuildTools = buildToolsForApi(compileSdkApi());
        summary.append("Selected build-tools: ").append(selectedBuildTools.getAbsolutePath());
        summary.append(selectedBuildTools.exists() ? "[exists]" : "[missing]");
        summary.append(new File(selectedBuildTools, "aidl").exists() ? "[aidl]" : "[missing aidl]");
        summary.append(new File(selectedBuildTools, "package.xml").exists() ? "[package.xml]" : "[missing package.xml]");
        summary.append('\n');
        return summary.toString();
    }

    public String statusText() {
        List<String> missing = missingTools();
        if (missing.isEmpty()) {
            return "Embedded runtime ready / 内置运行环境已就绪: " + root().getAbsolutePath();
        }
        return "Base runtime installed, Android build toolchain incomplete.\n" +
                "基础运行环境已安装，但 Android 构建工具链不完整。\n" +
                "Missing / 缺少: " + missing + "\n" +
                "Root: " + root().getAbsolutePath();
    }

    public void ensureAndroidSdkWrappers() throws IOException {
        int api = compileSdkApi();
        ensureBuildToolsDirectory(buildTools(), 35);
        ensureBuildToolsDirectory(buildToolsForApi(api), api);
        ensureBuildToolsDirectory(buildToolsForApi(34), 34);
    }

    public void ensureAndroidSdkMetadata() throws IOException {
        File platforms = new File(androidSdk(), "platforms");
        File[] entries = platforms.listFiles(file -> file.isDirectory() && file.getName().startsWith("android-"));
        File fallbackPlatform = highestInstalledPlatform();
        if (fallbackPlatform != null && !new File(platforms, "android-34/android.jar").exists()) {
            ensurePlatformAlias(fallbackPlatform, 34);
        }
        entries = platforms.listFiles(file -> file.isDirectory() && file.getName().startsWith("android-"));
        if (entries != null) {
            for (File entry : entries) {
                File androidJar = new File(entry, "android.jar");
                if (!androidJar.exists()) {
                    continue;
                }
                int api = apiFromPlatformDir(entry);
                if (api <= 0) {
                    continue;
                }
                File sourceProperties = new File(entry, "source.properties");
                if (!sourceProperties.exists()) {
                    writeText(sourceProperties,
                            "Pkg.Desc=Android SDK Platform " + api + "\n" +
                                    "Pkg.UserSrc=false\n" +
                                    "Platform.Version=" + api + "\n" +
                                    "AndroidVersion.ApiLevel=" + api + "\n" +
                                    "Pkg.Revision=1\n");
                }
                ensurePlatformBuildProperties(entry, api);
                ensurePlatformPackageXml(entry, api);
                ensurePlatformCoreForSystemModules(entry);
            }
        }
        ensureBuildToolsMetadata(buildTools(), 35);
        int api = compileSdkApi();
        ensureBuildToolsMetadata(buildToolsForApi(api), api);
        ensureBuildToolsMetadata(buildToolsForApi(34), 34);
        ensureAndroidSdkLicenses();
    }

    public void ensureAndroidSdkLicenses() throws IOException {
        mkdir(androidSdkLicenses());
        writeText(new File(androidSdkLicenses(), "android-sdk-license"),
                "24333f8a63b6825ea9c5514f83c2829b004d1fee\n" +
                        "8933bad161af4178b1185d1a37fbf41ea5269c55\n" +
                        "d56f5187479451eabf01fb78af6dfcb131a6481e\n");
        writeText(new File(androidSdkLicenses(), "android-sdk-preview-license"),
                "84831b9409646a918e30573bab4c9c91346d8abd\n");
    }

    private void ensureBuildToolsDirectory(File dir, int api) throws IOException {
        mkdir(dir);
        writeWrapperIfToolExists("aapt2", dir);
        writeWrapperIfToolExists("aapt", dir);
        writeWrapperIfToolExists("aidl", dir);
        writeWrapperIfToolExists("d8", dir);
        writeWrapperIfToolExists("dexdump", dir);
        writeWrapperIfToolExists("zipalign", dir);
        writeWrapperIfToolExists("apksigner", dir);
        writeWrapperIfToolExists("split-select", dir);
        writeWrapperIfToolExists("bcc_compat", dir);
        writeWrapperIfToolExists("lld", dir);
        writeWrapperIfToolExists("llvm-rs-cc", dir);
        ensureCoreLambdaStubs(dir);
        ensureBuildToolsMetadata(dir, api);
        ensureBuildToolsPackageXml(dir, api);
    }

    private void ensureCoreLambdaStubs(File dir) throws IOException {
        File stubs = new File(dir, "core-lambda-stubs.jar");
        if (stubs.exists()) {
            return;
        }
        try (FileOutputStream out = new FileOutputStream(stubs)) {
            out.write(new byte[]{
                    0x50, 0x4b, 0x05, 0x06,
                    0x00, 0x00, 0x00, 0x00,
                    0x00, 0x00, 0x00, 0x00,
                    0x00, 0x00, 0x00, 0x00,
                    0x00, 0x00, 0x00, 0x00,
                    0x00, 0x00
            });
        }
    }

    private void ensureBuildToolsMetadata(File dir, int api) throws IOException {
        mkdir(dir);
        File buildToolsProperties = new File(dir, "source.properties");
        writeText(buildToolsProperties,
                "Pkg.Desc=Android SDK Build-Tools " + api + "\n" +
                        "Pkg.Revision=" + api + ".0.0\n");
    }

    private void ensureBuildToolsPackageXml(File dir, int api) throws IOException {
        File packageXml = new File(dir, "package.xml");
        if (packageXml.exists()) {
            return;
        }
        writeText(packageXml, "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                "<ns2:repository xmlns:ns2=\"http://schemas.android.com/repository/android/common/02\" " +
                "xmlns:ns5=\"http://schemas.android.com/repository/android/generic/02\">\n" +
                "  <localPackage path=\"build-tools;" + api + ".0.0\" obsolete=\"false\">\n" +
                "    <type-details xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:type=\"ns5:genericDetailsType\"/>\n" +
                "    <revision>\n" +
                "      <major>" + api + "</major>\n" +
                "      <minor>0</minor>\n" +
                "      <micro>0</micro>\n" +
                "    </revision>\n" +
                "    <display-name>Android SDK Build-Tools " + api + "</display-name>\n" +
                "  </localPackage>\n" +
                "</ns2:repository>\n");
    }

    private void ensurePlatformBuildProperties(File dir, int api) throws IOException {
        File buildProperties = new File(dir, "build.prop");
        if (buildProperties.exists()) {
            return;
        }
        String release = androidReleaseName(api);
        writeText(buildProperties,
                "ro.build.version.sdk=" + api + "\n" +
                        "ro.build.version.codename=REL\n" +
                        "ro.build.version.release=" + release + "\n" +
                        "ro.build.version.release_or_codename=" + release + "\n" +
                        "ro.build.version.preview_sdk=0\n" +
                        "ro.build.version.preview_sdk_fingerprint=REL\n" +
                        "ro.build.version.all_codenames=REL\n" +
                        "ro.product.cpu.abi=arm64-v8a\n" +
                        "ro.product.cpu.abi2=\n" +
                        "ro.product.cpu.abilist=arm64-v8a,armeabi-v7a,armeabi\n" +
                        "ro.product.cpu.abilist32=armeabi-v7a,armeabi\n" +
                        "ro.product.cpu.abilist64=arm64-v8a\n" +
                        "ro.product.brand=Android\n" +
                        "ro.product.manufacturer=Android\n" +
                        "ro.product.device=generic\n" +
                        "ro.build.product=generic\n");
    }

    private void ensurePlatformPackageXml(File dir, int api) throws IOException {
        File packageXml = new File(dir, "package.xml");
        if (packageXml.exists()) {
            return;
        }
        writeText(packageXml, "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                "<ns2:repository xmlns:ns2=\"http://schemas.android.com/repository/android/common/02\" " +
                "xmlns:ns11=\"http://schemas.android.com/sdk/android/repo/repository2/03\">\n" +
                "  <localPackage path=\"platforms;android-" + api + "\" obsolete=\"false\">\n" +
                "    <type-details xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:type=\"ns11:platformDetailsType\">\n" +
                "      <api-level>" + api + "</api-level>\n" +
                "      <codename></codename>\n" +
                "      <base-extension>true</base-extension>\n" +
                "      <layoutlib api=\"15\"/>\n" +
                "    </type-details>\n" +
                "    <revision>\n" +
                "      <major>1</major>\n" +
                "    </revision>\n" +
                "    <display-name>Android SDK Platform " + api + "</display-name>\n" +
                "  </localPackage>\n" +
                "</ns2:repository>\n");
    }

    private void ensurePlatformCoreForSystemModules(File dir) throws IOException {
        File coreModules = new File(dir, "core-for-system-modules.jar");
        if (coreModules.exists()) {
            return;
        }
        File androidJar = new File(dir, "android.jar");
        if (androidJar.exists()) {
            copyFile(androidJar, coreModules);
        }
    }

    private String androidReleaseName(int api) {
        switch (api) {
            case 34:
                return "14";
            case 35:
                return "15";
            case 36:
                return "16";
            default:
                return Integer.toString(api);
        }
    }

    private void ensurePlatformAlias(File sourcePlatform, int api) throws IOException {
        File sourceJar = new File(sourcePlatform, "android.jar");
        if (!sourceJar.exists()) {
            return;
        }
        File targetPlatform = new File(androidSdk(), "platforms/android-" + api);
        mkdir(targetPlatform);
        File targetJar = new File(targetPlatform, "android.jar");
        if (!targetJar.exists()) {
            copyFile(sourceJar, targetJar);
        }
        File sourceCoreModules = new File(sourcePlatform, "core-for-system-modules.jar");
        File targetCoreModules = new File(targetPlatform, "core-for-system-modules.jar");
        if (sourceCoreModules.exists() && !targetCoreModules.exists()) {
            copyFile(sourceCoreModules, targetCoreModules);
        } else if (!targetCoreModules.exists()) {
            copyFile(sourceJar, targetCoreModules);
        }
        File sourceOptional = new File(sourcePlatform, "optional");
        if (sourceOptional.exists()) {
            File targetOptional = new File(targetPlatform, "optional");
            if (!targetOptional.exists()) {
                copyRecursively(sourceOptional, targetOptional);
            }
        }
    }

    public File androidBuilderGradle() {
        return new File(bin(), "androidbuilder-gradle");
    }

    public void ensureGradleLauncherWrapper() throws IOException {
        File launcher = findGradleLauncherJar();
        if (launcher == null) {
            return;
        }
        File wrapper = androidBuilderGradle();
        writeText(wrapper, "#!/system/bin/sh\n" +
                "PREFIX=\"${PREFIX:-" + usr().getAbsolutePath() + "}\"\n" +
                "JAVA_HOME=\"$PREFIX/lib/jvm/java-21-openjdk\"\n" +
                "HOME=\"${HOME:-" + home().getAbsolutePath() + "}\"\n" +
                "TMPDIR=\"" + tmp().getAbsolutePath() + "\"\n" +
                "export JAVA_HOME\n" +
                "export HOME\n" +
                "export TMPDIR\n" +
                "export LD_LIBRARY_PATH=\"$JAVA_HOME/lib/server:$JAVA_HOME/lib:$PREFIX/lib:${LD_LIBRARY_PATH:-}\"\n" +
                "unset JAVA_OPTS\n" +
                "unset _JAVA_OPTIONS\n" +
                "exec \"$JAVA_HOME/bin/java\" -Duser.home=\"$HOME\" -Djava.io.tmpdir=\"$TMPDIR\" -Djdk.lang.Process.launchMechanism=VFORK -classpath \"" + launcher.getAbsolutePath() + "\" org.gradle.launcher.GradleMain \"$@\"\n");
        wrapper.setExecutable(true, false);
    }

    private File findGradleLauncherJar() {
        File opt = new File(usr(), "opt");
        File[] gradleDirs = opt.listFiles(file -> file.isDirectory() && file.getName().startsWith("gradle-"));
        if (gradleDirs == null) {
            return null;
        }
        for (File gradleDir : gradleDirs) {
            File lib = new File(gradleDir, "lib");
            File[] launchers = lib.listFiles(file -> file.isFile() &&
                    file.getName().startsWith("gradle-launcher-") &&
                    file.getName().endsWith(".jar"));
            if (launchers != null && launchers.length > 0) {
                return launchers[0];
            }
        }
        return null;
    }

    private int highestInstalledPlatformApi() {
        File platform = highestInstalledPlatform();
        return platform == null ? 0 : apiFromPlatformDir(platform);
    }

    private File highestInstalledPlatform() {
        File platforms = new File(androidSdk(), "platforms");
        File[] entries = platforms.listFiles(file -> file.isDirectory() && file.getName().startsWith("android-"));
        if (entries == null) {
            return null;
        }
        int highest = 0;
        File highestPlatform = null;
        for (File entry : entries) {
            File androidJar = new File(entry, "android.jar");
            if (!androidJar.exists()) {
                continue;
            }
            try {
                int api = apiFromPlatformDir(entry);
                if (api > highest) {
                    highest = api;
                    highestPlatform = entry;
                }
            } catch (Exception ignored) {
            }
        }
        return highestPlatform;
    }

    private int apiFromPlatformDir(File entry) {
        try {
            return Integer.parseInt(entry.getName().substring("android-".length()));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private void mkdir(File dir) throws IOException {
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Cannot create directory: " + dir);
        }
    }

    private void writeText(File file, String value) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Cannot create directory: " + parent);
        }
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(value.getBytes(StandardCharsets.UTF_8));
        }
    }

    private void writeWrapperIfToolExists(String tool) throws IOException {
        writeWrapperIfToolExists(tool, buildTools());
    }

    private void writeWrapperIfToolExists(String tool, File buildToolsDir) throws IOException {
        File executable = new File(bin(), tool);
        File wrapper = new File(buildToolsDir, tool);
        if (executable.exists()) {
            writeText(wrapper, "#!/system/bin/sh\n" +
                    "PREFIX=\"${PREFIX:-" + usr().getAbsolutePath() + "}\"\n" +
                    "exec \"$PREFIX/bin/" + tool + "\" \"$@\"\n");
        } else {
            writeText(wrapper, "#!/system/bin/sh\n" +
                    "echo \"Android SDK tool " + tool + " is not bundled in this runtime.\" >&2\n" +
                    "exit 127\n");
        }
        wrapper.setExecutable(true, false);
    }

    private String normalizeEntryName(String raw) {
        if (raw == null) {
            return "";
        }
        String name = raw.replace('\\', '/');
        while (name.startsWith("/")) {
            name = name.substring(1);
        }
        if (name.contains("../") || name.equals("..") || name.startsWith("../")) {
            return "";
        }
        String[] parts = name.split("/");
        if (parts.length > 1 && !parts[0].equals("usr") && !parts[0].equals("home") && !parts[0].equals("bin") && !parts[0].equals("android-sdk")) {
            StringBuilder stripped = new StringBuilder();
            for (int i = 1; i < parts.length; i++) {
                if (i > 1) {
                    stripped.append('/');
                }
                stripped.append(parts[i]);
            }
            name = stripped.toString();
        }
        return name;
    }

    private File targetForEntry(String name) {
        if (name.startsWith("usr/") || name.startsWith("home/")) {
            return new File(root(), name);
        }
        if (name.startsWith("bin/") || name.startsWith("android-sdk/")) {
            return new File(usr(), name);
        }
        return new File(usr(), name);
    }

    private boolean isInside(File parent, File child) throws IOException {
        String parentPath = parent.getCanonicalPath();
        String childPath = child.getCanonicalPath();
        return childPath.equals(parentPath) || childPath.startsWith(parentPath + File.separator);
    }

    private boolean isExecutablePath(File file) {
        String path = file.getAbsolutePath();
        return path.contains(File.separator + "bin" + File.separator) || path.endsWith(".sh") || path.endsWith("gradlew");
    }

    private void copy(InputStream in, FileOutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
    }

    private void copyFile(File source, File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null) {
            mkdir(parent);
        }
        try (FileInputStream in = new FileInputStream(source); FileOutputStream out = new FileOutputStream(target)) {
            copy(in, out);
        }
    }

    private void copyRecursively(File source, File target) throws IOException {
        if (source.isDirectory()) {
            mkdir(target);
            File[] children = source.listFiles();
            if (children != null) {
                for (File child : children) {
                    copyRecursively(child, new File(target, child.getName()));
                }
            }
            return;
        }
        copyFile(source, target);
    }
}
