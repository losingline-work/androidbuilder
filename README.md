# Android Builder

Android Builder 是一个运行在安卓手机上的自动开发 Agent 控制 App。用户可以创建多个项目、输入需求、保留本地对话记录，让 Agent 生成 Android 项目源码，并尝试在手机本地构建 APK 后通过系统安装流程安装。

## 当前能力

- 多项目管理：新建、搜索、重命名、删除项目。
- 本地持久化：项目、聊天记录、构建任务、APK 产物都存储在本机。
- 云端模型：支持 OpenAI-compatible API，并内置 OpenAI / DeepSeek / 自定义服务配置。
- 多语言：支持跟随系统、English、中文。
- 生成目标：Kotlin + XML 的简单多页面 CRUD Android App。
- 构建后端：
  - 默认：内置运行环境 `embedded-runtime`。
  - 兼容模式：外部 Termux。
- 安装链路：使用 Android `PackageInstaller`，普通设备需要用户确认安装。

## 工程结构

```text
.
├── app/                  # Android Builder 主 App
├── embedded-runtime/     # 内置构建运行环境模块
├── build.gradle
├── settings.gradle
└── gradle.properties
```

关键代码：

- `app/src/main/java/com/androidbuilder/ui/`：项目列表、项目详情、设置页、第三方声明页。
- `app/src/main/java/com/androidbuilder/agent/`：需求转 App 规格、云端模型调用、生成 Android 项目源码。
- `app/src/main/java/com/androidbuilder/backend/`：构建后端抽象、内置后端、外部 Termux 兼容后端、runtime 安装器。
- `app/src/main/java/com/androidbuilder/data/`：SQLite 本地数据库和仓储。
- `embedded-runtime/src/main/java/com/androidbuilder/embeddedruntime/EmbeddedRuntime.java`：私有运行环境目录、bootstrap 安装、工具链检查。

## 内置运行环境

内置后端会在 App 私有目录中创建：

```text
files/runtime/
├── home/
├── usr/
│   ├── bin/
│   └── android-sdk/
└── work/{projectId}/{jobId}/
```

构建时需要至少存在：

```text
files/runtime/usr/bin/gradle
files/runtime/usr/bin/java
files/runtime/usr/bin/aapt2
files/runtime/usr/android-sdk/platforms/android-34/android.jar
```

如果这些工具不存在，构建不会再触发 Termux 权限问题，而是会在构建日志中明确提示缺少哪些工具。

## Bootstrap Zip

设置页支持两种安装方式：

- 安装内置 bootstrap：把 `bootstrap-arm64.zip` 放到 `app/src/main/assets/runtime/bootstrap-arm64.zip` 后重新打包 APK。
- 下载并安装 bootstrap：在设置页填写 zip URL，App 会下载并解压到 `files/runtime`。

支持的 zip 结构：

```text
usr/bin/gradle
usr/bin/java
usr/bin/aapt2
usr/android-sdk/platforms/android-34/android.jar
home/
```

也支持简写结构：

```text
bin/gradle
bin/java
bin/aapt2
android-sdk/platforms/android-34/android.jar
```

安装器会把简写结构归一化到：

```text
files/runtime/usr/
```

Termux 官方 64 位 ARM bootstrap 通常叫：

```text
bootstrap-aarch64.zip
```

可从 Termux Packages Releases 获取：

```text
https://github.com/termux/termux-packages/releases
```

注意：官方 Termux bootstrap 默认面向 `com.termux` 路径。要长期稳定内置到 `com.androidbuilder`，建议重新构建适配本 App 私有路径的 bootstrap。

## 外部 Termux 兼容模式

外部 Termux 现在不是默认路径，只作为兼容模式保留。启用后仍可能遇到厂商权限限制，尤其是 OPPO/ColorOS。

需要的权限/设置：

- Termux 启用外部命令：

```bash
mkdir -p ~/.termux
echo allow-external-apps=true >> ~/.termux/termux.properties
exit
```

- Android Builder 获得 `com.termux.permission.RUN_COMMAND`。
- Termux 获得“显示在其他应用上层”权限。

如果系统隐藏自定义权限，可用 ADB 授权：

```bash
adb shell pm grant com.androidbuilder com.termux.permission.RUN_COMMAND
```

## 本地构建

本机需要 Android SDK 和 JDK 17。当前项目固定使用本机 Corretto 17：

```properties
org.gradle.java.home=/Users/chedanchechedan/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home
```

构建 Debug APK：

```bash
./gradlew clean assembleDebug
```

运行 lint：

```bash
./gradlew lintDebug
```

生成 APK：

```text
app/build/outputs/apk/debug/app-debug.apk
```

安装到连接的手机：

```bash
$ANDROID_HOME/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 模型配置

设置页支持：

- OpenAI
- DeepSeek
- Custom OpenAI-compatible endpoint

DeepSeek 默认配置：

```text
Endpoint: https://api.deepseek.com/chat/completions
Model: deepseek-v4-flash
```

## 数据存储

SQLite 表：

- `projects`
- `messages`
- `build_jobs`
- `artifacts`

项目文件存储：

```text
files/projects/{projectId}/source
files/projects/{projectId}/jobs/{jobId}
```

删除项目时会删除对应的聊天记录、源码、日志和 APK 产物。

## 许可证与合规

如果直接复用或打包 Termux GPLv3 代码/二进制，需要：

- 保留 Termux 许可证和版权声明。
- 提供对应源码和本地修改。
- 按 GPLv3 要求分发相关代码。

App 内已包含“第三方声明”页面，说明 Termux、termux-packages、terminal-emulator、terminal-view 等组件的合规要求。

参考：

- Termux App: https://github.com/termux/termux-app
- Termux Packages: https://github.com/termux/termux-packages
- Termux Releases: https://github.com/termux/termux-packages/releases
