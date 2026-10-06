-- Run ONLY after sqlite_master confirms heap_profile_allocation exists.
-- Heapprofd records sampled allocation accounting, NOT object/payload snapshots.
-- Rows may be signed allocation/free deltas. Preserve each dump timestamp;
-- summing dump snapshots across time is not total allocated bytes or live heap.
SELECT 'heap_profile_allocation' AS table_name, COUNT(*) AS trace_rows,
       CASE WHEN COUNT(*) = 0 THEN 'present_zero_samples' ELSE 'present_samples' END AS status
FROM heap_profile_allocation;
SELECT COUNT(*) AS window_target_rows,
       SUM(CASE WHEN callsite_id IS NULL THEN 1 ELSE 0 END) AS missing_callstack_rows
FROM heap_profile_allocation
WHERE upid = __UPID__ AND ts >= __START_NS__ AND ts < __END_NS__;
SELECT ts AS dump_trace_ns, heap_name, callsite_id, COUNT(*) AS sample_rows,
       SUM(count) AS signed_sampled_count, SUM(size) AS signed_sampled_bytes
FROM heap_profile_allocation
WHERE upid = __UPID__ AND ts >= __START_NS__ AND ts < __END_NS__
GROUP BY ts, heap_name, callsite_id ORDER BY ts, signed_sampled_bytes DESC;
