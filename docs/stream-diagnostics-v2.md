# StreamDiag v2: bounded foreground attribution

This is a diagnostic-only upgrade. Existing event delivery, Snapshot publication,
normalization, animation and scrolling order are unchanged. Collection is gated by
file logging and a foreground root-window session. The `id` is a diagnostic window
session, **not** an agent run ID. An interval is reported approximately every 5s.

## Fixed schema

After the logger prefix, lines begin `StreamDiag id=<8hex>`.
New detailed lines share `windowStartNs`, `windowEndNs`,
`boundary=admissionSnapshot`, `final=true|false`, `v=2` and one of the following
`type` values. Legacy aggregate/probe lines remain; aggregates use the same snapshot boundary.

* `type=window`: `anchorNanoNs uptimeMs elapsedRealtimeNs package versionCode
  versionName buildType duration=inclusive heap=proxyNotAllocationStack
  gcTime=runtimeCounterNotPause spanCapacity=2048 slowBudget=256 frameBudget=120
  ringOverwritten slowBudgetDropped frameBudgetDropped spanOutputTruncated
  tokenSaturated eventLinksOverwritten mainRingOverwritten mainOutputTruncated
  noteBudgetDropped admission=open|closed openSpansAtCutoff closedRejectedRecords
  lateSpans postCloseObservation=notTracked`.
  Package/version lookup is cached on the worker. `versionName` is accepted only by
  `[0-9]{1,4}(?:\.[0-9]{1,4}){1,3}`; everything else (including suffixes, commit
  hashes and Unicode) is `unknown`. `anchorNanoNs` is a worker clock observation
  after snapshot selection, not the admission cutoff; `windowEndNs` is the cutoff.
  `noteBudgetDropped` is session-cumulative and reserves before the detail lambda.
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

Window boundaries are actual **admission snapshots**, not fabricated 5-second slots
or half-open completion-time partitions. The cutoff clock is read inside the shared
session lock; aggregate, per-page and primitive detail columns are snapshotted
atomically. A span admitted after the cutoff belongs to the next snapshot even if
its `endNs` precedes `windowStartNs`. Long spans can also begin before their window.
The bounded column copies/reset run under the lock; the up-to-2048-by-120 overlap
search, span construction, formatting and logging run outside it. Detached stats
are no longer mutated by new admissions. Delayed FrameMetrics keep their exact
timestamps and timestamp-resolved pages.

Detach takes one final snapshot and immediately closes admission, before worker
logging. `admission=closed` denotes that final cutoff. `openSpansAtCutoff` is the
number of begun diagnostic scopes not yet completed; it is not an emitted span
count. Captured-session record attempts after close increment `closedRejectedRecords`;
completions of already-open spans increment both that counter and `lateSpans`,
without inserting aggregate or detail records. Only counts known **at the snapshot**
are printed. No service waits for remaining spans, no post-close log is scheduled,
and `postCloseObservation=notTracked` explicitly means later rejections/completions
are not observed in the emitted final line. In particular, `lateSpans=0` does not
claim complete accounting after detach; calls without a captured session cannot
be counted either. Residual detail storage cannot accumulate after close.
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
traces. `record(fixedStage, ns, value)` retains legacy bounded aggregates (512 fixed
stage slots per window). All 56 fixed markdown/reveal/checkpoint/settings/usage
labels used by the integration are preserved individually rather than collapsed.

Recorders spanning callbacks must capture `currentSessionToken(): Long?` at creation
and flush with `recordForSession(expectedSession, fixedStage, ns, value)`. The token
is null when logging is disabled or the session is closed. A generation-safe write
captures active once, checks enabled/closed/serial, and only writes that captured
session; it cannot redirect old work into a new foreground window. A stale
`withAttribution` still executes its business block, so it is **not** a guard for a
subsequent global `record` call.
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
reset, exact overlap boundary, detached selection outside the admission lock,
post-close detail rejection, truncation and all 56 fixed labels. Cross-thread
assertion failures are rethrown on the JUnit thread. `StreamPerformanceDiagnosticsTest`
adds a deterministic shared-lock cutoff/admission race (including a late end
timestamp), atomic stats/detail membership, immutable detached aggregates, final
open-span counts/no residual admissions, lazy note budget and numeric version
allowlist checks. Source guards check IPC/UI wiring, schema, runtime counters and
deadline-miss independence.
No tests were executed by this implementation agent (workspace file tools only).
The parent must run independent Kotlin/CI validation and Android build review.
