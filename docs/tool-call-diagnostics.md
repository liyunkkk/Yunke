# Tool-call provenance diagnostics

Enable **Record diagnostic logs** in Settings → Diagnostics before reproducing, then **Export logs**. Search the exported app log for `ToolCallDiag `. Turning recording off stops new diagnostics; existing logs remain until cleared. No automatic upload is performed. Previous missing events cannot be reconstructed.

## Correlation

Records use a random run identifier plus attempt, round and local call identifiers. Provider retry attempts have separate attempt IDs. Responses item IDs and call IDs are correlated without logging their plaintext. A positional fallback is marked `positional_correlation`; it is weaker evidence than an ID match.

Stages are `raw_added`, `raw_delta_summary`, `raw_args_done`, `raw_item_done`, `raw_terminal`, `provider_parsed`, `parsed`, `validation`, `dispatch`, `result`, and `failed`. Raw stages currently cover the Responses adapter only. Other provider types receive provider-parsed and downstream diagnostics, not raw stream provenance.

- Raw argument presence/type distinguishes missing, null, blank, explicit `{}`, and malformed objects before defaults.
- `arguments_hmac` compares exact strings using a random per-run secret that is never exported. Hashes cannot be compared across runs. Object-valued arguments use bounded serialization and explicitly identify that basis.
- `tool` allows the fixed names `terminal` and `run_command`; other tool names are fingerprinted.
- `requested_environment` is argument evidence; `actual_environment` is the returned execution envelope. Requesting `linux` and returning `debian` is expected alias resolution, not evidence of misrouting.
- The `result` stage records the content paired into history, after local guards. When a guard rewrote it (for example `SHELL_COMMAND_NOT_FOUND`), `guard_annotated=true` and `raw_code` holds the executor's own code; `shell_failure_attempt`/`shell_failure_max`/`shell_stop_after_batch` expose the missing-command budget without logging the executable name.
- Validation rejection without dispatch means the command was not executed. Missing diagnostics alone do not establish execution failure: recording may be disabled, capped, or the process may have ended.

## Privacy and bounds

These records never write command contents, paths, arbitrary argument values/keys, credentials, request headers, message bodies, stdout or stderr. Only fixed metadata, lengths, presence/type flags, classified error codes and keyed fingerprints are emitted. This guarantee applies to `ToolCallDiag` records, not every pre-existing log source.

The recorder caps each line at 3968 characters (reserving logger framing), detail records at 128 per request attempt, and each critical stage at 64 records per attempt. Request shape/context, raw usage receipts, tool results/history pairing, failures and terminal summaries have independent quotas. There is no permanent 512-record run cutoff; later attempts remain observable. Critical records include dropped-detail, oversized-record and sink-failure counters. Tracked calls remain capped at 32 per attempt and aliases at 256. Stream deltas are summarized instead of logged individually. Existing on-device file rotation and export are reused. Diagnostic failures are isolated from execution, do not retry tools, and do not alter tool names, arguments, results or transcripts.

## Incident safeguards

Invalid tool arguments exhaust a bounded repair budget rather than generating unlimited failed cards. Missing Responses terminal events or absent terminal tool arguments fail without automatic replay. These changes prevent known failure modes; they do not prove the source of earlier `run_command({})` calls for which raw evidence was unavailable.

## Final-request and result attribution

`request_shape` observes the same serialized string used for the HTTP RequestBody: byte length from that body, character length and a per-run HMAC over UTF-8 bytes. Reasoning IDs are counted as missing/duplicate, never logged; ID tracking is bounded and marks a lower-bound duplicate count if capped. `usage` records each provider receipt separately with run/round/attempt and receipt ordinal, including presence flags; it does not merge receipts or alter accounting. `result` records raw and post-guard result lengths/UTF-8 bytes/HMACs; `history_result` records the content actually paired into history using the same result hash domain. No request, reasoning, tool-result or credential prose is logged.

## Child-result pages

Running `get_task_result` polls return status/progress without cumulative prose. A final result defaults to its first 4000 UTF-16 units, without repeating identical partial prose. Use `text_field`, `text_offset`, `text_limit` and `text_page.next_offset/has_more` for necessary further pages. `partial_result` and `model_report_unverified` require explicit field selection; model claims remain unverified. Event cursors (`after_seq`) are independent of text offsets. Offsets inside a surrogate pair are rejected; a limit of one may return a complete two-unit pair to ensure progress.

Final reports are retained in the live task rather than permanently truncated by public page limits. Confirmed partial text retains its existing 16000-unit bound with an explicit truncation flag and safe Unicode boundary. Process-local archives retain complete reports within the existing 2 MiB budget; if exceeded, prose is evicted explicitly (`text_evicted`, `available=false`, `archive_capacity`) and oldest metadata can be evicted. This is not durable storage or a promise of unlimited retention. Handoff versions use a stable keyed text revision rather than the selected page, and retained fingerprints are fixed-length hashes, never another copy of report prose.
