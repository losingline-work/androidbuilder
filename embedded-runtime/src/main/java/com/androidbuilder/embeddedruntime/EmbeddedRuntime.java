package com.androidbuilder.embeddedruntime;

import android.content.Context;

import java.io.File;
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

    public File buildTools() {
        return new File(androidSdk(), "build-tools/35.0.0");
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
        mkdir(buildTools());
        mkdir(licenses());
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
        if (!new File(androidSdk(), "platforms/android-34/android.jar").exists()) {
            missing.add("usr/android-sdk/platforms/android-34/android.jar");
        }
        return missing;
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
        mkdir(buildTools());
        writeWrapperIfToolExists("aapt2");
        writeWrapperIfToolExists("d8");
        writeWrapperIfToolExists("apksigner");
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
                "TMPDIR=\"" + tmp().getAbsolutePath() + "\"\n" +
                "export JAVA_HOME\n" +
                "export TMPDIR\n" +
                "export LD_LIBRARY_PATH=\"$JAVA_HOME/lib/server:$JAVA_HOME/lib:$PREFIX/lib:${LD_LIBRARY_PATH:-}\"\n" +
                "unset JAVA_TOOL_OPTIONS\n" +
                "unset JDK_JAVA_OPTIONS\n" +
                "unset JAVA_OPTS\n" +
                "unset _JAVA_OPTIONS\n" +
                "exec \"$JAVA_HOME/bin/java\" -Djava.io.tmpdir=\"$TMPDIR\" -Djdk.lang.Process.launchMechanism=VFORK -classpath \"" + launcher.getAbsolutePath() + "\" org.gradle.launcher.GradleMain \"$@\"\n");
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
        File executable = new File(bin(), tool);
        if (!executable.exists()) {
            return;
        }
        File wrapper = new File(buildTools(), tool);
        writeText(wrapper, "#!/system/bin/sh\n" +
                "PREFIX=\"${PREFIX:-" + usr().getAbsolutePath() + "}\"\n" +
                "exec \"$PREFIX/bin/" + tool + "\" \"$@\"\n");
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
}
