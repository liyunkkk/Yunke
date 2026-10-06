-- Render with analyze.py render-sql; no implicit whole-trace window.
-- Nanoseconds are TRACE TIME, not wall time or StreamDiag/System.nanoTime.
-- Pick UPID from diagnostic-integrity.sql; PID can be recycled after restart.
CREATE TEMP TABLE eta_diag_window AS
SELECT __START_NS__ AS start_ns, __END_NS__ AS end_ns, __UPID__ AS upid;
SELECT start_ns, end_ns, upid, end_ns - start_ns AS requested_ns,
       b.start_ts AS trace_start_ns, b.end_ts AS trace_end_ns,
       CASE WHEN w.start_ns < b.start_ts OR w.end_ns > b.end_ts
            THEN 'partial_trace_coverage' ELSE 'within_trace_bounds' END AS coverage
FROM eta_diag_window w CROSS JOIN trace_bounds b;

-- App FrameTimeline starts in the requested window, by surface/layer.
-- App Deadline Missed is a FrameTimeline reason, NOT doFrame >16ms.
-- A frame with another reason is not automatically an app deadline miss.
SELECT f.layer_name, COUNT(*) AS app_surface_frame_rows,
       SUM(CASE WHEN f.jank_type LIKE '%App Deadline Missed%' THEN 1 ELSE 0 END)
         AS app_deadline_missed,
       SUM(CASE WHEN f.jank_type IS NULL THEN 1 ELSE 0 END) AS unknown_jank_reason,
       SUM(CASE WHEN f.dur < 0 THEN 1 ELSE 0 END) AS unfinished_frames
FROM actual_frame_timeline_slice f CROSS JOIN eta_diag_window w
WHERE f.upid = w.upid AND f.ts >= w.start_ns AND f.ts < w.end_ns
GROUP BY f.layer_name;
SELECT f.jank_type, f.present_type, COUNT(*) AS frames
FROM actual_frame_timeline_slice f CROSS JOIN eta_diag_window w
WHERE f.upid = w.upid AND f.ts >= w.start_ns AND f.ts < w.end_ns
GROUP BY f.jank_type, f.present_type;

-- Main and RenderThread state residence, clipped to [start,end). Raw state is
-- retained: Blocked_or_sleeping includes sleeping, not only lock contention.
-- Unknown is not relabelled Blocked. Parallel threads must not be added as wall time.
SELECT CASE WHEN t.tid = p.pid THEN 'main' ELSE 'RenderThread' END AS role,
       t.utid, st.state AS raw_state,
       CASE WHEN st.state = 'Running' THEN 'Running'
            WHEN st.state IN ('R', 'R+') THEN 'Runnable'
            WHEN st.state IN ('S', 'D', 'DK', 'I', 'T', 't') THEN 'Blocked_or_sleeping'
            ELSE 'Unknown' END AS state_group,
       COUNT(*) AS intervals,
       SUM(MIN(CASE WHEN st.dur < 0 THEN w.end_ns ELSE st.ts + st.dur END, w.end_ns)
           - MAX(st.ts, w.start_ns)) AS clipped_ns
FROM thread_state st JOIN thread t USING (utid)
JOIN process p USING (upid) CROSS JOIN eta_diag_window w
WHERE t.upid = w.upid AND (t.tid = p.pid OR t.name = 'RenderThread')
  AND st.ts < w.end_ns
  AND (st.dur < 0 OR st.ts + st.dur > w.start_ns)
GROUP BY role, t.utid, raw_state, state_group;
SELECT t.utid, t.tid, t.name,
       CASE WHEN t.tid = p.pid THEN 'main' ELSE 'RenderThread' END AS role
FROM thread t JOIN process p USING (upid) CROSS JOIN eta_diag_window w
WHERE t.upid = w.upid AND (t.tid = p.pid OR t.name = 'RenderThread');

-- State coverage gaps are unknown, not idle/blocked. Thread-state intervals
-- should not overlap for one utid; if totals exceed the window inspect integrity.
SELECT t.utid, CASE WHEN t.tid = p.pid THEN 'main' ELSE 'RenderThread' END AS role,
       w.end_ns - w.start_ns AS requested_ns,
       COALESCE(SUM(MIN(CASE WHEN st.dur < 0 THEN w.end_ns ELSE st.ts + st.dur END, w.end_ns)
                    - MAX(st.ts, w.start_ns)), 0) AS state_observed_ns,
       w.end_ns - w.start_ns - COALESCE(SUM(
           MIN(CASE WHEN st.dur < 0 THEN w.end_ns ELSE st.ts + st.dur END, w.end_ns)
           - MAX(st.ts, w.start_ns)), 0) AS state_unobserved_ns
FROM thread t JOIN process p USING (upid) CROSS JOIN eta_diag_window w
LEFT JOIN thread_state st ON st.utid = t.utid AND st.ts < w.end_ns
  AND (st.dur < 0 OR st.ts + st.dur > w.start_ns)
WHERE t.upid = w.upid AND (t.tid = p.pid OR t.name = 'RenderThread')
GROUP BY t.utid, role, requested_ns;

-- doFrame is synchronous main-thread work, independently reported from frames.
-- No fixed 16ms device jank threshold; clipped sums include partial slices.
SELECT COUNT(*) AS doframe_slices,
       SUM(MIN(s.ts + s.dur, w.end_ns) - MAX(s.ts, w.start_ns)) AS clipped_inclusive_ns,
       MAX(s.dur) AS full_slice_max_ns
