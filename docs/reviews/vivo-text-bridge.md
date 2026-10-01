# V2419A text-only bridge: disabled foundation, not completed adaptation

## Scope

This change adds an independent `VivoTextBridgeService` inside Eta. It does not
add vivo to `AgentRuntimeService`, the existing assistant entry-package allowlist,
Xposed scopes, or default-assistant settings. It does not alter the installed
metadata-only probe or intercept a vivo chat request.

The component AND its bind/message gates use `vivo_text_bridge_enabled=false`.
The normal APK deliberately cannot bind this service. Do not enable it until the
native client, finish/cancel behavior and explicit user opt-in have been tested.
No UI switch is supplied in this foundation change.

## Trust and model boundary

- Device: exact V2419A, Android SDK 35.
- Sender: primary-user application UID, exactly `com.vivo.ai.copilot` (no shared
  UID), installed version code 6090021 and one pinned current SHA-256 signer.
  The UID comes from `Message.sendingUid`, not a request field.
- The selected provider and selected model must both exist and be enabled. The
  explicit lookup does not repair settings, choose another model or change the
  assistant. A selection changed during configuration loading is rejected.
  OAuth resolution may refresh the existing provider's authentication normally.
- Missing config/credentials and non-empty custom request bodies/extra JSON fail
  closed. This prevents body overrides from reintroducing tools or changing the
  model after the provider builds its text-only request.
- Model credentials and endpoint stay in Eta. No request can choose them. The
  helper calls `ModelFeatureCompletion`, never `AgentLoop` or the tool executor.
  Messages contain only the submitted user text; no caller-provided system
  prompt, history, attachment, URL, model or tool definitions are accepted.

## Messenger v1 contract (not yet used by a released native client)

| what | Input/output fields | Purpose |
| --- | --- | --- |
| 1 | `request_id`, `prompt` | One text completion |
| 2 | `request_id` | Cancel matching caller/request |
| 3 | `request_id`, `code`, optional `text` | A single terminal result for accepted work |
| 4 | `request_id`, `code` | Admission/protocol rejection; not a second terminal result |

IDs match `[A-Za-z0-9_-]{1,64}`. Prompt length is at most 4000 UTF-16 code units;
blank text and NULs are rejected. The nominal timeout is 90 seconds, output budget
2048 tokens, and accepted returned text at most 16000 UTF-16 code units.

One model worker is allowed per process. Cancellation, timeout and caller death
cancel the model controller, interrupt the worker and suppress late results.
The busy slot remains occupied until the worker exits. The process-lifetime
ledger retains at most 256 IDs and then refuses new work rather than evicting
replay protection. This is NOT durable deduplication across process death.
The future client must not automatically retry a request whose outcome is
unknown, including after Eta process death. Terminal callback delivery is
best-effort; a dead callback cannot be treated as receiving a result.

Unauthorized callers are ignored. Provider errors return fixed codes; the bridge
has no raw prompt/endpoint/key/exception logging and no retry/fallback logic.

## Evidence and unfinished work

Probe 0.3.0 previously observed real codec encode/socket send/decode metadata on
installed copilot6090021 (data/data_ack/flow_start/data_flow). That evidence proves
transport calls, not custom-model substitution or native answer completion.

Still required before enabling: native request interception eligibility; ownership
of the native conversation/block IDs; original UI reply insertion; cancellation,
stream-end and failure finalization; authenticated IPC/device tests; rate/consent
policy for general use. This commit is a guarded foundation, NOT end-to-end support.

Tests cover exact admission, protocol lengths/types, replay/busy boundaries,
explicit model selection and text-only guards, plus default-disabled Android
binding/manifest behavior. Local Python source contracts are supplemental;
Android/Kotlin compilation and tests are run by GitHub Actions, not a local SDK
substitution. No successful build/device claim may be made before its result.

## Review resolutions

The gateway review found no definite Kotlin/nullability blocker. Broad body-override
rejection is intentional (including `{}` and harmless override keys); the existing
ModelConfig default for extraBodyJson is blank, and this explicit repository builder
does not set it. No stored config is rewritten to satisfy this rule. All current
ModelConfig tool capability flags are disabled; custom body and extra-body JSON
are refused and only empty tool definitions reach ModelFeatureCompletion. Its
existing tool-call/finish-reason guards remain in effect. Config/OAuth exceptions
propagate only to the service's fixed-code boundary, not back as exception text.
Custom-body, blank model-ID, assistant-ID and exception/no-fallback tests were
added to cover the review gaps. This is source review, not real-device acceptance.

The bounded service review found no direct source-level compilation blocker.
The process-wide busy slot is deliberately NOT freed on cancellation/destruction
before the worker exits: freeing it early could allow concurrent charged requests.
A genuinely uninterruptible worker can therefore keep BUSY until process restart;
this limitation and the 256-ID process limit are accepted for this disabled
single-device foundation, not a production availability claim. A terminal result
is emitted at most once; successful delivery is not guaranteed. The review's
possible Handler(Looper) deprecation warning was checked against the SDK class
metadata and does not apply to that explicit-looper overload. Package APIs used
are below this project's minSdk34; actual compilation still belongs to CI.
