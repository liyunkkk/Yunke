#!/usr/bin/env python3
"""Offline standard-library-only StreamDiag v2 allowlist exporter.

No capture, adb, uploads or raw input in errors. Accept only extracted exact v2
lines, never arbitrary logcat headers, legacy notes or payloads. Reviewed static
emitter labels are fixed; token provenance and final output still require
same-build golden validation.
"""
import argparse
import csv
import io
import json
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

MAX_INTEGER = (1 << 63) - 1
MAX_LINE = 8192
MAX_RECORDS = 200000
# Reviewed static vocabulary from StreamDiagnosticLabels / FrameDiagnosticPage
# (81cb core input) and the 56 render/data/recorder labels (da5c input).
# Do not infer labels from a user's log, arbitrary suffixes or class names.
PAGES = frozenset("""
    Unknown Transition BrowserOverlay ConversationDrawer Home Chat Browser Terminal Tools
    AgentTaskPreference VirtualDisplayRecovery Haptics Skills Permissions SystemEnhance
    Settings SpeechSettings TtsSettings VoiceSettings AuxiliaryVision TitleModel
    ErrorReconnectSettings SubAgents VoiceModeSettings AppearanceSettings DataBackup
    Memory LinuxEnvironment SharedFolders Workspace LinuxFiles ModelProviders McpServers
    McpServerDetail ModelProviderDetail ModelProviderAuthMethod ModelProviderNew
    ContextCompression Assistants AssistantEdit UsageStats ManageChats
""".split())
KINDS = frozenset("""
    unknown replayBatch start.text start.thinking start.toolCall delta.text delta.thinking
    delta.toolCall end.text end.thinking end.toolCall runStarted roundStarted modelRetry
    reconnect providerRequest providerResponse assistantReceived childContext usage.projected
    usage.receipt supplement toolStarted toolFinished hostedToolStarted hostedToolFinished
    toolImages compactWaiting compactStarted compacted runFinished runFailed
    questionRequested questionResolved
""".split())
STAGES = frozenset("""
    ui.enqueue ui.runEvent ui.replay ui.flush ui.flushDelay ui.delta.received ui.delta.gap
    ui.flush.blockSwitch ui.flush.nonDelta ui.flush.timer
    ui.messages.apply ui.messages.transform ui.messages.normalize ui.messages.publish
    ui.conversation.route ui.conversation.owner ui.conversation.waiting ui.conversation.publish
    ui.summaries.refresh ipc.delta.delay ipc.client.receive ipc.attach.receive
    ipc.client.decode.live ipc.client.callback.live ipc.attach.decode.live ipc.attach.decode.replay
    ipc.attach.callback.live ipc.attach.callback.replay ipc.attach.callback.replayBatch
    runtime.checkpoint.accept runtime.checkpoint.append runtime.checkpoint.flush.size
    runtime.checkpoint.flush.timer runtime.checkpoint.flush.boundary runtime.checkpoint.flush.seal
    chat.compose timeline.project timeline.prefaces gallery.scan gallery.parse gallery.hit gallery.skip
    markdown.target markdown.coalesced markdown.queueWait markdown.parse markdown.superseded
    markdown.publishBlock markdown.targetToPublish markdown.publish markdown.layout markdown.blockDraw
    reveal.frameGap reveal.step reveal.backlog reveal.remeasure scroll.state tail.clippedPx tail.breachPx
    follow.initialSnap follow.decision follow.frameGap follow.scroll follow.step follow.cancelled
    follow.viewportRecovery heap.usedBytes main.frameMessages main.otherMessages main.message main.doFrame
    frame.total frame.layout frame.draw frame.sync frame.gpu frame.input frame.unknown frame.animation
    frame.command frame.swap frame.unaccounted frame.overlap frame.vsyncLate frame.metricsDropped frame.deadline
    persistence.write persistence.read persistence.serialize persistence.queueWait
    render.measure render.draw render.compose
    render.unknown markdown.unknown reveal.unknown settings.unknown persistence.unknown
    usage.unknown datastore.unknown citation.unknown runtime.checkpoint.unknown
    markdown.annotated.build markdown.annotated.cell markdown.annotated.raw markdown.citation.strip
    markdown.hidden.childHeight markdown.hidden.measure markdown.hidden.reportHeight
    markdown.stable.draw markdown.stable.measure markdown.tail.draw markdown.tail.measure
    reveal.drawContent reveal.graphemes.append reveal.graphemes.cacheHit reveal.graphemes.rebuild
    reveal.layout.update reveal.measure reveal.paths.append reveal.paths.cacheHit reveal.paths.nextGrapheme
    reveal.paths.rebuild reveal.record.cacheHit reveal.record.update reveal.saveLayer
    runtime.checkpoint.buffer.chars runtime.checkpoint.buffer.events runtime.checkpoint.buffer.residency
    runtime.checkpoint.encode runtime.checkpoint.lockWait runtime.checkpoint.merge runtime.checkpoint.write
    settings.commitTail settings.composition settings.editEntryWait settings.root.draw settings.root.measure
    settings.transform usage.commitTail usage.editEntryWait usage.ledger.encodeEvents usage.ledger.serialize
    usage.ledger.update usage.load.dao.conversations usage.load.dao.liveIds usage.load.dao.messages
    usage.load.dao.perDay usage.load.decode usage.lockWait usage.transform
    main.uninstrumented main.nonReveal chat.content.commit list.measure list.place
""".split()) | frozenset("ui.event." + kind for kind in KINDS) | frozenset("frame.page." + page for page in PAGES)
# Fixed enums only. A new emitter enum requires explicit review, never a regex.
RUNTIME_COUNTERS = frozenset({
    "art.gc.gc-count", "art.gc.gc-time", "art.gc.bytes-allocated", "art.gc.bytes-freed",
    "art.gc.blocking-gc-count", "art.gc.blocking-gc-time",
})
COUNTERS = frozenset({
    "ringOverwritten", "slowBudgetDropped", "frameBudgetDropped",
    "spanOutputTruncated", "tokenSaturated", "eventLinksOverwritten",
    "mainRingOverwritten", "mainOutputTruncated", "noteBudgetDropped",
    "closedRejectedRecords", "lateSpans",
})
FRAME_NUMBERS = frozenset({
    "abnormalFrame", "intendedVsyncNs", "vsyncNs", "totalNs",
    "deadlineNs", "metricsDropped", "unknownNs", "inputNs", "animationNs",
    "layoutNs", "drawNs", "syncNs", "commandNs", "swapNs", "gpuNs",
    "unaccountedNs", "overlapNs", "vsyncLateNs",
})
COMMON = frozenset({"v", "type", "id", "windowStartNs", "windowEndNs", "final", "boundary"})
FIELDS = {
    "window": COMMON | COUNTERS | {
        "anchorNanoNs", "uptimeMs", "elapsedRealtimeNs", "package", "versionCode",
        "versionName", "buildType", "duration", "heap", "gcTime", "spanCapacity",
        "slowBudget", "frameBudget", "boundary", "admission", "openSpansAtCutoff",
        "postCloseObservation",
    },
    "span": COMMON | {
        "span", "parent", "stage", "beginNs", "endNs", "thread", "main", "duration",
        "value", "runToken", "conversationToken", "visibility", "kind", "replay",
        "eventSeq", "sourceSpan", "page", "pageEnd",
    },
    "frame": COMMON | FRAME_NUMBERS | {"page", "pageEnd", "pageChanged", "pageSource", "deadlineMiss", "accounting"},
    "mainMessage": COMMON | {
        "beginNs", "endNs", "frameDispatch", "coveredNs", "revealNs",
        "uninstrumentedNs", "nonRevealNs", "accounting",
    },
    "runtime": COMMON | {"runtimeCounter", "supported", "cumulative", "delta"},
}
BOOLEANS = frozenset({"final", "main", "replay", "pageChanged", "deadlineMiss", "supported", "frameDispatch"})
ENUMS = {
    "visibility": {"Unknown", "Selected", "Hidden"}, "kind": KINDS,
    "stage": STAGES, "runtimeCounter": RUNTIME_COUNTERS,
    "page": PAGES, "pageEnd": PAGES, "boundary": {"admissionSnapshot"},
    "pageSource": {"route"}, "buildType": {"unknown", "debug", "release"},
    "package": {"io.github.mangi.eta"}, "duration": {"inclusive"},
    "heap": {"proxyNotAllocationStack"}, "gcTime": {"runtimeCounterNotPause"},
    "admission": {"open", "closed"}, "postCloseObservation": {"notTracked"},
    "accounting": {"frameMetricsResidualNotAdditive", "dispatchSubsetsNotAdditive"},
}


