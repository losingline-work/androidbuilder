# 移除本地 AI 模型实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 删除 GGUF/llama.cpp 本地模型功能，保留确定性源码守卫与云端修复流程。

**架构：** `AgentService` 改为只使用确定性规则、Hermes reviewer 和云端提示。设置页移除本地模型区域；app 模块去除 NDK/CMake；本地推理 Java/JNI/第三方源码整体删除。

**技术栈：** Android Java、JUnit 4、Gradle、XML Resources

---

### 任务 1：锁定无本地模型的 Agent 行为

**文件：**
- 修改：`app/src/test/java/com/androidbuilder/agent/AgentServiceRetryPolicyTest.java`
- 修改：`app/src/main/java/com/androidbuilder/agent/AgentService.java`

- [ ] 移除测试对 `LocalGuardAssistant` fake 的依赖，并运行目标测试确认生产构造器尚未匹配时失败。
- [ ] 删除 `AgentService` 的本地 assistant 字段、注入构造器、release 和本地模型 fallback 方法。
- [ ] 保留 `LocalGuardHeuristics`、云端 hint 和 Hermes 路径，运行 AgentService 测试确认通过。

### 任务 2：移除设置页和模型存储入口

**文件：**
- 修改：`app/src/main/java/com/androidbuilder/ui/SettingsActivity.java`
- 修改：`app/src/main/res/layout/activity_settings.xml`
- 修改：`app/src/main/res/values/strings.xml`
- 修改：`app/src/main/res/values-zh/strings.xml`

- [ ] 删除本地模型控件绑定、导入回调、保存偏好、状态刷新和模式转换。
- [ ] 删除设置页本地模型卡片与专属文案。
- [ ] 将日志搜索提示改为用户与云端模型对话。

### 任务 3：删除本地推理实现和测试

**文件：**
- 删除：`app/src/main/java/com/androidbuilder/agent/LlamaLocalGuardAssistant.java`
- 删除：`app/src/main/java/com/androidbuilder/agent/LocalGuardAssistant.java`
- 删除：`app/src/main/java/com/androidbuilder/agent/LocalGuardJsonGrammar.java`
- 删除：`app/src/main/java/com/androidbuilder/agent/LocalGuardMode.java`
- 删除：`app/src/main/java/com/androidbuilder/agent/LocalGuardModelStore.java`
- 删除：`app/src/main/java/com/androidbuilder/agent/LocalGuardResultParser.java`
- 删除：`app/src/main/java/com/androidbuilder/agent/LocalGuardSettings.java`
- 删除：`app/src/main/java/com/androidbuilder/agent/LocalLlamaEngine.java`
- 删除：`app/src/main/java/com/androidbuilder/agent/NoOpLocalGuardAssistant.java`
- 删除对应本地模型专属测试。

- [ ] 删除仅被本地推理使用的 Java 类型和测试。
- [ ] 用 `rg` 确认剩余引用只属于确定性规则结构。

### 任务 4：移除 JNI、CMake 和 llama.cpp

**文件：**
- 修改：`app/build.gradle`
- 删除：`app/src/main/cpp/CMakeLists.txt`
- 删除：`app/src/main/cpp/local_guard_llama.cpp`
- 删除：`third_party/llama.cpp/`

- [ ] 从 app Gradle 配置删除 NDK ABI 与 externalNativeBuild。
- [ ] 删除 JNI 源码和 llama.cpp 目录。
- [ ] 清理第三方声明中的 llama.cpp 内容。

### 任务 5：完整验证

- [ ] 运行 `./gradlew :app:testDebugUnitTest`，预期 `BUILD SUCCESSFUL`。
- [ ] 运行 `./gradlew :app:assembleDebug`，预期 `BUILD SUCCESSFUL` 且无 CMake 任务。
- [ ] 运行 `rg -n "GGUF|gguf|llama\\.cpp|LocalLlama|LlamaLocalGuard|LocalGuardSettings|LocalGuardMode" app app/build.gradle`，预期无生产功能引用。
- [ ] 检查 `git diff --stat`，确认没有回退工作区原有无关修改。
