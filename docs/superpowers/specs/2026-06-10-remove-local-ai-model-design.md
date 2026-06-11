# 移除本地 AI 模型设计

## 目标

从 Android Builder 中彻底移除 GGUF/llama.cpp 本地模型能力，减少 APK 原生构建复杂度、仓库体积和设置项，同时保留确定性源码校验、云端模型修复与 Hermes 审查流程。

## 删除范围

- 删除设置页中的本地模型启用、模式选择、GGUF 导入/删除和状态展示。
- 删除 GGUF 文件存储、偏好设置、本地 llama 引擎、JNI 桥接和本地模型 assistant。
- 删除 app 模块的 CMake/NDK 配置和 `third_party/llama.cpp` 源码。
- 删除仅服务本地推理的 grammar/parser、模式和测试。
- 从 `AgentService` 删除本地模型 preflight、policy rewrite、build triage fallback 与本地模型元数据。
- 清理本地模型文案、日志搜索提示和第三方声明。

## 保留范围

- `AndroidSourceGuard`、`DependencyGuard`、`TaskOperationsPreflight` 等确定性校验。
- `LocalGuardHeuristics`、`LocalGuardInstructionComposer`、`LocalGuardResult` 和必要 prompt 组装；它们不加载模型，继续作为本地规则与云端提示的内部数据结构。
- 云端 policy rewrite、云端 build triage、Hermes reviewer 和 AI conversation 日志。

## 行为变化

- 设置页不再出现本地 GGUF 助手区域。
- 代码生成不再加载或调用设备端模型。
- 本地规则命中时仍会阻止或改写高风险源码；云端调用失败时不再回退到本地模型。
- 旧版本已导入的 GGUF 文件不再被读取。应用升级后不主动扫描或删除用户私有目录中的旧文件，避免迁移代码扩大范围；卸载应用会清理该私有文件。

## 验证

- 更新 AgentService 单元测试，使其不再注入 `LocalGuardAssistant`。
- 删除本地模型专属测试，保留确定性规则测试。
- 全量运行 `:app:testDebugUnitTest`。
- 运行 `:app:assembleDebug`，确认不再执行 CMake/llama.cpp 构建任务。
- 使用 `rg` 确认生产代码、资源和构建配置中没有 GGUF/llama.cpp/本地模型入口。