class Rejected(ValueError):
    """Reason codes only, never attach the raw line/field/value/path."""


def integer(value, signed=False):
    if not re.fullmatch(r"-?[0-9]{1,19}" if signed else r"[0-9]{1,19}", value):
        raise Rejected("invalid_integer")
    number = int(value)
    if not -(1 << 63) <= number <= MAX_INTEGER:
        raise Rejected("invalid_integer")
    return number


def parse_line(line):
    if len(line) > MAX_LINE:
        raise Rejected("line_too_long")
    line = line.rstrip("\r\n")
    if not line.startswith("StreamDiag "):
        raise Rejected("not_v2_diagnostic")
    if any(ord(c) < 32 or ord(c) > 126 for c in line):
        raise Rejected("invalid_characters")
    fields = {}
    for word in line[len("StreamDiag "):].split(" "):
        if word.count("=") != 1:
            raise Rejected("invalid_format")
        key, value = word.split("=")
        if not key or not value or key in fields:
            raise Rejected("duplicate_or_empty_field")
        fields[key] = value
    if fields.get("v") != "2" or fields.get("type") not in FIELDS:
        raise Rejected("unsupported_version_or_type")
    kind = fields["type"]
    if set(fields) - FIELDS[kind]:
        raise Rejected("unknown_field")
    if not COMMON <= set(fields):
        raise Rejected("missing_identity_or_interval")
    if not re.fullmatch(r"[0-9a-f]{8}", fields["id"]):
        raise Rejected("invalid_session_token")
    result = {}
    for key, value in fields.items():
        if key in {"type", "id"}:
            result[key] = value
        elif key in BOOLEANS:
            if value not in {"true", "false"}:
                raise Rejected("invalid_boolean")
            result[key] = value == "true"
        elif key == "versionCode" and value == "unknown":
            result[key] = None
        elif key in ENUMS:
            if value not in ENUMS[key]:
                raise Rejected("unapproved_enum")
            result[key] = value
        elif key == "versionName":
            # Only numeric dotted release versions are exportable; arbitrary
            # commit strings/custom build labels must be redacted by emitter.
            if value != "unknown" and not re.fullmatch(r"[0-9]{1,4}(?:\.[0-9]{1,4}){1,3}", value):
                raise Rejected("unapproved_version_name")
            result[key] = value
        elif key in {"cumulative", "delta"} and value == "unknown":
            result[key] = None
        else:
            result[key] = integer(value, signed=key in {"value", "delta"})
    if result["windowEndNs"] < result["windowStartNs"]:
        raise Rejected("reversed_window")
    if kind == "span":
        if not {"span", "parent", "stage", "beginNs", "endNs"} <= set(result):
            raise Rejected("missing_span_fields")
        if result["endNs"] < result["beginNs"]:
            raise Rejected("reversed_span")
    if kind in {"frame", "mainMessage"} and "accounting" in result:
        expected = "frameMetricsResidualNotAdditive" if kind == "frame" else "dispatchSubsetsNotAdditive"
        if result["accounting"] != expected:
            raise Rejected("invalid_accounting_relation")
    if kind == "mainMessage":
        if not {"beginNs", "endNs", "frameDispatch", "coveredNs", "revealNs",
                "uninstrumentedNs", "nonRevealNs", "accounting"} <= set(result):
            raise Rejected("missing_main_message_fields")
        duration = result["endNs"] - result["beginNs"]
        if not (0 <= result["revealNs"] <= result["coveredNs"] <= duration):
            raise Rejected("invalid_dispatch_subsets")
        if (result["uninstrumentedNs"] != duration - result["coveredNs"] or
                result["nonRevealNs"] != duration - result["revealNs"]):
            raise Rejected("invalid_dispatch_residual")
    if kind == "frame":
        parts = ("unknownNs", "inputNs", "animationNs", "layoutNs", "drawNs", "syncNs", "commandNs", "swapNs")
        if "totalNs" in result and all(key in result for key in parts):
            residual = result["totalNs"] - sum(result[key] for key in parts)
            for key, expected in (("unaccountedNs", max(0, residual)), ("overlapNs", max(0, -residual))):
                if key in result and result[key] != expected:
                    raise Rejected("invalid_frame_residual")
        if {"vsyncNs", "intendedVsyncNs", "vsyncLateNs"} <= set(result):
            if result["vsyncLateNs"] != max(0, result["vsyncNs"] - result["intendedVsyncNs"]):
                raise Rejected("invalid_vsync_lateness")
    if kind == "runtime" and not {"runtimeCounter", "supported"} <= set(result):
        raise Rejected("missing_runtime_fields")
    if kind == "runtime" and not result["supported"]:
        # Do not convert unavailable ART counters into an observed zero.
        if any(result.get(key) is not None for key in ("cumulative", "delta")):
            raise Rejected("unsupported_runtime_with_value")
    return result


