# GPT speed mode — uncompiled candidate

## Scope and provenance

- Candidate repository: `/workspace/Eta-gpt-speed-candidate`.
- Original repository remains `/workspace/Eta`, baseline `5c997e4c`; not merged into that repository.
- `fbd4a69f`: request field, Bundle transfer, tier injection, binding/snapshot policy.
- `c85c269a`: complete UI wiring and shared eligibility; background/draft invalidation; static contracts.
- `abec772e`: Compose manual-clock reentry/reset tests and invalid test-import cleanup.
- Final independent review: task `a1de9cf2-ede1-4f3f-9353-ea855a3ac266`, reviewed worktree `3acaa0e6ccb04921aea356324d5f09f5`; no substantiated blocking findings. Its test-only delta was merged through the workspace review gate into this candidate only.

## Implemented behavior

- Long press on the thinking icon cycles NORMAL -> FAST -> ULTRA_FAST -> NORMAL for eligible GPT text bindings.
- One accepted gesture rotates 360 degrees over 450 ms, scales 1.0 -> 1.12 -> 1.0, and transitions the icon color. FAST is light pink (`#F2A7BD`); ULTRA_FAST is burgundy (`#800020`). NORMAL retains the reasoning-dependent original color.
- Animation gate rejects repeated input without queuing; token checks prevent a cancelled old animation from unlocking a newer one.
- Single click keeps the existing reasoning picker. Speed does not change reasoning depth.
- Shared binding eligibility checks the actual configured model ID, enabled provider/model, exact supported OpenAI protocol, and non-media/text capabilities. The request builder additionally checks the final model after custom body merging.
- Leaving eligible GPT immediately clears the transient state and restores the ordinary tint, removing the speed long-press entry. Returning to GPT does not restore the former speed.
- Provider updates normalize all loaded conversations, including background ones without a usage receipt, plus the current draft. This does not wait for the archive-delayed picker refresh. Restored conversations are checked again.
- Each submitted run freezes its own speed configuration before coroutine launch; later UI/settings changes do not mutate the frozen configuration.
- Explicit NORMAL/FAST/ULTRA_FAST writes service_tier default/fast/ultrafast respectively; null preserves legacy/manual tiers. No model/reasoning changes or silent fallback retries are introduced.
- The preference is transient conversation state, not a provider body setting or database preference.

## Verification and limitations

Executed by the parent with bytecode generation disabled:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s app/src/test/python -p 'test_*.py'
git diff --check
```

- 237 Python source-contract tests passed: 226 baseline plus 11 new GPT-speed contracts.
- Existing context-learning contract updated to assert the normalized `next` state while retaining route invalidation assertions.
- Six Kotlin test classes exist for model eligibility, state policy, request construction/JSON, Bundle transfer, animation policy, and Compose gestures.
- The final Compose test delta includes explicit manual-clock reentry rejection and switching away/back with owner-owned NORMAL reset.
- Kotlin/Compose tests have NOT been executed. No Gradle/Android compilation, CI build, push, APK installation, or real provider requests were performed.
- Static review and Python contracts do not prove rendered behavior, Kotlin compilation, provider acceptance, pricing, or actual latency. These remain later validation work once compilation/testing is authorized.
