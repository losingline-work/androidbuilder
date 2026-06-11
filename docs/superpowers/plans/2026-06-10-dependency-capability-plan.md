# 依赖能力扩展方案：少失败 + 多第三方包

> 状态（2026-06-10）：**Phase 1 + Phase 2 已实现，`:app:testDebugUnitTest` 全量通过。Phase 3（开放层 + 缓存固化）待前两阶段真机验证后再做。**
> 目标读者：实现此方案的 AI 工作者 / 开发者。

## 背景与目标

两个目标看似矛盾：

- **尽量少的执行失败 / 构建失败**；
- **尽量多地引入第三方包，提高生成 App 的能力**。

当前 `online` 模式是静态白名单（`OnlineDependencyPolicy`：androidx.* / material / gson / guava / findbugs / org.apache.commons / commons-io / commons-codec / joda-time，钉版本）。它的问题在实战中已经暴露（project-2 日志）：

1. **模型想用的能力库不在白名单**（MPAndroidChart 在 JitPack 的 `com.github.*`），计划文本把它 baked-in，之后每个任务都先撞守卫再被改写，浪费 2~3 次重试额度，315+ 次无效拉锯。
2. 白名单是"二元允许/拒绝"，守卫报错只说"不允许"，**不告诉模型"该用什么替代"**，改写循环收敛慢。
3. 一个依赖能不能用，最终裁决者其实是 **Gradle 解析 + 编译**，静态列表既挡住了能用的（MPAndroidChart 本身纯 Java、完全可构建），也保不住会挂的（白名单内版本也可能与 compileSdk 34 冲突）。

### 核心洞察

依赖的失败风险不是均匀的，而是**可预知、可分层、可机器验证**的：

| 失败原因 | 是否可静态判断 | 是否可机器验证 |
| --- | --- | --- |
| 需要注解处理器（Room/Hilt/Glide-compiler） | ✅ 坐标特征 | — |
| 需要 Kotlin 插件 / Compose | ✅ 坐标特征 | — |
| 坐标不存在 / 仓库缺失（JitPack） | ❌ | ✅ **解析预检**（秒级） |
| 需要更高 AGP/compileSdk | ⚠️ 部分（编目可知） | ✅ 编译 |
| 传递依赖冲突（duplicate class） | ❌ | ✅ 解析/编译 |
| 网络不可达 | ❌ | ✅ 解析预检（与"不存在"区分） |

> 关键技术事实：**从 Java 消费 Kotlin 编写的库不需要 Kotlin 插件**——kotlin-stdlib 只是传递依赖的 jar，`AndroidGradleNormalizer` 已有 stdlib 版本对齐。真正的硬死刑只有：注解处理器（kapt/ksp 专属）、Compose、要求更高 AGP/SDK 的版本。这意味着 OkHttp、Retrofit、Lottie、Glide（不带 compiler）、RxJava、MPAndroidChart 等大批能力库**本就可用**，只是被静态白名单挡住了。

### 方案总览：三层依赖体系 + 解析预检

```
Tier 1  能力编目 Catalog（已验证、带使用知识、prompt 主动广告）  ← 失败率最低，能力主力
Tier 2  可信组 + 钉版本（现状保留）                              ← androidx.* 等
Tier 3  开放坐标（用户可选开启；任意钉版本坐标，解析预检作闸门）  ← 能力上限，机器验证兜底
硬拦截  注解处理器 / Kotlin 插件 / Compose / 动态版本 / 额外 Gradle 插件（永不放开）
```

把"该不该用"从守卫的静态判断，移交给"**编目知识（事前）+ Gradle 解析预检（事中）+ 失败分类定向修复（事后）**"三道客观机制。

---

## Phase 1：能力编目 + prompt 广告（性价比最高，先做）

### 任务 1.1：新建 `DependencyCatalog`

文件：`app/src/main/java/com/androidbuilder/agent/DependencyCatalog.java`（+ 测试）