def parse_lines(lines, max_records=MAX_RECORDS):
    records, rejected, sessions = [], Counter(), {}
    total = truncated = 0
    for line in lines:
        total += 1
        try:
            record = parse_line(line)
        except Rejected as exc:
            rejected[str(exc)] += 1
            continue
        if len(records) >= max_records:
            truncated += 1
            continue
        session_id = record.pop("id")
        # Random diagnostic session IDs are also re-tokenized; never export the
        # original id or maintain a reverse mapping in the output.
        if session_id not in sessions:
            sessions[session_id] = "s" + str(len(sessions) + 1)
        record["session"] = sessions[session_id]
        records.append(record)
    return records, {
        "inputLines": total, "acceptedRecords": len(records),
        "rejectedLines": sum(rejected.values()), "rejectionReasons": dict(sorted(rejected.items())),
        "exporterTruncatedRecords": truncated, "recordLimit": max_records,
    }


def interval(record):
    if record["type"] in {"span", "mainMessage"}:
        return record["beginNs"], record["endNs"]
    if record["type"] == "frame" and "intendedVsyncNs" in record and "totalNs" in record:
        start = record["intendedVsyncNs"]
        return start, start + record["totalNs"]
    return record["windowStartNs"], record["windowEndNs"]


def overlaps(bounds, start, end):
    left, right = bounds
    if left == right:
        return start <= left < end
    return left < end and right > start


