# Conversation turn lifecycle

- `turnId` denotes a user logical turn; provider request `round` is not a user turn.
- Pause/resume and accepted steering retain `turnId`. An execution continuation can change `runId` without changing `turnId`.
- User stop cancels resources immediately, closes the UI running state, then commits the stopped worker's transcript through the durable result outbox. A new send/revision waits for this commit; its text is not discarded.
- Stopped history contains completed assistant/tool records and acknowledged queued supplements. A missing tool result is marked unknown, never invented as success or blindly replayed. Sensitive tool data remains redacted.
- Failure is terminal. Clicking Continue after a terminal failure creates a new `runId` and `turnId`; the failed turn remains in history and is closed in result grouping.
- Internal bounded HTTP retries remain in the current logical turn. Only live retry-wait events classify stop-during-retry; old notices are not live state.
- Revision/branch lookup uses turn identity and user content, not differing UI/history message counts. A missing supplement fails closed instead of cutting away an unrelated original question.

Regression tests cover deferred stop commit, commit/stop races, queued supplements, partial response retention, interrupted tool batches, revision misalignment, old retry notices, and failure/result boundaries.

## Revision matching and compression evidence

- A missing UI-to-history match no longer implies that the message was compressed. Editing, regeneration, deletion and branching fail closed when neither an identity match nor a verified earlier summary boundary is available.
- Attachment envelopes are normalized only within the same owner turn and with equal nonempty visible request / conversation references. Exact legacy matches and supplement matching remain supported; normalization cannot bridge different turns.
- A real UI summary marker must follow the missing user message. For legacy records without a marker, require a recognized summary and an exactly matched retained later turn. Tool pruning alone and a generic system message are not summary evidence.
- Failed revision shows an explicit history-unavailable notice, not “earlier context compressed”. Submission keeps the edit draft when its original boundary can no longer be found.
- Added eight regression cases; existing compaction fixtures now use recognized summary prefixes rather than an arbitrary “已压缩” system string. Local diff/XML validation only; CI tests and device verification pending.