每个条目包含：`groupId:artifactId`、钉死版本（与 AGP 8.7.3 / compileSdk 34 / minSdk 24+ / 纯 Java 消费验证兼容）、所需仓库（mavenCentral/google/**jitpack**）、用途标签、给模型的使用提示（一句话）、所需 Manifest 权限（如网络库 → INTERNET）。

**v1 编目清单**（全部可从 Java 消费、无注解处理器要求）：

| 用途 | 坐标 | 仓库 | 备注 |
| --- | --- | --- | --- |
| 图表 | `com.github.PhilJay:MPAndroidChart:v3.1.0` | jitpack | 纯 Java，本次失败案例主角 |
| 图片加载 | `com.squareup.picasso:picasso:2.8` | central | 纯 Java，最简 |
| 图片加载 | `com.github.bumptech.glide:glide:4.16.0` | central | 不带 compiler 即可用 |
| 动画 | `com.airbnb.android:lottie:6.4.0` | central | stdlib 传递依赖可接受 |
| 网络 | `com.squareup.okhttp3:okhttp:3.12.13` | central | 纯 Java 末代版本，最稳 |
| 网络 | `com.squareup.retrofit2:retrofit:2.11.0` + `converter-gson:2.11.0` | central | 纯 Java |
| 响应式 | `io.reactivex.rxjava3:rxjava:3.1.9` + `rxandroid:3.0.2` | central | 纯 Java |
| 事件总线 | `org.greenrobot:eventbus:3.3.1` | central | 纯 Java |
| 时间 | `com.jakewharton.threetenabp:threetenabp:1.4.7` | central | |
| 二维码 | `com.google.zxing:core:3.5.3` | central | 纯 Java |
| JSON | `com.squareup.moshi:moshi:1.15.1` | central | 仅反射模式，禁 codegen |

仍然硬拦截：Room、Hilt/Dagger、Compose、任何 `*-compiler`/`*-annotation-processor`、Kotlin Gradle 配置、非 `com.android.application` 插件、动态版本。

### 任务 1.2：守卫接入编目

文件：`DependencyGuard.java`、`OnlineDependencyPolicy.java`

- `isApproved` 判定顺序：编目精确命中 → 可信组+钉版本 →（Phase 3 的开放层）。
- **拒绝信息带替代建议**：报错时若用途可推断（如 `chart`、`image` 关键词或同 group 不同版本），附"catalog 提供 X@版本，请改用"。同版本不符时直接给出编目钉版。

### 任务 1.3：仓库注入支持 JitPack

文件：`backend/AndroidGradleNormalizer.java`

- `MIRROR_REPOSITORIES` 末尾追加 `maven { url 'https://jitpack.io' }`（排在阿里云镜像与官方源之后，仅未命中时才会查询，对现有依赖零开销）。
- 同步 `EmbeddedRuntimeBackend` 在线可达性检查：编目内有 jitpack 依赖时附带探测 jitpack（失败降级为警告，不阻塞）。

### 任务 1.4：prompt 主动广告编目（治本：让计划"生而合规"）

文件：`OpenAiClient.java`（planner / tasks / coder 的 dependencyPolicyPrompt）

- `online` 模式 prompt 改为："可用能力库（精确版本）：图表→MPAndroidChart v3.1.0；图片→Picasso 2.8 / Glide 4.16.0（不带 compiler）；网络→OkHttp 3.12.13 + Retrofit 2.11.0（需 INTERNET 权限）；……不在此列时优先 Android SDK 自绘/自实现"。
- **planner 也要拿到**：本次失败的根因之一是计划阶段就承诺了不可用的库；编目进 plan prompt 后，计划天然兼容。
- `offline_safe` / `local_cache` 模式 prompt 明确"不要承诺第三方图表/图片库"，由 `CapabilityAnalyzer` 在计划评估时提示能力边界。

### 任务 1.5：改写指引编目化

文件：`PolicyRewriteInstruction.java`

- 依赖类拦截的 rewrite hint 直接列编目替代项（含坐标和版本），把"撞墙→收敛"压缩到 1 次重试。

**Phase 1 验收**：重跑"记账 App + 图表"场景，计划直接选用 MPAndroidChart（或自绘），全程 0 次依赖策略拦截；单测覆盖编目判定、jitpack 注入、prompt 内容。

---

## Phase 2：依赖解析预检（快失败 + 可分类）

### 任务 2.1：构建前解析预检

文件：`backend/EmbeddedRuntimeBackend.java`

- 在 `gradle --version` smoke test 之后、`assembleDebug` 之前，通过 init-script 注册并执行 `abResolveDebugDeps` 任务（内部 `configurations.debugRuntimeClasspath.resolvedConfiguration.rethrowFailure()`），独立短超时（如 90s）。
- 失败输出干净可分类：`Could not find com.foo:bar:1.0`（坐标不存在）vs `Could not resolve`/timeout（网络）。秒级暴露，不再等整次构建烧到一半。

### 任务 2.2：失败分类器与修复指令

文件：`BuildFailureClassifier.java`、`PolicyRewriteInstruction.java` / `repairInstruction`

- 新增 `DEPENDENCY_UNRESOLVABLE`（repairableByModel=true）：修复指令明确"删除或替换为编目中的 X"。
- 与既有 `DEPENDENCY_NETWORK`（repairable=false，不提供修复按钮）严格区分。

**Phase 2 验收**：人为写入不存在坐标 → 90s 内失败、分类正确、修复一轮替换成功；断网场景归类为网络且不进修复循环。

---

## Phase 3：开放坐标层 + 缓存固化（能力上限）

### 任务 3.1：开放在线子模式（opt-in）

文件：`BuildBackendSettings`、`SettingsActivity`、`DependencyGuard`

- 新增"联网增强（开放）"：任意 `group:artifact:钉死版本` 放行（仍硬拦注解处理器/Kotlin 插件/Compose/动态版本/额外插件），合法性交给 Phase 2 解析预检裁决。默认仍为"联网增强（可信）"。

### 任务 3.2：成功依赖固化（"成功一次，永久离线"）

文件：`EmbeddedRuntimeBackend`、`offline-maven` 机制复用

- 构建成功后，把本次解析的第三方产物（jar/aar/pom）增量导出到 `files/offline-maven`，并记录到"本机已验证依赖"清单；后续构建优先本地命中，弱网/断网也能复建。
- "本机已验证依赖"作为编目的动态扩展（Tier 1.5），prompt 同步广告。

**Phase 3 验收**：开放模式下引入一个编目外纯 Java 库（如 `org.apache.poi` 子集或 `com.opencsv:opencsv`）一次构建成功；断网重建仍成功。

---

## 风险与对策

| 风险 | 对策 |
| --- | --- |
| JitPack 国内可达性差/慢 | 仓库排序最后（仅未命中才查询）；解析预检短超时快失败；编目对 jitpack 条目同时给 central 替代（图表无替代则提示自绘降级）；Phase 3 固化后不再依赖网络 |
| Kotlin stdlib 传递依赖体积/冲突 | normalizer 已做版本对齐；编目优先纯 Java 版本（OkHttp 3.12.x） |
| 开放层供应链风险 | opt-in + 钉版本 + 硬拦插件/处理器；解析只信任已注入仓库 |
| 设备存储增长（gradle 缓存 + offline-maven） | 设置页显示占用 + 一键清理（后续小任务） |
| 编目版本随时间过期 | 编目集中单文件 + 单测锁定，升级一处改 |

## 实施顺序与依赖关系

```
Phase 1（编目+prompt+jitpack） ──┬──> Phase 2（解析预检+分类） ──> Phase 3（开放层+固化）
                                 └──（独立可发布，先行止血）
```

每个 Phase 独立可提交、可回滚；全程 `:app:testDebugUnitTest` 守护。