def union_ns(intervals):
    current_end, total = None, 0
    for start, end in sorted(intervals):
        if current_end is None or start > current_end:
            total += end - start
            current_end = end
        elif end > current_end:
            total += end - current_end
            current_end = end
    return total


def summarize(records, audit, start, end, stats=None):
    if not (0 <= start < end <= MAX_INTEGER):
        raise Rejected("invalid_requested_window")
    selected = [r for r in records if overlaps(interval(r), start, end)]
    groups = defaultdict(list)
    for record in selected:
        groups[record["session"]].append(record)
    summaries = []
    for session, group in sorted(groups.items()):
        windows = [r for r in group if r["type"] == "window"]
        spans = [r for r in group if r["type"] == "span"]
        frames = [r for r in group if r["type"] == "frame"]
        stages = []
        stage_groups = defaultdict(list)
        for record in spans:
            # Missing token/visibility/page attribution is unknown, not inherited
            # from a nearby window/frame or from another run.
            identity = tuple(record.get(k) for k in
                             ("stage", "runToken", "conversationToken", "visibility", "page", "pageEnd"))
            stage_groups[identity].append(record)
        for identity, stage_records in sorted(stage_groups.items(), key=lambda item: repr(item[0])):
            stages.append({
                **dict(zip(("stage", "runToken", "conversationToken", "visibility", "page", "pageEnd"), identity)),
                "count": len(stage_records),
                "clippedInclusiveNs": sum(max(0, min(r["endNs"], end) - max(r["beginNs"], start))
                                          for r in stage_records),
                "maxFullSpanNs": max(r["endNs"] - r["beginNs"] for r in stage_records),
            })
        known_deadlines = [r for r in frames if "totalNs" in r and "deadlineNs" in r]
        explicit_deadlines = [r for r in frames if "deadlineMiss" in r]
        coverage = union_ns((max(r["windowStartNs"], start), min(r["windowEndNs"], end)) for r in windows)
        summaries.append({
            "session": session, "windowCoverageNs": coverage, "windowUncoveredNs": end - start - coverage,
            "counterScope": "exact_record_interval_not_prorated_or_assumed_cumulative",
            "windows": [{**r, "partialRequestedOverlap": r["windowStartNs"] < start or r["windowEndNs"] > end,
                         "counters": {key: r.get(key) for key in sorted(COUNTERS)}} for r in windows],
            "spanStageInclusive": stages,
            "frames": {
                "count": len(frames), "withTotalAndDeadline": len(known_deadlines),
                "missingTotalOrDeadline": len(frames) - len(known_deadlines),
                "frameMetricsDeadlineExceededComputed": (sum(r["totalNs"] > r["deadlineNs"] for r in known_deadlines)
                                                          if known_deadlines else None),
                "frameMetricsDeadlineMissReported": (sum(r["deadlineMiss"] for r in explicit_deadlines)
                                                       if explicit_deadlines else None),
                "appFrameTimelineDeadlineMissed": None,
                "metricsDroppedKnownRecordSum": (sum(r["metricsDropped"] for r in frames if "metricsDropped" in r)
                                                  if any("metricsDropped" in r for r in frames) else None),
                "metricsDroppedMissingRecords": sum("metricsDropped" not in r for r in frames),
                "routePageAttribution": "only; not necessarily the rendered message's conversation",
            },
            "runtimeCounters": [r for r in group if r["type"] == "runtime"],
            "clock": {
                "spanAndWindow": "System.nanoTime/monotonic",
                "frame": "producer_intendedVsyncNs; verify same-domain golden fixture",
                "traceAlignment": "not_verified; use anchorNanoNs/elapsedRealtimeNs/uptimeMs with uncertainty",
                "anchors": [{k: r.get(k) for k in ("anchorNanoNs", "uptimeMs", "elapsedRealtimeNs")} for r in windows],
            },
        })
    return {
        "schema": "eta.streamdiag.summary.v2",
        "requestedWindow": {"startNs": start, "endNs": end, "clock": "nanoTime_monotonic"},
        "parser": audit, "outsideRequestedWindow": len(records) - len(selected),
        "selectedRecords": selected, "groups": summaries,
        "traceHealth": stats if stats is not None else {"status": "missing_stats", "rows": None},
        "limits": ["Raw diagnostic id is re-tokenized; no rejected source is exported.",
                   "Anonymous numeric token provenance must be enforced by the emitter.",
                   "Inclusive parent/child/parallel durations must not be added.",
                   "FrameMetrics deadline exceedance is not FrameTimeline App Deadline Missed.",
                   "Runtime GC time counters are not stop-the-world pause durations.",
                   "Missing counters, clock mapping and sampling tables are unknown, not zero.",
                   "Records and counters spanning a selection boundary are retained without proration.",
                   "admissionSnapshot windows are arrival/drain sets, not completion-time membership or proof of complete coverage."],
    }


