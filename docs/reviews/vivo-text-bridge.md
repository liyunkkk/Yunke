# V2419A native text adapter — implementation and acceptance record

## Current scope

The source now joins a typed vivo outbound hook, authenticated Messenger client,
Eta text-only model service, native answer/cancel sinks and an independent UI
opt-in. This is a candidate implementation, NOT a claim of device acceptance.
Compilation/testing must cover the complete change rather than treating the
previous disabled-service foundation as delivered adaptation.

Supported: V2419A / Android SDK35 / copilot versionCode6090021 with its pinned
current signer. The independent `vivo_text_bridge` consent defaults false. The
component resource denotes build capability, not consent. For this experimental
adapter, each new Eta process requires explicit reconfirmation of the switch;
old disk values alone can never grant access. The UI states this requirement.
UI writes framework
and Eta-local consent explicitly; any failed enable attempts revoke both. Turning
off locally revokes model access first, cancels active work and requests target
hook revocation. The feature does not change the default assistant, existing
custom-model settings or AgentRuntimeService's package/tool authorization.

Only com.vivo.ai.copilot is newly added to the Xposed recommended scope. The user
must enable that scope and restart small-V before first use. Secondary processes
and other vivo packages are not added.

## Verified native ABI and takeover point

The installed 6.9.0.21 APK was inspected as source AND raw DEX. A prior source-only
report incorrectly described a String parameter: the real seam is typed:

- `GatewayManager.g(Query, ContinuationImpl) -> Object` (instance; lifecycle-only observer before original call)
- `LinkParamsMapper.a(String, RemoteQueryRequest) -> ChatPayload`
- `LinkServer.m(ChatPayload, String linkId, kotlin.jvm.functions.Function2) -> void`
- `GatewayManager.a(String linkId, LocalIntent) -> void`
- `LocalIntent.TextAnswer(String dialogId,String text,String blockId,String voiceText,boolean showToolbar,boolean needRecognize)`
- `LocalIntent.Interrupt(String dialogId,String insertAfterBlockId,Integer interruptSource,String interruptType)`

RemoteQueryHandler creates StartConversation, StartDialog, the user bubble,
WaitDialog and show-stop-button BEFORE these mapper/dispatch calls. The adapter
records typed Query.Remote lifecycle before the native wait UI, then
correlates the exact ChatPayload OBJECT IDENTITY, not serialized JSON, packet
payload bytes, callback guesses or fake coroutine continuations. After claiming
a matching dispatch it never calls the original sender, retries, or falls back
to the vendor model. The existing read-only probe is not modified.

Eligibility conservatively uses native TextQueryModel fields: default little_v
agent, inputType0/default empty bizSource, manual rendered text, no attachments,
voice/special contexts, recommendation, shortcut, regeneration or skipRemote.
Additional intent/query parameters reject the request. Existing Agent-prefix
preference is honored without rewriting it. Unsupported native requests remain
native; this is not universal voice, attachment, skill or agent-tool takeover.
The real keyboard path must still be confirmed against these eligibility gates.

## Response and cancellation

One complete response becomes a single native TextAnswer with an explicit answer
block ID and empty voiceText (no extra TTS request). Its native executor emits
block, toolbar and EndDialog. An explicit hide-stop UiSignal clears the loading
control. Do NOT use EndDialogAndConversation: it also closes the conversation/link.

Native stop/stop-dialog and link teardown revoke matching ownership and cancel the
Eta request, including stops before the mapper/outbound dispatch. A process-lifetime
turn ledger retains cancellation; weak identity receipts remain fences for live
native objects and cannot expire into a vendor retransmission. Repeated active
calls coalesce, while finished duplicates return a bounded local error instead of
starting another model request. Runtime lifecycle mutations share one lock.
The client verifies stillOwner when its queued submit runs and again immediately
before committing Binder send; this fixes cancel-before-client-begin without
claiming that Binder send can be made atomic with any concurrent UI cancellation. Ownership is reserved synchronously before main-thread submission,
so observed cancellation is rechecked at the asynchronous binding boundary. Stale completions
cannot write into another dialog. Model errors become bounded native error text;
no raw exception, prompt, endpoint or API key is logged by the adapter.

Native emission is an asynchronous vendor operation. Source invocation alone does
not prove visible output, saved history, finalization or cancellation correctness.
Those remain device-test acceptance items.

## Trust boundary and limits

The service validates Message.sendingUid, exact singleton caller package, installed
version and signer. The client verifies Eta's singleton UID, service ownership and
release signer, then validates reply UID/request ID. Keys and provider endpoints
stay in Eta. The explicit selected provider/model must exist and be enabled;
selection repair/fallback is forbidden, changes during lookup are rejected.
Custom-body or extra-JSON overrides fail closed rather than reintroducing tools.
Only ModelFeatureCompletion is called, never AgentLoop or a tool executor.

