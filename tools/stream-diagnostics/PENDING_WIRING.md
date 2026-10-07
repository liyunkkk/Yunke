# 待接线清单（集成阶段；本实现没有修改下列 owner 文件）

基线：041900cc。行号仅为此基线附近的定位提示；以函数名和代码锚点为准。
不要求改任何消息参数、key、contentType、缓存、reveal 策略或跟底算法。
诊断关闭时 modifier 返回原实例；record/measure 立即透传，不读快照状态。
本清单不宣称已接线，也不把提交点标记误写成 Compose 耗时。

## 1. AgentConversationMessages **内容 scope** 的成功提交

- 文件：`app/src/main/kotlin/io/github/mangi/eta/ui/components/AgentChatBody.kt`
- 函数：`AgentConversationMessages`（约 L627）。
- **正确 scope**：约 L1286–1302，末尾 `Box(...) { ... LazyColumn(...) }` 的内容
  lambda 内，已有 `val messageActions = remember { ChatMessageActions() }` 和
  `SideEffect { messageActions.onSuggestionClick = ...; ... }`。
- API / 完整字面量：在这个**已有 SideEffect 的末尾**追加：

  ```kotlin
  StreamPerformanceDiagnostics.record("chat.content.commit", value = 1)
  ```

- 不要接在约 L229 的父 `AgentChatBody` 的 `chat.compose` SideEffect；那不是这个
  内容 scope，也不能证明该内容 lambda 真正执行。
- 为什么不改变 skippability/重组：不新增 Composable wrapper、参数、remember、
  快照状态、effect 或订阅，只在成功 apply 时执行现有 SideEffect 的普通诊断写入。
  内容被跳过就没有新 SideEffect。此记录是 **ns=0 提交次数/点标记，不是组合耗时**。
  `record` 内部检查 opt-in 会话，关时不读时钟、不留明细。

## 2. 会话 LazyColumn 的测量与放置

- 文件/函数同上。
- 锚点：约 L1303，`LazyColumn(state = scrollState, ... modifier = Modifier
  .fillMaxSize().graphicsLayer { ... }.onGloballyPositioned { ... })`。
- 在它的 `Modifier` 链开头（`fillMaxSize` 之前）加：

  ```kotlin
  .streamDiagnosticMeasure("list.measure")
  .streamDiagnosticPlacement("list.place")
  ```

- `list.measure` 包围原 child measure；`list.place` 包围原 child placement。
  测量函数创建 placement lambda 的时间不能冒充 placement 执行时间。
- 为什么不改变结果：两者关时原 modifier identity；开时保持原 constraints、
  width/height，child 只 measure 一次、`placeRelative(0, 0)` 一次。新 placement
  observer 无 layer、semantics、快照状态或调度动作；固定 stage 的 data-class
  element 等值复用 node，不因无关重组创建新 observer policy。
  不动 `state`、排列、graphicsLayer、裁切、恢复逻辑、key 或 contentType。
  placement 仍使用相对坐标，RTL 语义不变。启用时额外 LayoutModifierNode 的
  成本属于诊断扰动，不能声称完全无扰动；透明性测试已写，但 Android 未执行。
- `streamDiagnosticMeasure(stage)` / `streamDiagnosticDraw(stage)` 原签名与
  原 measure/draw 语义在本实现中保持不变；placement 是独立新增 API。

## 3. Markdown 文档容器（补齐 block 之外的布局/绘制）

- 文件：`app/src/main/kotlin/io/github/mangi/eta/ui/components/ChatMessageItem.kt`
  （并行 owner，**本实现没有修改**）。
- 函数/锚点：`ChatMarkdownDocument` 约 L1240；L1299 附近
  `var previousVisibleType: IElementType? = null; Column(modifier) { blocks.forEachIndexed ... }`。
- 把该 `Column` 的 modifier 接为：

  ```kotlin
  modifier
      .streamDiagnosticMeasure("render.measure")
      .streamDiagnosticDraw("render.draw")
  ```

- 这两个 stage 已在原注册表；是文档容器的 **inclusive** 范围，不能与内层
  `markdown.stable.measure` / `markdown.tail.measure` / `markdown.hidden.measure`
  或 `markdown.blockDraw` 相加。`FrozenMarkdownElement` 约 L1335–1354 的
  stable/tail 测量和 blockDraw/stable/tail 绘制已经接线，不应重复插同名 span。
- 为什么不改变结果：关时同一 modifier；开时 child measure/draw 各一次，
  constraints/尺寸/放置原样传递。只加 observer modifier，不包装原 Composable
  内容、不改变 AST、freeze、remember、reveal key 或 block 可见性。

## 4. ui.flush 共用边界（现有 timer/reason span 的覆盖范围说明）

- 文件：`app/src/main/kotlin/io/github/mangi/eta/ui/app/AgentAppState.kt`
  （并行 owner，**本实现没有修改**）。
- 函数/锚点：`flushPendingRunDelta` 约 L4756，现有代码：

  ```kotlin
  runEventFlushJobs.remove(runId)?.cancel()
  runEventCoalescer.flush(runId)?.let { event ->
      StreamUiEventDiagnostics.measure(diagnosticStage) { applyRunEvent(runId, event) }
  }
  ```

- 把**函数内的现有同步 body**包在：

  ```kotlin
  StreamPerformanceDiagnostics.measure("ui.flush") { /* 原同步 body，一次 */ }
  ```

- 现有 timer 调用在 `scheduleRunDeltaFlush` 约 L4749–4753 已有同名
  `measure("ui.flush")`；集成时把该冗余外层移到共用函数，保留
  `withAttribution(...)` 和 `diagnosticStage = "ui.flush.timer"`，避免两个同名
  inclusive span 混淆计数。`ui.flush.blockSwitch` / `ui.flush.nonDelta` /
  `ui.flush.timer` 继续表示已有 apply reason，不改字面量。
- `applyCoalescedRunEvent` 约 L4671 的 blockSwitch 直接 `applyRunEvent` 路径
  不经过这个函数；若要统一边界，在已有 `measure("ui.flush.blockSwitch")`
  外层加 `measure("ui.flush")`（仍只调用原 apply 一次）。
- 为什么不改变业务或组合：此处非 Composable，是同步 main-thread 回调；
  measure 关时直接执行 block，开时 finally 记录耗时；不改取消、合并、apply 的
  次数/顺序，不跨越前面的 `delay` 或任何 coroutine suspension。
  这些 span 仍不能当作 FrameMetrics unknown 的独占分量。

## 已在允许文件接通的入口（无需 owner 再插）

- `ChatBodyTrace.kt` 的 `traceChatBodyRun` 约 L81，复用已有
  `SideEffect { traceChatBodyRun("md", bodyTraceMount) }`、`md.doc`、
  `md.phase.loading`、`md.phase.error`、`md.phase.success`、`thinking`、`tool`
  标记入口，opt-in 记录固定 `render.compose`，value=1 / ns=0。
  不向 collector 传 mount、文本或 ID；这些 marker 的嵌套提交次数不可当成
  唯一消息数或内容重组耗时。
- `StreamPerformanceDiagnostics.measure` 的同步 outermost 覆盖、reveal subset，
  `attach` 的 Looper 完成边界已接 `main.uninstrumented` / `main.nonReveal`；
  `mainMessage` v2 输出是消息 wall-time 数字包络，不是 FrameTimeline。

新增 stage 完整表和 residual/overlap/vsyncLate 的不可相加包含关系见 README。
