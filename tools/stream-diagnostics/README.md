# Eta stream diagnostics: local, opt-in, offline

This prepares detailed measurement, **not an optimization or an acceptance result**.
Nothing here starts a profiler, collects logs, uploads files, installs an APK or
runs persistently. The only app change in this module is
`<profileable android:shell="true" />` inside `application`; no `debuggable` flag,
new permission, exported activity, business/visual or persistence change.

## Canonical capture and acceptance boundary

For a 45-second unsampled capture, use `docs/diagnostics/eta-jank.perfetto`.
The longer and CPU-only profiles below are separate diagnostic passes, not a
matched performance baseline. **No automatic ON/OFF success oracle is supplied.**
Missing counters, missing source coverage, an unclosed session, or missing proof
of either effective ON/OFF setting remain unknown and must not be replaced with
zero. The [on-device acceptance gates](#on-device-acceptance-gates) below are
mandatory before matched comparisons or per-frame causal attribution.

On this Root/KernelSU Android device, passing a caller-opened output FD with `-o`
can be rejected by the tracing service. Generate a private copy of the canonical
config, append one `output_path: "/data/misc/perfetto-traces/eta-UNIQUE.pftrace"`,
where the destination does not already exist, and run:

```sh
perfetto --txt -c /data/local/tmp/eta-UNIQUE/capture.pbtxt
```

Do **not** pass `-o` for these captures, force Android
`primary_trace_clock: MONOTONIC`, disable SELinux, or overwrite an existing trace.
Default Android trace time is BOOTTIME; map the app nanoTime window using measured
clock bridges at both ends and actual PID/Linux TID, not Java Thread.id.
Preserve exact APK hash, Git SHA, signing certificate, process lifetime, ON/OFF
key readback and actual absence/presence evidence with each matched reproduction.

Before attribution, inspect complete stats (including `*overwrit*`, per-buffer
and per-CPU loss), source-specific time ranges, and emitted final completion.
Trace bounds alone do not prove all 45 seconds survived. Unfinished slices,
negative timestamps, truncation and unknown symbols require explicit accounting;
any exception must be tied to the exact affected source/process, not suppressed.
The exploratory 45-second probe is not a matched long-text performance result.

Analyzer `--output` uses exclusive creation: choose a new path. Existing files,
input aliases and symlinks are refused to preserve original evidence.

## Evidence and limits of validation

- The inherited 180-second config is a **legacy compatibility profile**, not
  the canonical baseline. The 45-second canonical config now includes its four
  block/direct-reclaim events; actual device/kernel support is **unverified**.
  The 30-second config targets the main process and enables CPU sampling only.
- The inherited manifest review reports no `android:process` on
  `AgentRuntimeService` or `AgentExecutionService`, and no application default
  override: these are in
  **`io.github.mangi.eta`**. `:voice`, `:voice_session`, `:recognition` are separate
  voice-related processes, not the agent runtime. Recheck the installed merged
  manifest and `adb shell ps -A` if another build changes this.
- Parent-reported Android Perfetto **1-second baseline smoke** produced
  **30,274 sched rows and 93 FrameTimeline rows**. It also reported
  **`trace_sorter_negative_timestamp_dropped=1`, severity `error`**. This is a
  compromised-data health finding, **not zero loss**, not a 180-second workload
  measurement and not evidence about Eta's actual jank.
- Parent-reported sampled-config 1-second smoke used a deliberately nonexistent
  target: **syntax accepted only; no real CPU stacks were demonstrated**.
  This CPU-only config cannot demonstrate allocation counts or allocation stacks.
- This implementation agent has no shell/Android execution capability. The
  Python unit tests, SQL schema compatibility, final same-build core golden
  fixture, installed `profileable` permission, real samples and the full two
  captures must be checked by the integrating agent/operator. Source, a passing
  build, config parsing and fake fixtures are not on-device acceptance.

## Two separate capture passes (commands to run manually, not executed here)

Use a trusted local USB-connected device and the final same-build APK. Local
traces can contain system process/thread names and stack symbols; do not upload
them casually. These configs do **not** enable `android.log`, full logcat,
network/prompt/reply/audio payload capture or heap object dumps. Pass 2 samples
CPU callstacks only, not allocation counts, sizes, stacks or contents.

### 1. Canonical timeline, 45 seconds

Use `docs/diagnostics/eta-jank.perfetto`. The 180-second
`config/eta-jank-timeline.pbtxt` is legacy, not the recommended baseline; if used
for exploration it still requires the same new-config/new-output procedure.
Neither profile alone constitutes a matched long-text ON/OFF result.

For **each** run (including each ON/OFF repetition), start a new private host
workspace and a new private device config directory. The following host-side
POSIX-shell example is manual, from the repository root with `adb` authorized.
Stop on any error. Inspect the generated config: exactly one `output_path` must
be present; refuse a template that already defines one. A destination whose
existence cannot be checked with authorized device access is **unknown**, not
available. Do not weaken SELinux to inspect or write it.

```sh
# Select ONE profile for this run. For pass 2, use the path given below instead.
PROFILE=docs/diagnostics/eta-jank.perfetto
umask 077
CAPTURE_DIR=$(mktemp -d "${TMPDIR:-/tmp}/eta-capture.XXXXXXXXXX") || exit 1
CAPTURE_ID=${CAPTURE_DIR##*/}
DEVICE_DIR=/data/local/tmp/$CAPTURE_ID
TRACE_PATH=/data/misc/perfetto-traces/$CAPTURE_ID.pftrace
cp "$PROFILE" "$CAPTURE_DIR/capture.pbtxt" || exit 1
printf '\noutput_path: "%s"\n' "$TRACE_PATH" >> "$CAPTURE_DIR/capture.pbtxt" || exit 1
printf 'profile=%s\ndevice_config=%s/capture.pbtxt\ntrace=%s\n' \
  "$PROFILE" "$DEVICE_DIR" "$TRACE_PATH" > "$CAPTURE_DIR/paths.txt" || exit 1
# The directory must be inspectable; a new mkdir also refuses config-dir reuse.
adb shell "test -d /data/misc/perfetto-traces && test -x /data/misc/perfetto-traces && test ! -e '$TRACE_PATH' && test ! -L '$TRACE_PATH' && mkdir -m 700 '$DEVICE_DIR'" || exit 1
adb push "$CAPTURE_DIR/capture.pbtxt" "$DEVICE_DIR/capture.pbtxt" || exit 1
adb shell chmod 600 "$DEVICE_DIR/capture.pbtxt" || exit 1
adb shell perfetto --txt -c "$DEVICE_DIR/capture.pbtxt" || exit 1
# Reproduce streaming/scrolling during the foreground capture, e.g. use another
# terminal/device. Wait for completion; preserve any producer/service errors.
test ! -e "$CAPTURE_DIR/trace.pftrace" && test ! -L "$CAPTURE_DIR/trace.pftrace" || exit 1
adb pull "$TRACE_PATH" "$CAPTURE_DIR/trace.pftrace" || exit 1
```

There is **no `-o`**: the tracing service owns the fresh output path. Keep the
private config, path manifest and original trace with this run's evidence.
Do not reuse these paths, overwrite an existing trace, or force Android
`primary_trace_clock: MONOTONIC`. Ring buffers and file caps can overwrite or
truncate history. Check all stats, file completeness, final closure, source and
per-frame window coverage; requested duration and trace bounds are not proof.

Cleanup is optional and manual. Only after checking the pull's byte count/hash
against the device original and preserving the evidence, confirm that the two
remote files are exactly those created by **this** run in `paths.txt`. Use that
run's retained variables, not variables reassigned for a later pass. Then delete
only those confirmed files; never fixed legacy paths, globs, or recursive trees:

```sh
adb shell rm -- "$DEVICE_DIR/capture.pbtxt" "$TRACE_PATH"
adb shell rmdir -- "$DEVICE_DIR"
```

### 2. Short CPU-sampled stacks, 30 seconds, separate reproduction

Repeat the entire fresh-workspace procedure above, replacing its `PROFILE`
assignment with:

```sh
PROFILE=tools/stream-diagnostics/config/eta-jank-sampled-stacks.pbtxt
```

Pass 2 is **CPU-only**: `linux.perf` CPU-clock **99 Hz** callstack sampling.
It does not enable heapprofd, ART heap snapshots, or allocation-stack capture.
It is **not** an ON/OFF pair and does **not** provide stacks of the same frames
from pass 1, even if the workload is repeated. A within-pass sample/frame link
still requires that trace's own calibrated window and process/thread identity.
Supported Android/ART build, profileable permission, producer availability and
unwind/symbol support are prerequisites. Present-but-empty tables,
target-window zero samples, missing tables, unresolved stacks and producer
errors are different findings; none means “CPU did no work” or “no allocations”.
Startup/runtime restrictions may require a manually recorded app restart;
do not silently automate it.

Sampling and diagnostics add scheduling, unwinding, memory and I/O disturbance.
Do not combine pass 2 FPS/jank into the unsampled baseline or compare mismatched
workloads as a performance improvement. CPU samples are statistical, not exact
method coverage or allocation evidence. CPU, GC cycles, GPU and nested Eta
stages overlap. **Their durations cannot be precisely added into a single frame
or response time**. A sampled hot stack is investigative evidence, not by itself
proof of the first pass's frame cause.

### Profileability and symbols

Official references (provided/checked by parent; not browsed by this agent):

- https://developer.android.com/guide/topics/manifest/profileable-element

`profileable` permits trusted local shell profiling on a user build (needed for
local stack profiling on the reported Android 15 device), not arbitrary app
memory reading and not debugging. No heap producer is enabled in the CPU-only
pass. The app's `heap=proxyNotAllocationStack` counter is only a runtime heap
proxy, not allocation counts or an allocation stack trace.

Keep R8 `mapping.txt`, native unstripped ELF/debug symbols/build IDs, source
revision and the exact installed **same APK** together. Another build's mapping
can produce wrong attribution; obfuscated/unresolved names remain unknown.
Do not decode anonymous conversation/run tokens back to user identifiers.

## SQL analysis: explicit trace-time window, explicit UPID

Use a compatible local `trace_processor_shell` binary; it is not bundled or
installed by this tool. Save its version. The examples below use the verified
**v58.2** `query` subcommand; older versions may use different flags. Check
`query --help`, and never reinterpret an SQL string as a filename. Set
`TIMELINE_TRACE` to the pulled trace in the selected timeline run's private
workspace (and `SAMPLED_TRACE` to a separate sampled run's trace below). Retain
the associated path manifests; do not overwrite/rename old evidence. For example:

```sh
trace_processor_shell query -f tools/stream-diagnostics/sql/diagnostic-integrity.sql "$TIMELINE_TRACE"
trace_processor_shell query "$TIMELINE_TRACE" 'SELECT start_ts,end_ts FROM trace_bounds'
# Replace numbers with actual TRACE-TIME ns and the app UPID from integrity SQL.
python3 tools/stream-diagnostics/analyze.py render-sql --template window-analysis --start-ns 123000000000 --end-ns 124000000000 --upid 42 --output window.sql
trace_processor_shell query -f window.sql "$TIMELINE_TRACE"
```

Do **not** use those example numbers as a real measurement or omit bounds and
silently analyze the whole recording. PID is not UPID; PID may be recycled.
SQL stats and integrity inventory counts are trace-wide even when stage/frame
queries select a window. Global `sched`/FrameTimeline row totals, first-to-last
source spans and overall trace bounds do **not** establish per-frame coverage
or continuity. Check rows and missing intervals on each target thread/frame.
`diagnostic-integrity.sql` prints **all** stats including zeros, indexed per-CPU
entries and warnings/errors/loss/overwrite/truncation entries. Keep these with
any conclusion. A missing expected statistic is unknown, not zero. The known
negative-timestamp smoke error above must stay in its report.

`window-analysis.sql` reports:

- App surface FrameTimeline rows beginning in `[start,end)`, separately by layer
  and jank reason. **App Deadline Missed** comes from FrameTimeline `jank_type`,
  not a fixed 16 ms cutoff or a doFrame duration. Multiple surfaces are not
  automatically unique app frames; null reasons/unfinished rows are explicit.
- Main/RenderThread raw `thread_state`, clipped to the window: `Running`,
  `R/R+` Runnable, and other states as `Blocked_or_sleeping` (not all lock waits).
  Missing thread/state coverage is unknown, not smooth idle. Inspect raw D/S
  states, binder/reclaim/block tracks and thread scheduling for finer causes.
- Main synchronous `doFrame` separately, not equated with FrameTimeline misses.
- GC-specific pause labels separately from inclusive concurrent cycles.
  Unrecognized GC/SuspendAll labels remain unknown; runtime GC time counters are
  **not stop-the-world pause times**. GC labels differ across ART versions.
- A fixed `Eta.<stage>` layer mapping; inclusive parent/child/parallel times must
  not be added. New Eta stage labels become `unknown`, not guessed network or
  renderer attribution. UI delivery gaps do not prove network stalls.

After integrity confirms the optional table exists, run sampling templates on
**pass 2**, with that trace's UPID/window (not pass 1's IDs):

```sh
python3 tools/stream-diagnostics/analyze.py render-sql --template cpu-samples --start-ns 123000000000 --end-ns 124000000000 --upid 42 --output cpu.sql
trace_processor_shell query -f cpu.sql "$SAMPLED_TRACE"
```

The CPU query distinguishes present-but-zero trace rows from target-window
sample counts and missing callsites. Missing-table SQL errors must be labelled
unsupported/missing, **never replaced by zeros**. Heap/allocation queries are
not applicable to either capture profile above. SQL/schema and runtime labels
must be checked on the actual trace processor.

## StreamDiag v2 JSON exporter

Only feed an **already extracted file containing exact diagnostic lines**.
The tool does not read AppFileLogger directories or dump whole logs. It does not
accept arbitrary timestamp/logcat prefixes, legacy aggregates/notes, quoted
free text, prompts, replies, URLs or raw IDs. Unknown keys or enum values reject
the **whole line**; errors contain reason codes, not rejected fields/values.
A safe summary may be written with rejection counts, but the CLI exits **2** on
rejected or exporter-truncated records. Exit **0** is parser success only, not
capture health, privacy provenance or performance acceptance.

The supplied core interface is parsed strictly:

- Common: `StreamDiag v=2 type=window|span|frame|mainMessage|runtime id=<8 lowercase hex>`
  `windowStartNs=<long> windowEndNs=<long> final=true|false`. Diagnostic `id` is
  immediately re-tokenized into `s1`, `s2`, ...; its reverse mapping is not exported.
- Window: exact `anchorNanoNs`, `uptimeMs`, `elapsedRealtimeNs`, fixed `package`,
  numeric `versionCode` (or exactly lowercase `unknown`, exported as null),
  numeric dotted `versionName` (or `unknown`), fixed `buildType`,
  `boundary=admissionSnapshot`, `duration=inclusive`, `heap=proxyNotAllocationStack`,
  `gcTime=runtimeCounterNotPause`, capacities/budgets and the reviewed integrity
  counters listed in `analyze.py`. Missing fields are null, **not assumed zero**.
  An admission snapshot is the collection drained on this report, **not** a
  half-open completion-time filter. Its spans can begin before or end after the
  report window; do not discard those records or claim exact temporal coverage.
- Span: numeric `span/parent/beginNs/endNs/thread/value/runToken/conversationToken`
  `eventSeq/sourceSpan`, fixed `page/pageEnd` from the 42 `FrameDiagnosticPage`
  names (case-sensitive, not ordinals), bool `main/replay`, fixed `stage/kind`,
  `visibility=Unknown|Selected|Hidden`, **only** `duration=inclusive` (numeric
  durations reject). Inclusive begin/end durations are clipped for selection;
  exact original values/links/tokens remain in records.
- Frame: exact abnormal-frame index, route `page/pageEnd/pageChanged/pageSource`,
  intended/actual vsync, total/deadline and component nanoseconds, `deadlineMiss`
  and `metricsDropped`. Deadline miss is **FrameMetrics**, not App FrameTimeline.
  Route page is not proven to be the rendered message's conversation.
- Runtime: fixed `runtimeCounter`, bool `supported`, `cumulative/delta` integer
  or `unknown`. Unsupported must have unknown/missing values. These are runtime
  counters, not GC pause spans.

Fixed `stage`, `kind`, `page/pageEnd`, `boundary` and `runtimeCounter` allowlists
are intentionally restrictive. Stages include the reviewed core labels,
`ui.event.<fixed kind>`, `frame.page.<fixed page>`, the 56 render/data/recorder
labels, and only the explicitly compiled canonical `*.unknown` labels. Kinds
are exact emitter labels such as `delta.text`, `start.toolCall`, `replayBatch`
and `unknown`; fake `Delta`, `Unknown`, `gcCount` and arbitrary suffixes reject.
ART counters are exactly `art.gc.gc-count`, `art.gc.gc-time`,
`art.gc.bytes-allocated`, `art.gc.bytes-freed`, `art.gc.blocking-gc-count`, and
`art.gc.blocking-gc-time`. The synthetic golden fixture is calibrated against the reviewed f9f5713b emitter;
this validates schema spelling, not actual on-device capture. Window fields
`admission=open|closed`, `openSpansAtCutoff`, `closedRejectedRecords`, `lateSpans`,
and `postCloseObservation=notTracked` describe the snapshot cutoff. Final reports
do not observe subsequent completions; zero is not proof that none occurred. Do not make these arbitrary strings or regex-only acceptors to “fix”
a rejection. Both exporter and emitter must limit versionName to exactly
`[0-9]{1,4}(?:\.[0-9]{1,4}){1,3}` or `unknown`; Unicode, arbitrary revision and
custom version labels must be redacted upstream to `unknown`, not made exportable. Numeric anonymity is an **emitter contract**: a parser cannot
prove that a digit-only token was not a raw ID maliciously put in the wrong field.
No joins, hashing of user data, reverse lookups or guessed token ownership occur.

Example (replace bounds with actual **System.nanoTime ns**, not trace/wall time):

```sh
python3 tools/stream-diagnostics/analyze.py summary extracted-streamdiag-v2.txt --start-ns 123000000000 --end-ns 124000000000 --output summary.json
# Optional: export this dedicated query in trace_processor's CSV output format.
# Noclobber: an existing stats.csv is evidence, not a reusable output target.
(set -C; trace_processor_shell query "$TIMELINE_TRACE" 'SELECT name,idx,value,severity,source FROM stats ORDER BY name,idx' > stats.csv) || exit 1
python3 tools/stream-diagnostics/analyze.py summary extracted-streamdiag-v2.txt --start-ns 123000000000 --end-ns 124000000000 --stats-csv stats.csv --output summary-with-health.json
python3 -m unittest discover -s tools/stream-diagnostics -p 'test_analyze.py' -v
```

CSV import requires exactly `name,idx,value,severity,source`; if your processor
emits presentation banners/separators, export a dedicated CSV with that header,
not a mixed multi-query console transcript. Import preserves zero rows and
warnings/errors plus the exact dropped timestamp counter. No stats file/empty
stats is `missing_stats`; no nonzero reported problem is **not loss-free proof**.

Records are selected by overlapping `[start,end)` intervals. Window/runtime
records retain their original interval and exact non-prorated counters, even
when partly outside selection. Spans are grouped by exact run/conversation/
visibility/start-page/end-page; missing attribution stays null. Window coverage
is a union, not an inclusive sum. Duplicate input is retained visibly and is not
silently deduped; avoid concatenating overlapping log exports.

Clock anchors are preserved exactly. `System.nanoTime`, uptime milliseconds,
elapsed realtime and trace timestamps are **different domains**; do not simply
paste the Python bounds into SQL. Sleep, ms quantization, anchor read skew and
trace clock conversion require explicit correlation and uncertainty. Frame
intended-vsync-domain compatibility also needs a golden/device check. The tool
makes no verified trace-alignment claim and never defaults to full trace.

## Where did these frame times go? (v2 gap analysis)

The supplied 041900cc / 5.3.8 / 2026100406 evidence is from application file
logs, not a verified FrameTimeline trace: 97 frames with `unknownNs >= 8ms`,
59 without retained main spans; separately, 61 frames with `totalNs > 33ms`,
32 without coverage. The narrower intersection (`>33ms`, `unknown>=8ms`, no
coverage) is **17**, not 59 or 32. These denominators must not be interchanged.
`mainRingOverwritten` reached 4542, `ringOverwritten` 5107,
`spanOutputTruncated` 818, and `eventLinksOverwritten` 651; zero slow/frame budget
drops do **not** cancel that loss. Retained-span gaps can overestimate the actual
instrumentation gap. This change is diagnostic preparation, not a new capture
or proof that those original frames are now attributed.

Use the same strict extracted v2 input as `summary`:

```sh
python3 tools/stream-diagnostics/analyze.py where-time extracted-streamdiag-v2.txt --start-ns 123000000000 --end-ns 124000000000 --output where-time.json
# Optional, fixed-schema inventory independently checked with your trace processor:
python3 tools/stream-diagnostics/analyze.py where-time extracted-streamdiag-v2.txt --start-ns 123000000000 --end-ns 124000000000 --table-status-json table-status.json --output where-time-with-tables.json
```

`whereTime.frames` reports **two overlapping axes, never one additive pie chart**:

1. FrameMetrics components and the signed residual accounting below.
2. Union of retained `main=true` span intervals clipped to the frame interval,
   reveal subset, observed time outside reveal, and time with no retained span
   coverage. Nested/duplicate intervals are unioned, not added. A duration named
   `unknownNs` is **not** the uncovered span duration and is not assumed to occupy
   an invented prefix of the frame interval.

The new `mainMessage` records preserve numeric-only slow Looper dispatch
`beginNs/endNs/frameDispatch/coveredNs/revealNs/uninstrumentedNs/nonRevealNs`
and fixed `accounting=dispatchSubsetsNotAdditive`. They do not export class names,
message names, mount numbers, business IDs, or text. `coveredNs` sums only live,
synchronous outermost measurements, independent of whether the span detail ring
later loses those spans. `revealNs` counts only outermost reveal scopes (including
reveal nested inside another stage). `uninstrumentedNs = dispatch - covered`;
`nonRevealNs = dispatch - reveal`, so **uninstrumented is contained in nonReveal,
and reveal is contained in covered; do not add them**. These are **dispatch wall
times**, potentially including scheduling/waits/diagnostic overhead, not CPU
self-time. No timing scope is left installed across suspension.

Only dispatches >=4ms are retained in the bounded 256-entry main ring. Main
records are selected by overlap with the reporting window, unlike the span
admission/drain set; a boundary-crossing record can appear twice. Full dispatch
subset counters are not prorated onto its overlapping portion. Fast dispatches,
messages open at detach, work before printer installation, and overwritten
records remain unobserved. `frameDispatch=true` means the Looper callback was a
Choreographer FrameDisplayEventReceiver; **`main.doFrame` is this dispatch envelope,
not a FrameTimeline slice, a unique displayed frame, or a deadline miss**. The
script keeps the FrameTimeline result unavailable until independently supplied
trace analysis; an inventory row count alone is not a jank result.

Frame detail admission now also includes `unknownNs >= 8_000_000`, even without a
reported deadline miss or a >=33ms total. It uses the existing 120-frame budget,
not an unbounded event stream. `recordIntegrity.status` distinguishes
`records_lost_or_truncated`, `integrity_unavailable`, and
`no_loss_reported_not_complete_coverage`. The counters belong to overlapping report
windows (main overwrite count is cumulative), not a proved loss on that exact
frame. `gapMeaning` retains this distinction: absence of evidence is neither
idle time nor proof that Compose caused the gap.

### Residual / overlap / vsyncLate: containment, NOT additive

Let `parts = unknown + input + animation + layout + draw + sync + command + swap`.
Then `residual = total - parts`, `unaccounted = max(residual, 0)`, and
`overlap = max(-residual, 0)`. GPU is excluded from `parts` because it overlaps
command/swap. `frame.unaccounted` is the nonnegative **residual within total**, not
a decomposition of `unknown`. `frame.overlap` is excess component accounting,
**not extra time**. `frame.vsyncLate = max(vsync - intendedVsync, 0)` describes a
lateness interval that overlaps/is ordinarily contained in unknown delay; it is
**not an additional component**, nor a guarantee of exact equality on every API.
Both `unknown` and `unaccounted` are already accounted relative to total; **do not
add total, unknown, unaccounted, overlap, vsyncLate, or GPU together**. New frame
records explicitly carry `unaccountedNs/overlapNs/vsyncLateNs` and
`accounting=frameMetricsResidualNotAdditive`. Old v2 records derive residual only
when all required components exist; missing components produce null/unavailable,
never a fabricated zero.

### Complete literal table for new or connected labels

The source/wiring descriptions here are inherited preparation notes, not a
verification of the final source or installed APK. Recheck each connection in
that artifact; label registration does not establish runtime coverage or supply
frame-to-row/block identifiers.

New labels are registered only in `StreamPerformanceDiagnostics.kt`'s
`StreamDiagnosticGapLabels.stages`; the existing registry delegates validation to
that fixed set. No dynamic suffix or content-derived label is accepted.

| Exact stage literal | State / interpretation |
| --- | --- |
| `main.uninstrumented` | New; all completed dispatch aggregates, dispatch minus outermost measured time |
| `main.nonReveal` | New; all completed dispatch aggregates, dispatch minus outermost reveal time; contains uninstrumented |
| `chat.content.commit` | New; registered for the integration call in the actual conversation content scope; point/count only |
| `list.measure` | New; registered for conversation LazyColumn measure; inclusive child measure, not placement |
| `list.place` | New; registered for conversation LazyColumn placement via the new placement-only modifier |
| `render.compose` | Existing; connected through the already installed ChatBodyTrace SideEffects; successful content-body commits, **not composition cost** |
| `frame.unaccounted` | Existing; residual metric now also exported per detailed frame |
| `frame.overlap` | Existing; overlap accounting now also exported per detailed frame; never extra wall time |
| `frame.vsyncLate` | Existing; lateness now also exported per detailed frame; overlaps unknown |
| `main.doFrame` | Existing; main frame dispatch aggregate, separate from FrameTimeline |
| `main.message` | Existing; non-frame dispatch aggregate |

Existing fixed markdown labels `markdown.stable.measure`, `markdown.tail.measure`,
`markdown.hidden.measure`, `markdown.stable.draw`, `markdown.tail.draw`, and
`markdown.blockDraw` remain wired in their existing paths. The generic registered
`render.measure`/`render.draw` can cover the document-level block container during
integration; they must not be added to their inclusive markdown children. Existing
`ui.flush`, `ui.flush.blockSwitch`, `ui.flush.nonDelta`, `ui.flush.timer` remain
fixed; the common flush/coalescer boundary still needs the integration edit in
its owner file. See [PENDING_WIRING.md](PENDING_WIRING.md) for exact anchors and APIs.

### Missing tables are unavailable, NOT zero

`table-status.template.json` is a schema template, **not trace evidence**. Delete
unverified declarations or leave them unavailable. Its fixed schema is
`eta.streamdiag.tables.v2` with only the reviewed table names in `analyze.py`;
entries have exactly `{available: bool, rows: nonnegative integer|null}`. No free
text, query, path, reason, or arbitrary table name is accepted. Duplicate/unknown
keys or `available=false, rows=0` reject. Missing declarations and absent tables
produce `{status: unavailable, rows: null}`; an independently observed existing
empty table produces `{status: observed_zero, rows: 0}`. `available=true, rows=null`
is available with unknown count, not zero. Inventory counts are trace-wide, not
requested-window samples. The analysis script does not query Perfetto or recover
missing tables silently. Check table existence with `diagnostic-integrity.sql`
first; a query failure remains unsupported/unavailable, never a zero-row result.

## On-device acceptance gates

Apply these gates to **each final on-device reproduction**, not a smoke trace or
synthetic fixture. They are evidence requirements, **not implementation claims**:
this documentation/config change adds no clock calibration emitter, Linux TID
bridge, row/block link emitter, lock tracing or independent OFF frame collector.
Verify actual final-source wiring and installed-APK output; a registered stage,
parser field, pending wiring note or passing fixture does not supply missing
data. Missing evidence remains unavailable and blocks the corresponding claim.

1. **Final artifact identity.** Preserve the installed final APK's SHA-256,
   version/build variant, full source Git SHA and dirty-source status, signing
   certificate SHA-256, same-build R8/native symbols and device/refresh rate.
   Verify the installed bytes/signature, not just a candidate APK or version
   string. ON and OFF must use this same final artifact; record process lifetime,
   restarts and device conditions for each repetition.
2. **Effective ON and OFF.** Record the exact setting key, namespace/store and
   value read back from the effective app setting at both ends of each run,
   including persistence/restart behavior. Verify actual diagnostic
   presence/absence in the chosen window. A toggle screenshot, a presumed key,
   or missing diagnostic lines alone is not proof of OFF; ON also needs positive
   activation evidence. OFF absence must never be exported as zero counters.
3. **Same rendered workload.** Match long-text body, length, streaming chunk
   order/timing, page/visibility, scroll/list position and rendering options.
   Check the actual rendered body, not only the input or server response. If the
   **53-character prefix** is still rendered differently between ON/OFF
   (including appearing on-screen in only one setting), the bodies differ:
   do **not** call it fully same-load ON/OFF. Keep local body verification
   private; these profiles must not start recording
   prompt/reply text to satisfy this gate.
4. **One window and final closure.** Use explicit equal-duration, matched-phase
   `[start,end)` windows for app diagnostics, trace and each frame collector.
   Require the session's emitted `final=true`, `admission=closed` and exact
   window bounds, with open-at-cutoff/late/rejected records accounted for.
   Retain **all** application integrity/capacity counters for that same window,
   including span/main/event-link overwrites, output truncation, slow/frame
   budget drops and `metricsDropped`, plus parser rejection/truncation and all
   trace loss/error/per-buffer/per-CPU stats. Admission snapshots and cumulative
   counters are not exact completion filters or exact per-frame losses. OFF may
   legitimately omit app diagnostics; its independent collector still needs a
   completed matched window and its own integrity evidence. Unclosed/missing
   reports and unobserved post-close completions cannot be replaced with zeros.
5. **Two-end clock calibration.** Measure app MONOTONIC/nanoTime against
   BOOTTIME/elapsed realtime at **both start and end**, correlated with actual
   Perfetto clock snapshots. Preserve bracketing/read-skew bounds, quantization,
   suspend effects and offset/drift uncertainty, including sample-clock
   conversion for `PERF_CLOCK_MONOTONIC` in pass 2. Verify intended/actual-vsync
   domains on this device. Report the calibrated interval and uncertainty for
   every selected frame; one anchor or a pasted nanoTime SQL bound is not enough.
   If uncertainty makes the frame/dispatch association non-unique, stop that
   attribution rather than choosing the nearest frame.
6. **Real process/thread bridge.** Correlate actual PID/Linux TID and process/
   thread lifetimes to trace UPID/UTID for Eta main, RenderThread and relevant
   workers. Java `Thread.id`, a numeric `span.thread`, thread names and recycled
   PIDs are not verified Linux identity bridges by themselves. If final source
   does not emit the bridge, obtain a verified independent bridge or leave the
   link unavailable; do not document the bridge as already implemented.
7. **Actually reproduce abnormal Eta frames.** Retain abnormal frame records
   from this workload with exact criteria, counts/denominators and unique
   calibrated joins to the target app/surface and dispatch. Global trace counts,
   other apps' FrameTimeline rows, first/last source timestamps or a long trace
   cannot substitute for actual per-frame coverage. If **Eta FrameTimeline is
   absent**, report only execution/wait evidence for uniquely matched, calibrated
   FrameMetrics frames; do not claim presentation FPS or FrameTimeline deadline
   misses. If OFF has no independent same-kind frame metric and valid window/
   denominator, make **no ON/OFF FPS comparison** (nor a missing-as-zero jank
   comparison). Visual smoothness and doFrame envelopes are not substitutes.
8. **Per-frame cause chain, not window totals.** For each reproduced abnormal
   frame, retain clipped main/RenderThread `sched` and `thread_state` rows,
   Running/Runnable/wait intervals, explicit lock-contention/wait evidence,
   actual stop-the-world GC pause slices and RenderThread/binder/fence waits.
   Distinguish each source's verified per-frame coverage/observed-zero result
   from unavailable data; not every frame must contain every kind of wait.
   D/S state is not automatically a lock wait; runtime GC time and concurrent GC
   cycles are not pause durations. Validate block/direct-reclaim event support
   and target-window coverage on this kernel before using them. Link the frame
   to actual anonymous event/span/**row/block** identifiers and the measured
   rendered work; page labels or temporal proximity alone do not identify a
   conversation row/markdown block. Missing source links or lock/pause/wait data
   leave that portion unknown, not zero or a proven renderer cause. Show exact
   local evidence references, gaps and uncertainty; overlapping components and
   inclusive parent/child stages are not an additive causal pie chart.

Keep a per-frame evidence/unknowns table and the matched-run artifact/window/
integrity manifest with the report. Separate unsampled findings from the
perturbed 99 Hz repetition. Failed gates permit a bounded exploratory finding,
not “installed and retested”, “fully same-load”, “all jank explained”, an ON/OFF
acceptance result or “optimized”. No full two-pass/on-device acceptance was
performed by this implementation agent.
