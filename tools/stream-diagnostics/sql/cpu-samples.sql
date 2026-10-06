-- Run ONLY after sqlite_master confirms perf_sample exists. An SQL error is
-- 'unsupported/missing table', NOT '0 samples'. Render explicit trace-time window.
SELECT 'perf_sample' AS table_name, COUNT(*) AS trace_rows,
       CASE WHEN COUNT(*) = 0 THEN 'present_zero_samples' ELSE 'present_samples' END AS status
FROM perf_sample;
SELECT COUNT(*) AS window_target_samples,
       SUM(CASE WHEN ps.callsite_id IS NULL THEN 1 ELSE 0 END) AS missing_callstack_samples
FROM perf_sample ps JOIN thread t USING (utid)
WHERE t.upid = __UPID__ AND ps.ts >= __START_NS__ AND ps.ts < __END_NS__;
-- Sample counts are not exact CPU durations. Keep unresolved callsites visible.
-- Inspect these callsites in Perfetto/symbolize using the SAME APK's symbols.
SELECT t.utid, t.name, ps.callsite_id, COUNT(*) AS samples
FROM perf_sample ps JOIN thread t USING (utid)
WHERE t.upid = __UPID__ AND ps.ts >= __START_NS__ AND ps.ts < __END_NS__
GROUP BY t.utid, t.name, ps.callsite_id ORDER BY samples DESC;
