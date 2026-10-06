# Eta stream diagnostics: local, opt-in, offline

This prepares detailed measurement, **not an optimization or an acceptance result**.
Nothing here starts a profiler, collects logs, uploads files, installs an APK or
runs persistently. The only app change in this module is
`<profileable android:shell="true" />` inside `application`; no `debuggable` flag,
new permission, exported activity, business/visual or persistence change.

## Evidence and limits of validation

- The parent supplied both configs and the initial integrity SQL. The 180-second
  baseline config is copied unchanged. The 30-second config only removes the
  nonexistent `io.github.mangi.eta:agent_runtime` targets and documents why.
- Manifest declares no `android:process` on `AgentRuntimeService` or
  `AgentExecutionService`, and no application default override: these are in
  **`io.github.mangi.eta`**. `:voice`, `:voice_session`, `:recognition` are separate
  voice-related processes, not the agent runtime. Recheck the installed merged
  manifest and `adb shell ps -A` if another build changes this.
- Parent-reported Android Perfetto **1-second baseline smoke** produced
  **30,274 sched rows and 93 FrameTimeline rows**. It also reported
  **`trace_sorter_negative_timestamp_dropped=1`, severity `error`**. This is a
  compromised-data health finding, **not zero loss**, not a 180-second workload
  measurement and not evidence about Eta's actual jank.
- Parent-reported sampled-config 1-second smoke used a deliberately nonexistent
  target: **syntax accepted only; no real CPU/heap stacks were demonstrated**.
- This implementation agent has no shell/Android execution capability. The
  Python unit tests, SQL schema compatibility, final same-build core golden
  fixture, installed `profileable` permission, real samples and the full two
  captures must be checked by the integrating agent/operator. Source, a passing
  build, config parsing and fake fixtures are not on-device acceptance.

## Two separate capture passes (commands to run manually, not executed here)

Use a trusted local USB-connected device and a same-build APK. Confirm the APK
SHA/version and workload/device refresh rate. Local traces can contain system
process/thread names and stack symbols; do not upload them casually. These
configs do **not** enable `android.log`, full logcat, network/prompt/reply/audio
payload capture or heap object dumps. Allocation **sizes/stacks**, not allocation
contents, are sampled in pass 2.

### 1. Baseline timeline, 180 seconds

From the repository root, with `adb` already authorized:

```sh
adb shell ps -A
adb push tools/stream-diagnostics/config/eta-jank-timeline.pbtxt /data/local/tmp/eta-jank-timeline.pbtxt
adb shell perfetto --txt -c /data/local/tmp/eta-jank-timeline.pbtxt -o /data/misc/perfetto-traces/eta-jank-timeline.pftrace
# During the 180-second foreground command, reproduce streaming and scrolling
# on the device (or start the command in one terminal and use the device).
adb pull /data/misc/perfetto-traces/eta-jank-timeline.pftrace ./eta-jank-timeline.pftrace
```

The ring buffers and 1 GiB file cap are bounded; bounds can overwrite/truncate
history. Do not assume the requested full duration survived. Check `stats`,
`trace_bounds`, trace file completeness, and clock/window coverage first. If the
app/session was not visible/streaming in the selected window, say so explicitly.

### 2. Short sampled stacks, 30 seconds, same workload repeated separately

```sh
adb push tools/stream-diagnostics/config/eta-jank-sampled-stacks.pbtxt /data/local/tmp/eta-jank-sampled-stacks.pbtxt
adb shell perfetto --txt -c /data/local/tmp/eta-jank-sampled-stacks.pbtxt -o /data/misc/perfetto-traces/eta-jank-sampled-stacks.pftrace
adb pull /data/misc/perfetto-traces/eta-jank-sampled-stacks.pftrace ./eta-jank-sampled-stacks.pftrace
# Optional manual cleanup after verifying local copies (not performed by tool):
adb shell rm /data/local/tmp/eta-jank-timeline.pbtxt /data/local/tmp/eta-jank-sampled-stacks.pbtxt
adb shell rm /data/misc/perfetto-traces/eta-jank-timeline.pftrace /data/misc/perfetto-traces/eta-jank-sampled-stacks.pftrace
```