FROM slice s JOIN thread_track tt ON s.track_id = tt.id
JOIN thread t USING (utid) JOIN process p USING (upid)
CROSS JOIN eta_diag_window w
WHERE t.upid = w.upid AND t.tid = p.pid AND s.dur >= 0
  AND (s.name GLOB 'Choreographer#doFrame*' OR s.name GLOB 'doFrame*')
  AND s.ts < w.end_ns AND s.ts + s.dur > w.start_ns;

-- ART naming varies. Only a GC-specific pause label is called a GC pause.
-- Concurrent cycles overlap application execution: NEVER call their full dur a pause.
-- Other GC and SuspendAll slices remain explicitly unclassified.
SELECT CASE
         WHEN LOWER(s.name) LIKE '%gc%' AND LOWER(s.name) LIKE '%pause%'
           THEN 'gc_pause_label'
         WHEN LOWER(s.name) LIKE '%gc%' AND LOWER(s.name) LIKE '%concurrent%'
           THEN 'gc_concurrent_cycle_inclusive'
         ELSE 'gc_or_suspend_unknown' END AS gc_kind,
       s.name AS runtime_label, t.name AS thread_name, COUNT(*) AS slices,
       SUM(MIN(s.ts + s.dur, w.end_ns) - MAX(s.ts, w.start_ns)) AS clipped_inclusive_ns,
       MAX(s.dur) AS full_slice_max_ns
FROM slice s JOIN thread_track tt ON s.track_id = tt.id JOIN thread t USING (utid)
CROSS JOIN eta_diag_window w
WHERE t.upid = w.upid AND s.dur >= 0
  AND (LOWER(s.name) LIKE '%gc%' OR LOWER(s.name) LIKE '%suspendall%'
       OR LOWER(s.name) LIKE '%suspend all%')
  AND s.ts < w.end_ns AND s.ts + s.dur > w.start_ns
GROUP BY gc_kind, runtime_label, thread_name;

-- Fixed stage layers. New/unrecognised Eta.* sections stay unknown, not a guessed
-- network/render attribution. Parent/child inclusive durations must NOT be added.
-- Unknown business labels are not exported (could be dynamic content).
WITH stage_layer(stage, layer) AS (VALUES
 ('ui.delta.received', 'ui_delivery'), ('ui.flushDelay', 'ui_scheduling'),
 ('ui.flush', 'ui_projection'), ('ui.flush.blockSwitch', 'ui_projection'),
 ('ui.messages.apply', 'ui_projection'), ('ui.messages.transform', 'ui_projection'),
 ('ui.messages.normalize', 'ui_projection'), ('ui.messages.publish', 'ui_projection'),
 ('ui.conversation.route', 'ui_projection'), ('ui.conversation.owner', 'ui_projection'),
 ('ui.conversation.waiting', 'ui_projection'), ('ui.conversation.publish', 'ui_projection'),
 ('ui.summaries.refresh', 'ui_projection'), ('chat.compose', 'composition'),
 ('gallery.scan', 'gallery'), ('gallery.parse', 'gallery'),
 ('gallery.hit', 'gallery'), ('gallery.skip', 'gallery'),
 ('markdown.target', 'markdown_pipeline'), ('markdown.queueWait', 'markdown_pipeline'),
 ('markdown.parse', 'markdown_pipeline'), ('markdown.coalesced', 'markdown_pipeline'),
 ('markdown.superseded', 'markdown_pipeline'), ('markdown.publishBlock', 'markdown_pipeline'),
 ('markdown.targetToPublish', 'markdown_pipeline'), ('markdown.publish', 'markdown_pipeline'),
 ('markdown.layout', 'layout'), ('markdown.blockDraw', 'draw'),
 ('reveal.step', 'reveal'), ('reveal.frameGap', 'reveal'),
 ('reveal.backlog', 'reveal'), ('reveal.remeasure', 'layout'),
 ('scroll.state', 'scroll'), ('follow.initialSnap', 'scroll'),
 ('follow.decision', 'scroll'), ('follow.frameGap', 'scroll'),
 ('follow.scroll', 'scroll'), ('follow.step', 'scroll'), ('follow.cancelled', 'scroll'))
SELECT COALESCE(m.layer, 'unknown') AS layer, COALESCE(m.stage, 'unknown') AS stage,
       t.utid, COUNT(*) AS slices,
       SUM(MIN(s.ts + s.dur, w.end_ns) - MAX(s.ts, w.start_ns)) AS clipped_inclusive_ns,
       MAX(s.dur) AS full_slice_max_ns
FROM slice s JOIN thread_track tt ON s.track_id = tt.id JOIN thread t USING (utid)
CROSS JOIN eta_diag_window w
LEFT JOIN stage_layer m ON s.name = 'Eta.' || m.stage
WHERE t.upid = w.upid AND s.name GLOB 'Eta.*' AND s.dur >= 0
  AND s.ts < w.end_ns AND s.ts + s.dur > w.start_ns
GROUP BY layer, stage, t.utid;
SELECT COUNT(*) AS unfinished_eta_slices
FROM slice s JOIN thread_track tt ON s.track_id = tt.id JOIN thread t USING (utid)
CROSS JOIN eta_diag_window w
WHERE t.upid = w.upid AND s.name GLOB 'Eta.*' AND s.dur < 0
  AND s.ts < w.end_ns;
DROP TABLE eta_diag_window;