Messenger: request1 = request_id + prompt; cancel2 = request_id; terminal3 =
request_id + code + optional text; rejection4 = request_id + code. Request IDs
are bounded ASCII, prompts at most4000 UTF-16 units, result at most16000 units,
nominal server timeout90s/output budget2048 tokens. One worker is allowed at a
time. Cancelled workers hold the server slot until they actually exit. An
uninterruptible worker therefore remains BUSY until process restart.

Process-lifetime replay limits retain256 IDs rather than evicting them; they do
not survive process death. No automatic retry after any unknown outcome. Client
unbind, server watchdog, callback death, one-terminal guards and stale reply
checks are implemented. Terminal callback delivery is best effort, not guaranteed.

## Validation record and remaining acceptance

- The previous metadata-only probe0.3 verified encode/send/decode calls, not model
  replacement. No repeat of that test or of old blocked-task recovery is needed.
- Foundation commit95d9a2a0 was pushed to main. Run36904696316 failed in an
  unrelated inherited BrowserUseManager.loadURL Unit-return mismatch before
  unit tests; the minimal fail-closed source fix is included in this integration.
- Full-source Python contracts and Kotlin/Android unit tests must be run on the
  final integration. All APK compilation goes through the existing original-
  signing GitHub Actions workflow via gh, and the final source goes to main.
- The client child was marked completed but its worktree diff was empty; it was
  discarded without being counted as implementation/review. The dispatcher then
  prohibited new tasks for this run, so remaining implementation/verification is
  performed by the parent. Earlier source reviews are not approval of new code.
- Required device acceptance: enable this single-device opt-in and module scope;
  one benign text request; confirm zero selected vendor dispatches, one Eta model
  request and visible native answer; next turn; cancellation; provider error;
  toggle-off; verify default assistant and other module scopes are unchanged.


## Full build and installation-gate review

- GitHub Actions36907842711 for248c286235789f1ce3486b7aac1cbf531a723d69
  actually succeeded. CLI status and downloaded XML show30 vivo Kotlin/Android
  tests,0 failures,0 errors. Initial direct TLS lookups timed out; the existing
  local mihomo listener127.0.0.1:2080 works with command-local HTTP(S)_PROXY.
  No global proxy, DNS or network setting was changed.
- Installation-gate reviews66cd004c andb55dfc0d identified lifecycle and failed
  consent-write risks. Follow-ups7e8f58e4 and75eda295 reviewed the hardening.
  Parent verified ABI/claims and fixed the confirmed queue-boundary and ledger
  issues; speculative callback-thread warnings were rejected using the client's
  actual main-Handler contract.
- Hardening adds a fresh-process consent latch, synchronous controller revoke
  with old-call-specific cleanup, pre-mapper native turn tracking, weak identity
  receipts, separate hide/answer exception paths, a common Runtime lifecycle lock,
  and two-boundary client ownership guards. In-flight network cancellation remains
  best effort, not proof that a server has undone an already received request.
-43 vivo Kotlin/Android tests now exist, including failed writes + new-process
  stale grants, pre-mapper stop, closed-window then global revoke, stale bind,
  duplicate sends and quota guards. These source additions require a new full CI
  run before APK installation. Python suite remains166 passing tests.
- Native GUI acceptance and default keyboard eligibility remain NOT VERIFIED.
  Do not label compilation, metadata-only probe calls or queued native emission as
  successful model replacement or visible answer delivery.


## Release 跨 ClassLoader 反射保名回归

- 真实旧 release 的 NativeApi 将目标侧 `kotlin.jvm.functions.Function2` 与 `kotlin.coroutines.jvm.internal.ContinuationImpl` 分别改写为 Eta 内部 `oh2`、`lh1`；目标 APK 只有原名，没有这两个别名。源码 ABI 对照和普通单元测试不足以发现此发布变换。
- 仅为这两个类型增加精确 `-keepnames`，不改用 Eta 的类字面量，不扩大 Kotlin 包保留范围，不修改签名、同意或调用边界。
- 发布上传前检查实际 mapping 和 APK DEX 字符串表；缺条目、改名、缺反射字符串或损坏输入拒绝交付。解析范围是本项目常规 R8 little-endian DEX 035/037–040（data 区到 EOF），不声称支持所有合法链接布局、041 容器或完整指令验证。
- 本地实际执行新增 22 项与全 193 项 Python 测试均通过。旧包配真实 mapping 被改名检查拒绝；旧包配明确标注 TEST-ONLY 的 identity mapping 也在完整 DEX 解析后因缺原始 ContinuationImpl 字符串被拒绝。测试用映射绝不作为发布产物。
- 仍须检查新 CI 结果与最终反射调用字面量，并单独完成实机 Hook、模型替换、原生回复及取消验收；本修复不代表实机接管已经成功。