Pass 2 adds `linux.perf` CPU-clock **99 Hz** callstack sampling and heapprofd
**16 KiB** allocation sampling for `libc.malloc` and `com.android.art`, with
5-second dumps. Supported Android/ART build, profileable permission, producer
availability and unwind/symbol support are prerequisites. Present tables with
zero rows, target-window zero samples, missing tables, unresolved stacks and
sampling producer errors are different findings; none means “no allocations” or
“CPU did no work”. Startup/runtime restrictions may require repeating the
short pass after a manual app restart; do not silently automate it.

Sampling and diagnostics add scheduling, unwinding, memory and I/O disturbance.
Do not combine pass 2 FPS/jank into baseline or compare mismatched workloads as a
performance improvement. CPU samples are statistical, heap counts/bytes are
sampled estimates/accounting (including signed allocation/free rows), and dump
timestamps are not exact allocation times. CPU, GC cycles, GPU and nested Eta
stages overlap. **Their durations cannot be precisely added into a single frame
or response time**. A sampled hot stack correlated with a slow frame is evidence
for investigation, not proof of causation.

### Profileability and symbols

Official references (provided/checked by parent; not browsed by this agent):

- https://developer.android.com/guide/topics/manifest/profileable-element
- https://perfetto.dev/docs/data-sources/native-heap-profiler

`profileable` permits trusted local shell profiling on a user build (needed for
local stack profiling on the reported Android 15 device), not arbitrary app
memory reading and not debugging. The official profileable discussion does not
promise a heap object/body view. Heapprofd uses the native/ART heap names above.
It is not a Java heap dump, and the app's `heap=proxyNotAllocationStack` counter
is only a runtime heap proxy, not an allocation stack trace.

Keep R8 `mapping.txt`, native unstripped ELF/debug symbols/build IDs, source
revision and the exact installed **same APK** together. Another build's mapping
can produce wrong attribution; obfuscated/unresolved names remain unknown.
Do not decode anonymous conversation/run tokens back to user identifiers.

## SQL analysis: explicit trace-time window, explicit UPID

Use a compatible local `trace_processor_shell` binary; it is not bundled or
installed by this tool. Save its version. The examples below use the verified
**v58.2** `query` subcommand; older versions may use different flags. Check
`query --help`, and never reinterpret an SQL string as a filename. For example:

```sh
trace_processor_shell query -f tools/stream-diagnostics/sql/diagnostic-integrity.sql eta-jank-timeline.pftrace
trace_processor_shell query eta-jank-timeline.pftrace 'SELECT start_ts,end_ts FROM trace_bounds'
# Replace numbers with actual TRACE-TIME ns and the app UPID from integrity SQL.
python3 tools/stream-diagnostics/analyze.py render-sql --template window-analysis --start-ns 123000000000 --end-ns 124000000000 --upid 42 --output window.sql
trace_processor_shell query -f window.sql eta-jank-timeline.pftrace
```

Do **not** use those example numbers as a real measurement or omit bounds and
silently analyze the whole recording. PID is not UPID; PID may be recycled.
SQL stats are trace-wide even when stage/frame queries select a window.
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
trace_processor_shell query -f cpu.sql eta-jank-sampled-stacks.pftrace
python3 tools/stream-diagnostics/analyze.py render-sql --template heap-samples --start-ns 123000000000 --end-ns 124000000000 --upid 42 --output heap.sql
trace_processor_shell query -f heap.sql eta-jank-sampled-stacks.pftrace
```

Each optional query distinguishes present-but-zero trace rows from target-window
sample counts and missing callsites. Missing-table SQL errors must be labelled
unsupported/missing, **never replaced by zeros**. Heap output keeps each dump
and heap separate; summing dumps is not a live-heap size. SQL/schema and runtime
labels must be smoke checked by the parent on the actual trace processor.

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

- Common: `StreamDiag v=2 type=window|span|frame|runtime id=<8 lowercase hex>`
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
trace_processor_shell query eta-jank-timeline.pftrace 'SELECT name,idx,value,severity,source FROM stats ORDER BY name,idx' > stats.csv
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

## Acceptance report checklist

Record APK SHA/version/source/same-build symbols, device/refresh rate, pass and
workload, exact windows and clock mapping uncertainty, visibility/run/page
coverage, all application drop/truncation counters, trace stats including errors,
optional table presence/zero samples/unresolved symbols, and unknown attribution.
Separate baseline findings from perturbed sample evidence. Installed capture and
controlled same-workload verification are still required; this preparation is
not “installed and retested”, “all jank explained” or “optimized”.