# Optional Perfetto tables are declared explicitly; a missing declaration never means zero rows.
TRACE_TABLES = frozenset({
    "actual_frame_timeline_slice", "thread_state", "slice", "sched",
    "cpu_profile_stack_sample", "heap_profile_allocation",
})
LOSS_COUNTERS = ("ringOverwritten", "spanOutputTruncated", "slowBudgetDropped",
                 "frameBudgetDropped", "mainRingOverwritten", "mainOutputTruncated")
FRAME_PARTS = ("unknownNs", "inputNs", "animationNs", "layoutNs", "drawNs",
               "syncNs", "commandNs", "swapNs")


def strict_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise Rejected("duplicate_json_key")
        result[key] = value
    return result


def parse_table_status_json(text):
    """A fixed-schema inventory only; no SQL, paths, free-form reasons or names."""
    try:
        value = json.loads(text, object_pairs_hook=strict_object)
    except (ValueError, TypeError):
        raise Rejected("invalid_table_status_json") from None
    if (not isinstance(value, dict) or set(value) != {"schema", "tables"} or
            value["schema"] != "eta.streamdiag.tables.v2" or not isinstance(value["tables"], dict)):
        raise Rejected("invalid_table_status_schema")
    tables = value["tables"]
    if set(tables) - TRACE_TABLES:
        raise Rejected("unapproved_table")
    for entry in tables.values():
        if (not isinstance(entry, dict) or set(entry) != {"available", "rows"} or
                type(entry["available"]) is not bool):
            raise Rejected("invalid_table_status_entry")
        rows = entry["rows"]
        if rows is not None and (type(rows) is not int or not 0 <= rows <= MAX_INTEGER):
            raise Rejected("invalid_table_row_count")
        if not entry["available"] and rows is not None:
            raise Rejected("unavailable_table_with_count")
    return tables


