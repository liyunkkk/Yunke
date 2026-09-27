# Tool-call provenance diagnostics

Enable **Record diagnostic logs** in Settings → Diagnostics before reproducing, then **Export logs**. Search the exported app log for `ToolCallDiag `. Turning recording off stops new diagnostics; existing logs remain until cleared. No automatic upload is performed. Previous missing events cannot be reconstructed.

## Correlation

Records use a random run identifier plus attempt, round and local call identifiers. Provider retry attempts have separate attempt IDs. Responses item IDs and call IDs are correlated without logging their plaintext. A positional fallback is marked `positional_correlation`; it is weaker evidence than an ID match.

Stages are `raw_added`, `raw_delta_summary`, `raw_args_done`, `raw_item_done`, `raw_terminal`, `provider_parsed`, `parsed`, `validation`, `dispatch`, `result`, and `failed`. Raw stages currently cover the Responses adapter only. Other provider types receive provider-parsed and downstream diagnostics, not raw stream provenance.

- Raw argument presence/type distinguishes missing, null, blank, explicit `{}`, and malformed objects before defaults.
- `arguments_hmac` compares exact strings using a random per-run secret that is never exported. Hashes cannot be compared across runs. Object-valued arguments use bounded serialization and explicitly identify that basis.
- `tool` allows the fixed names `terminal` and `run_command`; other tool names are fingerprinted.
- `requested_environment` is argument evidence; `actual_environment` is the returned execution envelope. Requesting `linux` and returning `debian` is expected alias resolution, not evidence of misrouting.
- Validation rejection without dispatch means the command was not executed. Missing diagnostics alone do not establish execution failure: recording may be disabled, capped, or the process may have ended.

## Privacy and bounds

These records never write command contents, paths, arbitrary argument values/keys, credentials, request headers, message bodies, stdout or stderr. Only fixed metadata, lengths, presence/type flags, classified error codes and keyed fingerprints are emitted. This guarantee applies to `ToolCallDiag` records, not every pre-existing log source.

The recorder caps each line at 4096 characters, each attempt at 128 records, each run at 512 records, tracked calls at 32 per attempt and aliases at 256. Stream deltas are summarized instead of logged individually. Existing on-device file rotation and export are reused. Diagnostic failures are isolated from execution, do not retry tools, and do not alter tool names, arguments, results or transcripts.

## Incident safeguards

Invalid tool arguments exhaust a bounded repair budget rather than generating unlimited failed cards. Missing Responses terminal events or absent terminal tool arguments fail without automatic replay. These changes prevent known failure modes; they do not prove the source of earlier `run_command({})` calls for which raw evidence was unavailable.
