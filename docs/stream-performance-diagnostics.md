# Streaming performance diagnostics

`StreamDiag` collects only while a chat is streaming in the resumed activity, plus a 3-second rendering tail. It detaches on navigation/background. Each visible session gets a random ID (not the conversation ID). Counters are aggregated in memory and written by a HandlerThread every 5 seconds, then reset; disposal writes the remaining interval. No text, prompts, URLs, credentials or audio are logged. Stage count is bounded to 64; histograms use fixed storage.

## Fields

- `n`, `avgUs`, `maxUs`: count and duration (microseconds).
- `b16_32_50_100_over`: counts in <=16, <=32, <=50, <=100, >100 milliseconds. These bins are not a device-specific jank definition.
- `valueSum`, `valueMax`: stage-specific numeric values. For frame.total, valueSum counts total duration exceeding FrameMetrics.DEADLINE; frame.deadline reports device frame budgets. Dropped frame-metrics callbacks are recorded separately.
- Values for UI apply/gallery scan/chat.compose are message counts; target/parse/publish and delta are character counts; layout is pixel height; backlog is pending graphemes; heap is used Java heap bytes.

## Coverage

- UI-delivered deltas and gaps (NOT raw network SSE timing); flush scheduling delay and message projection work.
- Chat composition commits, background gallery scan/parse, cache hit/no-image skip.
- Markdown target queue wait, parse, coalesced/superseded snapshots, target-to-publish delay, publish and layout counts.
- Reveal frame intervals, backlog and height remeasurement requests.
- User-scroll state changes, follow decisions, actual scroll work/cancellation.
- Window FrameMetrics total, deadline, layout/measure, draw, synchronization, GPU and input-handling durations. All window content is included, not just chat.
- The remaining non-overlapping parts: `frame.unknown` (UI thread busy before the frame), `frame.animation`, `frame.command`, `frame.swap`. `frame.unaccounted` is total minus those eight parts. GPU is recorded separately because it overlaps command issue and buffer swap; a negative remainder is `frame.overlap`.
- `frame.vsyncLate` is actual VSYNC minus intended VSYNC. valueSum counts frames later than 8.3 ms, the 120 Hz budget. `frame.deadline` is the device budget, not time spent; its histogram is not a jank count.
- Sampled Java heap usage; this is not native allocation or GC pause profiling.

`Eta.<stage>` trace sections accompany measured synchronous stages for Perfetto attribution. Aggregates establish correlations, not automatic root-cause claims. Runtime logs remain separate; a UI delta gap alone cannot distinguish network latency from runtime delivery/main-thread scheduling. A sample with no drops does not establish that the entire interaction was smooth.

## Verification

Check the installed APK SHA against the delivered CI artifact, run a long reply containing lists/table/code, scroll away and back during output, then compare intervals with the same workload and device refresh rate. Check no diagnostics remain active after leaving/backgrounding and final summaries are written. Do not compare cumulative gfxinfo counts across different sessions as if they were a controlled benchmark.