def table_availability(tables=None):
    out = {}
    for name in sorted(TRACE_TABLES):
        entry = (tables or {}).get(name)
        if entry is None or not entry["available"]:
            out[name] = {"status": "unavailable", "rows": None}
        elif entry["rows"] is None:
            out[name] = {"status": "available_count_unknown", "rows": None}
        else:
            out[name] = {"status": "observed_zero" if entry["rows"] == 0 else "observed", "rows": entry["rows"]}
    return out


def frame_accounting(frame):
    residual = None
    if "totalNs" in frame and all(key in frame for key in FRAME_PARTS):
        residual = frame["totalNs"] - sum(frame[key] for key in FRAME_PARTS)
    lateness = (max(0, frame["vsyncNs"] - frame["intendedVsyncNs"])
                if "vsyncNs" in frame and "intendedVsyncNs" in frame else None)
    return {
        "relation": "frameMetricsResidualNotAdditive",
        "totalNs": frame.get("totalNs"),
        "components": {key: frame.get(key) for key in FRAME_PARTS},
        "gpuNsOverlappingCommandSwap": frame.get("gpuNs"),
        "unaccountedNs": frame.get("unaccountedNs", max(0, residual) if residual is not None else None),
        "overlapNs": frame.get("overlapNs", max(0, -residual) if residual is not None else None),
        "vsyncLateNsOverlappingUnknown": frame.get("vsyncLateNs", lateness),
        "residualSource": "emitted" if "unaccountedNs" in frame else ("derived" if residual is not None else "unavailable"),
        "unknownTiming": "componentDurationNotAnInstrumentedSpanGap",
    }


