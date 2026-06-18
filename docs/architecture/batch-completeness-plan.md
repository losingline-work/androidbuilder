# 计划:解决「批次漏产计划文件 / 大任务收不拢」

> 背景:资源假阳性整类已根治(merge 守卫 XML、preflight、Java R.*、云端 reviewer 四层全降级给 aapt)。
> 之后 drawable/layout 任务暴露出最后一类**真·模型问题**:`Batch validation: missing planned file`——
> 模型按 manifest 分批生成时,某些批次没把计划中的文件全产出。本计划解决这一类。

## 问题机理(钉死根因)

当前分批流程(`AgentService.createTaskOperationsInBatches`,约 L1688–1815):

```
manifest(列全部文件) → ManifestBatchPolicy.batches(按权重分组,MAX_BATCH_WEIGHT=10)
  → 每批:createTaskOperationsBatch(让模型只产出这批文件)
       → BatchValidationPolicy.review:这批计划文件全产出了吗?
            缺任何一个 → 返回错误串 → 整批重试(batchAttempt 最多 2 次)
            2 次仍缺 → BatchGenerationException → 整个任务尝试失败 → 外层重试 → 耗尽
```

**两个结构性放大器**:
1. **全有或全无**:模型产出 9/10,整批(含已产出的 9 个)被判失败、整批重生成。重试时可能换成另一个 9/10,**永不收敛**。
2. **大任务**:一个 weight=10 的批次可能十几个文件;模型注意力分散,漏掉几个。drawable/layout 又是最大的 canned 任务(所有矢量图 + 所有布局)。

> 注:`BatchValidationPolicy` 本身**已不查资源存在性**(注释明确,资源是 aapt 的事),它只查 full-write/delete、unplanned sprawl、缺计划文件。所以这不是假阳性,是真·模型没产出。

## 杠杆与取舍

| 杠杆 | 改动 | 影响 | 代价 |
| --- | --- | --- | --- |
| **A 结转重产(carry-forward)** | 中 | **直接消除"全有或全无"**:接受已产出的,只把缺的几个结转成后续小批次重产 | 无额外云调用,稳 |
| **B 更小批次** | 1 行 | 每批文件更少 → 漏产概率下降 | 批次变多 → 云调用变多、429 风险上升、更慢 |
| **C 拆分超载任务** | 大 | drawable/layout 拆成更小的独立任务(资源跨任务引用已交给 aapt,拆分现在安全) | 改任务图、改 normalizer,回归面大 |
| **D 分块校验(orthogonal)** | 大 | `TierValidationPolicy` 已落地大脑;按层跑 aapt/javac,让不完整**早暴露** | 跨层 + 需真机验;不直接修"漏产",是"早发现" |

**A 是最佳性价比**:它正面解决全有或全无,且不像 B 那样把云调用翻倍。这正是我们已经用过的「部分进度 + 定向重试」模式(截断救回就是同一思路,只是在 JSON 层)。

## 推荐路线(每阶段之间有度量门)

### 阶段 0:先量(已可做)
`e969d05`(reviewer 修复)后**先跑一次**。reviewer 不再逼模型"创建所有引用资源",模型注意力回到计划文件上,漏产**可能自然大降**。
- **门**:drawable 任务若能收拢(不耗尽)→ 本问题已解决,后续阶段都不必做。
- 若仍 `missing planned file` 频繁 → 进阶段 1。

### 阶段 1(主修复):结转重产 carry-forward
把"缺计划文件"从**整批硬拒**改成**接受已产出 + 把缺的结转**。

**改动**:
- `BatchValidationPolicy.review`:把返回值从单一错误串,拆成「结构错(必须重试:坏 action、unplanned sprawl)」与「缺计划文件集(可结转,非硬拒)」两类。结构错仍返回错误串;只缺文件时返回缺失路径集合(新的轻量结果类型,如 `BatchReview{structuralError, missingPlannedPaths}`)。
- `AgentService.createTaskOperationsInBatches`:
  - 只缺文件 → `accepted.addAll(已产出)`,把 `missingPlannedPaths` 累加进一个 `carryForward` 集合,**继续下一批**(不耗 batchAttempt)。
  - 所有 manifest 批次跑完后,若 `carryForward` 非空,把它们组成额外批次(复用 `ManifestBatchPolicy.batches` 对这批文件再分组)再跑一轮;仍缺的进入下一轮,设一个 `MAX_CARRY_FORWARD_ROUNDS`(≈2)上限。
  - 彻底仍缺 → 此时才按缺失报错(交给现有外层重试 / 最终 aapt 兜底)。
- **测试**(可单测,不需 runtime):
  - `BatchValidationPolicyTest`:结构错仍返回错误;只缺文件返回缺失集合而非错误。
  - 一个 `CarryForwardPolicy`(若把"该结转还是该硬拒、是否超轮"抽成纯函数)的单测。

**守卫安全**:结转只改"何时重产哪些文件",不放行任何最终守卫会拒的东西;merge 时 `validatePolicyOnly` + 构建时 aapt/javac 仍是权威。

### 阶段 2(调参,仅当阶段 1 后仍漏):更小批次
`ManifestBatchPolicy.MAX_BATCH_WEIGHT` 10 → 6(并视情况 `SINGLE_BATCH_THRESHOLD`)。一行改动。
- **门**:接受"云调用变多 + 429 风险"的代价;若 429 明显恶化,回调或配合退避。

### 阶段 3(最后手段,仅当 1+2 仍收不拢):拆分超载 canned 任务
`ImplementationTaskNormalizer.drawableLayoutTask` 拆成更细的独立 `project_tasks`(例如「drawable 资源」与「layout XML」分开,或按屏拆 layout)。
- 资源跨任务引用现已交给 aapt,拆分不再引入资源假阳性,**比半年前安全**。
- 代价:改任务图 + `HermesTaskGraph` 依赖 + normalizer,回归面最大,所以排最后。

### 并行轨道(orthogonal):分块校验 D
`TierValidationPolicy`(大脑)已就位。等**第一次真正进到构建、拿到 aapt/javac 真实输出**后,接 backend 的逐层 aapt/javac + 分层修复。它解决的是「错误早暴露 / 减构建压力」,和本计划的「生成完整性」互补,不互替。

## 度量(怎么判断哪一阶段够了)

每跑一个新构建,看 drawable/layout 任务:
- `已失败且重试耗尽` 是否归零(收拢)。
- `Batch validation: missing planned file` 次数(应随阶段递减)。
- 收拢消耗的云调用数 / 是否触发 429。
- 关键里程碑:**生成是否首次跑完 → 首次进构建**。

阶段顺序就是按"性价比 + 风险"排的:先白量(0)→ 主修复(1,carry-forward)→ 调参(2)→ 动刀任务图(3)。能在前面阶段收拢就不碰后面。
