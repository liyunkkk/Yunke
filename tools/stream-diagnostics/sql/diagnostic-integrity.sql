-- Trace-wide health, not window-local. Keep zero rows and indexed per-CPU stats:
-- missing statistics are NOT evidence of zero losses. This file extends the
-- supplied smoke-check SQL without hiding any warnings/errors.
SELECT name, idx, value, severity, source FROM stats ORDER BY severity, name, idx;
SELECT name, idx, value, severity, source FROM stats
WHERE severity IN ('error', 'data_loss', 'warning', 'warn')
   OR name GLOB '*lost*' OR name GLOB '*drop*' OR name GLOB '*overrun*'
   OR name GLOB '*overwrite*' OR name GLOB '*discard*' OR name GLOB '*trunc*'
ORDER BY name, idx;
SELECT COUNT(*) AS sched_rows FROM sched;
SELECT COUNT(*) AS frames FROM actual_frame_timeline_slice;
SELECT upid, pid, name FROM process WHERE name GLOB '*mangi*';
-- Inspect existence BEFORE executing optional sampling SQL. An absent table and
-- a present-but-empty table are different outcomes. Neither proves no CPU/alloc.
SELECT name, type FROM sqlite_master
WHERE name IN ('perf_sample', 'heap_profile_allocation', 'stack_profile_callsite',
               'stack_profile_frame', 'stack_profile_mapping') ORDER BY name;
-- Reference smoke only: parent observed 30274 sched rows, 93 FrameTimeline rows,
-- trace_sorter_negative_timestamp_dropped=1 (severity error), NOT loss-free.