def where_time(records, audit, start, end, stats=None, tables=None):
    """Two independent axes: FrameMetrics components and retained main-thread interval union.

    Do not locate unknownNs on a guessed prefix, equate a Looper dispatch with
    FrameTimeline, prorate whole-message coverage, or add nested spans.
    """
    result = summarize(records, audit, start, end, stats)
    frames = []
    for frame in result["selectedRecords"]:
        if frame["type"] != "frame":
            continue
        group = [r for r in records if r["session"] == frame["session"]]
        windows = [r for r in group if r["type"] == "window" and overlaps(interval(r), *interval(frame))]
        counters = {key: (max(r[key] for r in windows if key in r)
                          if any(key in r for r in windows) else None) for key in LOSS_COUNTERS}
        lost = any(value is not None and value > 0 for value in counters.values())
        loss_status = ("records_lost_or_truncated" if lost else
                       "integrity_unavailable" if any(value is None for value in counters.values()) else
                       "no_loss_reported_not_complete_coverage")
        entry = {
            "session": frame["session"], "abnormalFrame": frame.get("abnormalFrame"),
            "page": frame.get("page"), "metrics": frame_accounting(frame),
            "recordIntegrity": {"status": loss_status, "counters": counters,
                                "scope": "overlapping_report_windows_not_localized_to_frame"},
        }
        if "totalNs" not in frame or "intendedVsyncNs" not in frame:
            entry["instrumentation"] = {"status": "unavailable"}
            frames.append(entry)
            continue
        left, right = interval(frame)
        left, right = max(left, start), min(right, end)
        spans = [r for r in group if r["type"] == "span" and r.get("main") is True]
        clipped = [(max(r["beginNs"], left), min(r["endNs"], right))
                   for r in spans if overlaps(interval(r), left, right)]
        reveal = [(max(r["beginNs"], left), min(r["endNs"], right))
                  for r in spans if r["stage"].startswith("reveal.") and overlaps(interval(r), left, right)]
        observed = union_ns(clipped)
        reveal_observed = union_ns(reveal)
        # None means no main span table was supplied for this session; 0 means
        # some main spans were supplied but none overlap this particular frame.
        entry["instrumentation"] = {
            "status": "observed" if spans else "unavailable",
            "clippedFrameIntervalNs": right - left,
            "mainSpanUnionNs": observed if spans else None,
            "revealSpanUnionNs": reveal_observed if spans else None,
            "observedOutsideRevealNs": observed - reveal_observed if spans else None,
            "noRetainedSpanCoverageNs": right - left - observed if spans else None,
            "gapMeaning": "unattributed_or_record_loss" if lost else "unattributed_not_proven_idle",
            "relation": "revealSubsetOfUnionNotAdditive",
        }
        messages = [r for r in group if r["type"] == "mainMessage" and overlaps(interval(r), left, right)]
        entry["slowMainDispatches"] = {
            "status": "observed" if messages else "unavailable",
            "source": "looperSlowDispatchNotFrameTimelineOrCpuTime",
            "overlappingEnvelopeUnionNs": union_ns((max(r["beginNs"], left), min(r["endNs"], right))
                                                  for r in messages) if messages else None,
            "fullDispatchAccountingNotProrated": [{key: r[key] for key in
                ("beginNs", "endNs", "frameDispatch", "coveredNs", "revealNs", "uninstrumentedNs", "nonRevealNs", "accounting")}
                for r in messages],
        }
        frames.append(entry)
    result["whereTime"] = {
        "frames": frames, "traceTables": table_availability(tables),
        "frameTimeline": {"status": "unavailable", "appDeadlineMissed": None},
        "clockRelation": "frameAndNanoTimeAlignmentRequiresDeviceVerification",
        "relationship": "metricsDispatchAndSpanAxesOverlapDoNotAdd",
    }
    return result


