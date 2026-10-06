# StreamDiag v2: bounded foreground attribution

This is a diagnostic-only upgrade. Existing event delivery, Snapshot publication,
normalization, animation and scrolling order are unchanged. Collection is gated by
file logging and a foreground root-window session. The `id` is a diagnostic window
session, **not** an agent run ID. An interval is reported approximately every 5s.

## Fixed schema

After the logger prefix, lines begin `StreamDiag id=<8hex>`.
New detailed lines share `windowStartNs`, `windowEndNs`, `final=true|false`,
`v=2` and one of the following `type` values. Legacy aggregate/probe lines remain.

* `type=window`: `anchorNanoNs uptimeMs elapsedRealtimeNs package versionCode
  versionName buildType duration=inclusive heap=proxyNotAllocationStack
  gcTime=runtimeCounterNotPause spanCapacity=2048 slowBudget=256 frameBudget=120
  ringOverwritten slowBudgetDropped frameBudgetDropped spanOutputTruncated
  tokenSaturated eventLinksOverwritten mainRingOverwritten mainOutputTruncated
  noteBudgetDropped`.
  Package/version lookup is cached on the worker. Unavailable identity is `unknown`.
* `type=span`: `span parent stage beginNs endNs thread main duration=inclusive
  value runToken conversationToken visibility kind replay eventSeq sourceSpan
  page pageEnd`.
* `type=frame`: `abnormalFrame page pageEnd pageChanged pageSource=route
  intendedVsyncNs vsyncNs totalNs deadlineNs deadlineMiss metricsDropped
  unknownNs inputNs animationNs layoutNs drawNs syncNs commandNs swapNs gpuNs`.
* `type=runtime`: `runtimeCounter supported`; supported counters also carry
  `cumulative` and `delta` (`unknown` for the first observation or a reset).
  Allowlisted counters are `art.gc.gc-count`, `art.gc.gc-time`,
  `art.gc.bytes-allocated`, `art.gc.bytes-freed`, `art.gc.blocking-gc-count`,
  `art.gc.blocking-gc-time`. Each unsupported counter emits `supported=false`.

Tokens and sequence numbers are integers; unknown tokens are `0`.
Visibility is case-sensitive `Unknown|Selected|Hidden`. Page values match the
compiled `FrameDiagnosticPage` names (`Chat`, `Settings`, `Transition`, `Unknown`,
etc.). Stage and event-kind labels are compiled allowlists. Unknown labels in
supported fixed families collapse to `<family>.unknown`, never the caller suffix.
Raw business IDs, content, tool parameters and titles are never formatted. Identity
cache is bounded to 256 entries in memory only, cleared with its foreground session.
Replay/event links are bounded weak identity references; overflow is explicit.

Span durations include nested durations and are **not additive**. Parent IDs can
refer to short/overwritten spans not emitted. The ring retains 2048 completed stages;
output selects slow (>=4ms) spans and main-thread spans overlapping abnormal frames,
up to 256 records per interval. An abnormal frame is any valid deadline miss,
including <33ms, or a >=33ms spike. Frames reserve a 120-record budget before
allocating detailed records. Looper counts and bounded slow-message retention now
include doFrame messages. Old 16/32/50/100ms buckets retain their old meaning;
additional 8.33/16.67/33.33/50/100ms buckets are separate.

Window boundaries are actual collector-drain times, not fabricated 5-second slots.
Completed spans are attributed to their drain window; a long span can begin before
it. Delayed FrameMetrics keep their exact timestamps and timestamp-resolved pages.
Cross-window/callback delay and ring overwrite can reduce frame-to-span matches;
absence of matching application spans is not proof of absence of work. Route
transitions are aggregated as `Transition` rather than assigned to the later page.

## Integration API for render/persistence work

```kotlin
val attribution = StreamPerformanceDiagnostics.attribution(
    runId = existingRunId,
    conversationId = existingConversationId,
    selected = existingConversationId == selectedConversationId,
)
StreamPerformanceDiagnostics.withAttribution(attribution) {
    StreamPerformanceDiagnostics.measureDetail("persistence.write", byteCount) {
        existingSynchronousWrite()
    }
}
```

Capture on the originating synchronous task and explicitly pass to an **existing**
background task; do not add dispatches or change business scheduling:

```kotlin
val captured = StreamPerformanceDiagnostics.captureAttribution()
// Inside the existing withContext(Default), after any suspension:
StreamPerformanceDiagnostics.withAttribution(captured) {
    StreamPerformanceDiagnostics.measureDetail("markdown.parse", contentLength) {
        existingParse()
    }
}
```

`withAttribution` and `measureDetail` must not span coroutine suspension. Thread-local
contexts restore in `finally`. Disabled wrappers avoid identity hashing, clocks and
traces. `record(fixedStage, ns, value)` retains legacy bounded aggregates.
When there is no originating event context or no already-available owner identity,
render hooks should report unknown, not scan complete message history for diagnostics.

Heap/counter observations are proxies, not allocation stacks. Runtime GC time is
not equated with stop-the-world pause duration. Handwritten hooks cannot reveal all
Compose internal method stacks. Explicit short Perfetto/heapprofd captures and any
profileable manifest change belong to the independent device-capture phase; no
sampling agent or always-on allocation capture is started here.

## Validation

Pure Kotlin `BoundedStreamDiagnosticsTest` covers scope restore/nesting, explicit
cross-thread capture, anonymous token stability/saturation, ring overwrite, budget
reset, exact overlap boundary, truncation and fixed label privacy. Source guards
check IPC/UI wiring, schema, runtime counters and deadline-miss independence.
No tests were executed by this implementation agent (workspace file tools only).
The parent must run independent Kotlin/CI validation and Android build review.