def parse_stats_csv(text):
    """Dedicated SELECT name,idx,value,severity,source FROM stats CSV only."""
    try:
        reader = csv.DictReader(io.StringIO(text))
        if reader.fieldnames != ["name", "idx", "value", "severity", "source"]:
            raise Rejected("invalid_stats_schema")
        rows = []
        for raw in reader:
            if set(raw) != set(reader.fieldnames) or any(v is None for v in raw.values()):
                raise Rejected("invalid_stats_row")
            if not re.fullmatch(r"[a-z][a-z0-9_]{0,159}", raw["name"]):
                raise Rejected("invalid_stats_name")
            if raw["severity"] not in {"info", "warning", "warn", "error", "data_loss"}:
                raise Rejected("invalid_stats_severity")
            if raw["source"] not in {"trace", "analysis", "unknown"}:
                raise Rejected("invalid_stats_source")
            idx = None if raw["idx"] in {"", "[NULL]"} else integer(raw["idx"])
            rows.append({"name": raw["name"], "idx": idx, "value": integer(raw["value"], signed=True),
                         "severity": raw["severity"], "source": raw["source"]})
    except (csv.Error, TypeError):
        raise Rejected("invalid_stats_csv") from None
    problem = [r for r in rows if r["value"] != 0 and (r["severity"] in {"error", "data_loss", "warning", "warn"}
               or re.search(r"lost|drop|overrun|overwrite|discard|trunc", r["name"]))]
    if not rows:
        status = "missing_stats"
    elif any(r["severity"] in {"error", "data_loss"} for r in problem):
        status = "compromised"
    elif problem:
        status = "warning_or_loss_counter"
    else:
        status = "no_nonzero_problems_reported_not_loss_free_certification"
    return {"status": status, "scope": "whole_trace_not_requested_window", "rows": rows,
            "problemRows": problem, "missingExpectedStats": "unknown_no_assumption_of_zero"}


def render_sql(text, start, end, upid):
    if not (0 <= start < end <= MAX_INTEGER and 0 <= upid <= MAX_INTEGER):
        raise Rejected("invalid_sql_window")
    if not all(token in text for token in ("__START_NS__", "__END_NS__", "__UPID__")):
        raise Rejected("not_window_sql_template")
    return text.replace("__START_NS__", str(start)).replace("__END_NS__", str(end)).replace("__UPID__", str(upid))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    summary = sub.add_parser("summary", help="Export extracted exact v2 diagnostic lines only")
    summary.add_argument("input", type=Path)
    summary.add_argument("--stats-csv", type=Path)
    breakdown = sub.add_parser("where-time", help="Frame components, retained-span union and slow main dispatch accounting")
    breakdown.add_argument("input", type=Path)
    breakdown.add_argument("--stats-csv", type=Path)
    breakdown.add_argument("--table-status-json", type=Path)
    render = sub.add_parser("render-sql", help="Render SQL with explicit TRACE-TIME bounds and UPID")
    render.add_argument("--template", choices=["window-analysis", "cpu-samples", "heap-samples"], required=True)
    render.add_argument("--upid", type=int, required=True)
    for cmd in (summary, breakdown, render):
        cmd.add_argument("--start-ns", type=int, required=True)
        cmd.add_argument("--end-ns", type=int, required=True)
        cmd.add_argument("--output", type=Path)
    args = parser.parse_args(argv)
    try:
        if args.command == "render-sql":
            text = (Path(__file__).parent / "sql" / (args.template + ".sql")).read_text(encoding="utf-8")
            output = render_sql(text, args.start_ns, args.end_ns, args.upid)
            rejected = False
        else:
            with args.input.open(encoding="utf-8") as source:
                records, audit = parse_lines(source)
            stats = parse_stats_csv(args.stats_csv.read_text(encoding="utf-8")) if args.stats_csv else None
            if args.command == "where-time":
                tables = parse_table_status_json(args.table_status_json.read_text(encoding="utf-8")) if args.table_status_json else None
                report = where_time(records, audit, args.start_ns, args.end_ns, stats, tables)
            else:
                report = summarize(records, audit, args.start_ns, args.end_ns, stats)
            output = json.dumps(report, indent=2, sort_keys=True) + "\n"
            rejected = audit["rejectedLines"] > 0 or audit["exporterTruncatedRecords"] > 0
        if args.output:
            args.output.write_text(output, encoding="utf-8")
        else:
            sys.stdout.write(output)
        # A safe summary may still be written, but invalid/truncated input fails
        # so CI cannot silently accept a privacy/schema gap.
        return 2 if rejected else 0
    except (Rejected, OSError, UnicodeError):
        sys.stderr.write("Diagnostic export failed: invalid schema/window or unavailable UTF-8 file; details redacted.\n")
        return 2


if __name__ == "__main__":
    sys.exit(main())
