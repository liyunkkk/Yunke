"""Emitter-schema golden lines with synthetic numbers, never real capture data.

Static spellings come from reviewed f9f5713b core output templates and
FramePageTimeline; 944b31c9 supplies the 56 render/data/recorder labels. These are
schema compatibility tests, not device capture or clock-alignment evidence.
"""
import contextlib
import importlib.util
import io
import json
import re
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("eta_diag_analyze", Path(__file__).with_name("analyze.py"))
diag = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(diag)

PREFIX = "StreamDiag id=12ab34cd windowStartNs=100 windowEndNs=300 boundary=admissionSnapshot final=false v=2 type={kind} "
WINDOW = PREFIX.format(kind="window") + (
    "anchorNanoNs=300 uptimeMs=1 elapsedRealtimeNs=900 package=io.github.mangi.eta "
    "versionCode=42 versionName=1.2.3 buildType=unknown duration=inclusive "
    "heap=proxyNotAllocationStack gcTime=runtimeCounterNotPause spanCapacity=2048 "
    "slowBudget=256 frameBudget=120 ringOverwritten=2 slowBudgetDropped=3 "
    "frameBudgetDropped=4 spanOutputTruncated=5 tokenSaturated=6 "
    "eventLinksOverwritten=7 mainRingOverwritten=8 mainOutputTruncated=9 noteBudgetDropped=10 "
    "admission=open openSpansAtCutoff=1 closedRejectedRecords=0 lateSpans=0 "
    "lateAfterFinal=notTracked completeCpu=notClaimed gitSha=" + "a" * 40 + " "
    "protectedSpanCapacity=2048 protectedBudgetDropped=0 frameCaptureTruncated=0 previousWindowSpans=0 "
    "rowTokenSaturated=0 listSampleOverwritten=0 frameBudgetEvicted=0 callbackRejected=0 openAtStop=0 "
    "openIdDropped=0 stopCutoffNs=-1 partial=false anchorUncertaintyNs=10 osPid=100 javaThreadId=7 osTid=107 "
    "threadIdCapacity=128 threadIdSaturated=1"
)
RENDER_FIELDS = ("listToken=1 rowToken=2 rowType=agent blockIndex=0 blockType=paragraph blockChars=12 "
                 "component=unknown renderIdentity=anonymousSessionLocalNotEventCausality")
SPAN = PREFIX.format(kind="span") + (
    "span=1 parent=0 stage=ui.flush beginNs=120 endNs=280 thread=1 main=true "
    "duration=inclusive value=5 runToken=3 conversationToken=7 visibility=Selected "
    "kind=delta.text replay=false eventSeq=4 sourceSpan=0 page=Chat pageEnd=Chat "
    "javaThreadId=1 osTid=100 osTidUnknown=-1 partialAtCutoff=false " + RENDER_FIELDS
)
FRAME = PREFIX.format(kind="frame") + (
    "abnormalFrame=0 page=Chat pageEnd=Settings pageChanged=true pageSource=route "
    "intendedVsyncNs=150 vsyncNs=155 totalNs=40 deadlineNs=30 deadlineMiss=true "
    "metricsDropped=2 unknownNs=1 inputNs=2 animationNs=3 layoutNs=4 drawNs=5 "
    "syncNs=6 commandNs=7 swapNs=8 gpuNs=9 unaccountedNs=4 overlapNs=0 vsyncLateNs=5 "
    "accounting=frameMetricsResidualNotAdditive firstDraw=false partialAtCutoff=false pageSegment=1"
)
MAIN_MESSAGE = PREFIX.format(kind="mainMessage") + (
    "beginNs=140 endNs=220 frameDispatch=true coveredNs=30 revealNs=10 "
    "uninstrumentedNs=50 nonRevealNs=70 accounting=dispatchSubsetsNotAdditive partial=false "
    "cpuNs=20 wallMinusCpuNs=60 cpuAccounting=threadCpuCounterNotBlockedDiagnosis"
)
RUNTIME = PREFIX.format(kind="runtime") + (
    "runtimeCounter=art.gc.bytes-allocated supported=false"
)
RUNTIME_SUPPORTED = PREFIX.format(kind="runtime") + (
    "runtimeCounter=art.gc.bytes-freed supported=true cumulative=10 delta=unknown"
)
# Independent complete P0 correlation fixtures; synthetic numbers, no device data.
CORRELATION = {
    "frameCorrelation": "abnormalFrame=0 intendedVsyncNs=150 frameTotalNs=40 matched=1 emitted=1 omitted=0 lookbackMatched=9 lookbackEmitted=8 lookbackOmitted=1 sourceWindowLoss=false sourceWindowUnknown=false mainSpanUnionNs=40 frameWallOutsideSpansNs=0 evidenceIncomplete=false coverage=instrumentedCompletedSpansOnly evidenceComplete=notClaimed zeroMatch=notProofOfNoMainWork capture=anomalyCallbackAndAdmissionSnapshot rule=mainSpanOverlapNotCausality accounting=wallUnionNotCpuOrFrameParts",
    "spanOverlap": "abnormalFrame=0 span=1 parent=0 stage=ui.flush beginNs=120 endNs=280 overlapNs=40 durationNs=160 selfUpperBoundNs=160 selfAccounting=directChildUnionUpperBoundIfMissingChildren duration=inclusiveNotAdditive value=5 eventSeq=4 runToken=3 conversationToken=7 " + RENDER_FIELDS,
    "frameLookback": "abnormalFrame=0 span=2 parent=0 stage=list.place beginNs=110 endNs=140 durationNs=30 lookbackNs=200000000 relation=precedingNotFrameOverlap " + RENDER_FIELDS,
    "frameList": "abnormalFrame=0 listToken=1 sampleNs=140 ageAtFrameEndNs=50 sourcePage=Chat sourceSegment=1 relation=sourceMatchedObservedPostLayoutNotExactFrame visibility=layoutSlotsNotClippedPixels messageCount=12 totalRows=14 firstIndex=8 firstOffset=4 viewportStart=-10 viewportEnd=900 visibleCount=1 emittedRows=1 omittedRows=0",
    "frameListRow": "abnormalFrame=0 listToken=1 sampleNs=140 rowToken=2 index=8 offset=-4 size=90",
    "frameMainMessage": "abnormalFrame=0 beginNs=140 endNs=220 overlapNs=40 relation=overlap frameDispatch=true coveredNs=30 uninstrumentedNs=50 cpuNs=-1 wallMinusCpuNs=-1 accounting=dispatchWallNotFrameParts cpuAccounting=threadCpuCounterNotBlockedDiagnosis omitted=0",
}
P0_EXTRA = {
    "observerCost": "observerPhase=Enter count=1 totalNs=2 maxNs=2 accounting=observerWallNotCpu includesLockWait=true recursiveMeasurement=false scope=sessionCumulative",
    "openSpan": "span=1 partial=true atCutoff=true stillOpenAtFinal=false cpuNs=unknown",
    "finalCompletion": "cutoffNs=300 loggerRejected=0 loggerFailed=0 drainGraceMs=250 completion=appendAndFlush privacyGate=honored evidenceComplete=notClaimed",
}
# Independent zero-loss window: previousWindowSpans and lateSpans are not loss
# counts, and thread saturation is tested separately from retained span evidence.
ZERO_WINDOW = re.sub(
    r"\b(ringOverwritten|slowBudgetDropped|frameBudgetDropped|spanOutputTruncated|"
    r"tokenSaturated|eventLinksOverwritten|mainRingOverwritten|mainOutputTruncated|"
    r"noteBudgetDropped|closedRejectedRecords|lateSpans|protectedBudgetDropped|"
    r"frameCaptureTruncated|previousWindowSpans|rowTokenSaturated|listSampleOverwritten|"
    r"frameBudgetEvicted|callbackRejected|openAtStop|openIdDropped|threadIdSaturated|"
    r"openSpansAtCutoff)=[0-9]+", r"\1=0", WINDOW)
ZERO_CORRELATION = CORRELATION["frameCorrelation"].replace(
    "matched=1 emitted=1 omitted=0", "matched=0 emitted=0 omitted=0").replace(
    "lookbackMatched=9 lookbackEmitted=8 lookbackOmitted=1", "lookbackMatched=0 lookbackEmitted=0 lookbackOmitted=0").replace(
    "mainSpanUnionNs=40 frameWallOutsideSpansNs=0", "mainSpanUnionNs=0 frameWallOutsideSpansNs=40")

# Independently pinned da5c render/data/recorder input, not derived from the
# parser's allowlist: an omitted real static label must fail compatibility.
RENDER_LABELS = frozenset("""
    markdown.annotated.build markdown.annotated.cell markdown.annotated.raw
    markdown.blockDraw markdown.citation.strip markdown.hidden.childHeight
    markdown.hidden.measure markdown.hidden.reportHeight markdown.parse
    markdown.publish markdown.publishBlock markdown.queueWait markdown.stable.draw
    markdown.stable.measure markdown.tail.draw markdown.tail.measure markdown.targetToPublish
    reveal.drawContent reveal.graphemes.append reveal.graphemes.cacheHit reveal.graphemes.rebuild
    reveal.layout.update reveal.measure reveal.paths.append reveal.paths.cacheHit
    reveal.paths.nextGrapheme reveal.paths.rebuild reveal.record.cacheHit reveal.record.update reveal.saveLayer
    runtime.checkpoint.buffer.chars runtime.checkpoint.buffer.events runtime.checkpoint.buffer.residency
    runtime.checkpoint.encode runtime.checkpoint.flush.boundary runtime.checkpoint.lockWait
    runtime.checkpoint.merge runtime.checkpoint.write settings.commitTail settings.composition
    settings.editEntryWait settings.root.draw settings.root.measure settings.transform
    usage.commitTail usage.editEntryWait usage.ledger.encodeEvents usage.ledger.serialize usage.ledger.update
    usage.load.dao.conversations usage.load.dao.liveIds usage.load.dao.messages usage.load.dao.perDay
    usage.load.decode usage.lockWait usage.transform
""".split())
STATS = (
    "name,idx,value,severity,source\n"
    "trace_sorter_negative_timestamp_dropped,,1,error,analysis\n"
    "ftrace_cpu_overrun_begin,0,0,data_loss,trace\n"
    "ftrace_cpu_overrun_end,0,3,data_loss,trace\n"
    "parse_warning,,1,warning,analysis\n"
)


class ParserTests(unittest.TestCase):
    def test_fixed_full_schema(self):
        records, audit = diag.parse_lines([WINDOW, SPAN, FRAME, RUNTIME])
        self.assertEqual(audit["rejectedLines"], 0)
        self.assertEqual([r["type"] for r in records], ["window", "span", "frame", "runtime"])
        self.assertEqual(records[1]["runToken"], 3)
        self.assertEqual(records[1]["conversationToken"], 7)
        self.assertEqual(records[1]["visibility"], "Selected")
        self.assertEqual(records[1]["eventSeq"], 4)
        self.assertNotIn("delta", records[3])
        self.assertEqual(records[0]["ringOverwritten"], 2)
        self.assertEqual(records[0]["boundary"], "admissionSnapshot")
        self.assertEqual(records[1]["kind"], "delta.text")
        self.assertEqual(records[1]["page"], "Chat")
        self.assertEqual(records[2]["pageEnd"], "Settings")
        self.assertIsNone(diag.parse_line(RUNTIME_SUPPORTED)["delta"])
        for line in (SPAN, WINDOW):
            with self.assertRaises(diag.Rejected):
                diag.parse_line(line.replace("duration=inclusive", "duration=160"))

    def test_same_build_emitter_field_sets_match_independent_golden(self):
        root = Path(__file__).resolve().parents[2]
        source = (root / "app/src/main/kotlin/io/github/mangi/eta/ui/components/StreamPerformanceDiagnostics.kt").read_text()
        prefix = source.split('val prefix = "StreamDiag id=${session.id} windowStartNs=', 1)[1].split("AppFileLogger.diagnosticInfo", 1)[0]
        common = {"id", "windowStartNs"} | set(re.findall(r"\b([A-Za-z][A-Za-z0-9]*)=", prefix))
        templates = {
            "window": source.split("v=2 type=window", 1)[1].split("completeCpu=notClaimed", 1)[0] + "completeCpu=notClaimed",
            "span": source.split("v=2 type=span", 1)[1].split("log.timingsBetween", 1)[0],
            "mainMessage": source.split("v=2 type=mainMessage", 1)[1].split("detail.frames.forEachIndexed", 1)[0],
            "frame": source.split("v=2 type=frame", 1)[1].split("val messages = log.between", 1)[0],
            "runtime": source.split("v=2 type=runtime", 1)[1].split("previousGc = currentGc", 1)[0],
        }
        golden = {"window": WINDOW, "span": SPAN, "frame": FRAME, "mainMessage": MAIN_MESSAGE, "runtime": RUNTIME_SUPPORTED}
        for kind, template in templates.items():
            with self.subTest(kind=kind):
                emitted = common | {"v", "type"} | set(re.findall(r"\b([A-Za-z][A-Za-z0-9]*)=", template))
                if kind == "window":
                    emitted |= {"package", "versionCode", "versionName", "buildType", "gitSha"}
                    tid_source = (root / "app/src/main/kotlin/io/github/mangi/eta/ui/components/DiagnosticObserverCosts.kt").read_text()
                    emitted |= set(re.findall(r"\b([A-Za-z][A-Za-z0-9]*)=", tid_source.split("fun fields()", 1)[1]))
                if kind == "span":
                    emitted |= set(re.findall(r"\b([A-Za-z][A-Za-z0-9]*)=", RENDER_FIELDS))
                if kind == "frame":
                    emitted |= {"page", "pageEnd", "pageChanged", "pageSource"}
                self.assertEqual(emitted, set(diag.parse_line(golden[kind])))

    def test_final_admission_schema_is_exact_and_preserves_cutoff_counters(self):
        record = diag.parse_line(WINDOW)
        self.assertEqual(record["admission"], "open")
        self.assertEqual(record["openSpansAtCutoff"], 1)
        self.assertEqual(record["closedRejectedRecords"], 0)
        self.assertEqual(record["lateSpans"], 0)
        self.assertEqual(record["lateAfterFinal"], "notTracked")
        closed = diag.parse_line(WINDOW.replace("final=false", "final=true")
                                 .replace("admission=open", "admission=closed")
                                 .replace("closedRejectedRecords=0", "closedRejectedRecords=2")
                                 .replace("lateSpans=0", "lateSpans=1"))
        self.assertEqual(closed["admission"], "closed")
        self.assertEqual(closed["closedRejectedRecords"], 2)
        self.assertEqual(closed["lateSpans"], 1)
        attacks = [WINDOW.replace("admission=open", "admission=" + value)
                   for value in ("Open", "PRIVATE", "0")]
        attacks += [WINDOW.replace("lateAfterFinal=notTracked", "lateAfterFinal=" + value)
                    for value in ("tracked", "unknown", "PRIVATE")]
        attacks += [WINDOW.replace("openSpansAtCutoff=1", "openSpansAtCutoff=-1"),
                    WINDOW.replace("closedRejectedRecords=0", "closedRejectedRecords=-1"),
                    WINDOW.replace("lateSpans=0", "lateSpans=-1")]
        for line in attacks:
            with self.assertRaises(diag.Rejected):
                diag.parse_line(line)

    def test_complete_p0_correlation_and_final_fixtures_round_trip_without_rejection(self):
        lines = [WINDOW, SPAN, FRAME, MAIN_MESSAGE, RUNTIME_SUPPORTED]
        lines += [PREFIX.format(kind=kind) + fields for kind, fields in {**CORRELATION, **P0_EXTRA}.items()]
        records, audit = diag.parse_lines(lines)
        self.assertEqual(0, audit["rejectedLines"])
        self.assertEqual(len(lines), len(records))
        self.assertEqual(-4, next(r for r in records if r["type"] == "frameListRow")["offset"])
        self.assertEqual(-1, next(r for r in records if r["type"] == "frameMainMessage")["cpuNs"])
        self.assertFalse(any("PRIVATE" in json.dumps(r) for r in records))
        self.assertEqual(0, diag.parse_line(PREFIX.format(kind="finalCompletion") + P0_EXTRA["finalCompletion"])["loggerFailed"])
        for kind, fields in CORRELATION.items():
            with self.assertRaises(diag.Rejected):
                diag.parse_line(PREFIX.format(kind=kind) + fields + " messageId=PRIVATE")

    def test_cross_window_unknown_loss_lookback_omission_and_tid_saturation_are_exported(self):
        correlation = PREFIX.format(kind="frameCorrelation") + CORRELATION["frameCorrelation"]
        record = diag.parse_line(correlation)
        self.assertEqual(9, record["lookbackMatched"])
        self.assertEqual(8, record["lookbackEmitted"])
        self.assertEqual(1, record["lookbackOmitted"])
        self.assertFalse(record["sourceWindowLoss"])
        self.assertFalse(record["sourceWindowUnknown"])
        for source in ("sourceWindowLoss", "sourceWindowUnknown"):
            delayed = diag.parse_line(correlation.replace(source + "=false", source + "=true")
                                      .replace("evidenceIncomplete=false", "evidenceIncomplete=true"))
            self.assertTrue(delayed[source]); self.assertTrue(delayed["evidenceIncomplete"])
            with self.assertRaises(diag.Rejected):
                diag.parse_line(correlation.replace(source + "=false", source + "=unknown"))
        window = diag.parse_line(WINDOW)
        self.assertEqual(128, window["threadIdCapacity"])
        self.assertEqual(1, window["threadIdSaturated"])
        for field in ("lookbackMatched", "lookbackEmitted", "lookbackOmitted"):
            with self.assertRaises(diag.Rejected):
                diag.parse_line(correlation.replace(field + "=" + str(record[field]), field + "=-1"))
        with self.assertRaises(diag.Rejected):
            diag.parse_line(WINDOW.replace("threadIdSaturated=1", "threadIdSaturated=-1"))

    def test_correlation_arithmetic_rejects_only_emitter_contract_contradictions(self):
        line = PREFIX.format(kind="frameCorrelation") + CORRELATION["frameCorrelation"]
        attacks = [
            line.replace("matched=1 emitted=1 omitted=0", "matched=1 emitted=2 omitted=0"),
            line.replace("matched=1 emitted=1 omitted=0", "matched=1 emitted=1 omitted=1"),
            line.replace("lookbackMatched=9", "lookbackMatched=8"),
            line.replace("mainSpanUnionNs=40", "mainSpanUnionNs=41"),
            line.replace("frameWallOutsideSpansNs=0", "frameWallOutsideSpansNs=1"),
            line.replace("matched=1 emitted=1 omitted=0", "matched=0 emitted=0 omitted=0"),
            line.replace("sourceWindowLoss=false", "sourceWindowLoss=true"),
            line.replace("sourceWindowUnknown=false", "sourceWindowUnknown=true"),
        ]
        for attack in attacks:
            with self.subTest(attack_index=attacks.index(attack)), self.assertRaises(diag.Rejected):
                diag.parse_line(attack)
        # The emitter computes union before limiting detail rows; output omission
        # alone does not imply evidenceIncomplete or invalidate the wall union.
        limited = line.replace("matched=1 emitted=1 omitted=0", "matched=2 emitted=1 omitted=1")
        self.assertFalse(diag.parse_line(limited)["evidenceIncomplete"])
        self.assertEqual(1, diag.parse_line(line)["lookbackOmitted"])
        # No count is compared to the number of supplied spanOverlap/lookback
        # lines: extracted v2 inputs are allowed to contain only a subset.
        records, audit = diag.parse_lines([limited])
        self.assertEqual(audit["rejectedLines"], 0)
        self.assertEqual(len(records), 1)
        for fields in ("sourceWindowLoss sourceWindowUnknown lookbackMatched lookbackEmitted lookbackOmitted",
                       "emitted omitted mainSpanUnionNs frameWallOutsideSpansNs"):
            old = re.sub(r" (?:" + "|".join(fields.split()) + r")=[^ ]+", "", line)
            self.assertEqual(diag.parse_line(old)["matched"], 1)
        records, audit = diag.parse_lines(attacks)
        self.assertFalse(records)
        self.assertEqual(audit["rejectedLines"], len(attacks))

    def test_correlation_emitter_fields_match_independent_full_golden(self):
        root = Path(__file__).resolve().parents[2]
        source = (root / "app/src/main/kotlin/io/github/mangi/eta/ui/components/StreamPerformanceDiagnostics.kt").read_text()
        end_markers = {"frameCorrelation": "correlation.overlaps.forEach", "spanOverlap": "correlation.preceding.forEach",
                       "frameLookback": "frame.listSnapshot?.let", "frameList": "sample.rows.forEach",
                       "frameListRow": "} ?: AppFileLogger", "frameMainMessage": "val correlation ="}
        common = {"id", "windowStartNs", "windowEndNs", "boundary", "final", "v", "type"}
        for kind, fixture in CORRELATION.items():
            template = source.split("v=2 type=" + kind + " ", 1)[1].split(end_markers[kind], 1)[0]
            emitted = common | set(re.findall(r"\b([A-Za-z][A-Za-z0-9]*)=", template))
            if kind in {"spanOverlap", "frameLookback"}:
                emitted |= set(re.findall(r"\b([A-Za-z][A-Za-z0-9]*)=", RENDER_FIELDS))
            self.assertEqual(emitted, set(diag.parse_line(PREFIX.format(kind=kind) + fixture)), kind)

    def test_info_level_overwritten_and_delta_loss_are_not_hidden(self):
        for name in ("traced_buf_bytes_overwritten", "ftrace_setup_errors", "ftrace_cpu_overrun_delta", "traced_buf_chunks_discarded", "traced_buf_trace_writer_packet_loss"):
            result = diag.parse_stats_csv("name,idx,value,severity,source\n" + name + ",0,3,info,trace\n")
            if "setup_errors" not in name:
                self.assertTrue(result["problemRows"], name)

    def test_retokens_id_no_reverse_mapping(self):
        records, audit = diag.parse_lines([SPAN, SPAN.replace("12ab34cd", "87654321")])
        output = json.dumps(diag.summarize(records, audit, 100, 300))
        self.assertNotIn("12ab34cd", output)
        self.assertNotIn("87654321", output)
        self.assertEqual([r["session"] for r in records], ["s1", "s2"])
        self.assertNotIn("id", records[0])

    def test_strict_privacy_rejection_never_echoes(self):
        private = "PRIVATE_SENTINEL"
        attacks = [
            SPAN + " text=" + private, SPAN + " url=https://example.invalid/" + private,
            SPAN + " apiKey=" + private, SPAN + " rawConversationId=" + private,
            SPAN.replace("stage=ui.flush", "stage=" + private),
            SPAN.replace("kind=delta.text", "kind=" + private),
            SPAN.replace("visibility=Selected", "visibility=" + private),
            SPAN.replace("runToken=3", "runToken=" + private),
            SPAN.replace("id=12ab34cd", "id=" + private),
            WINDOW.replace("versionName=1.2.3", "versionName=" + private),
            SPAN + " note=" + private, "logcat header " + SPAN,
            FRAME + " text=" + private, WINDOW + " revision=" + private,
            RUNTIME + " className=" + private,
            SPAN.replace("page=Chat", "page=" + private),
            FRAME.replace("pageEnd=Settings", "pageEnd=" + private),
            WINDOW.replace("boundary=admissionSnapshot", "boundary=" + private),
            RUNTIME.replace("art.gc.bytes-allocated", private),
        ]
        records, audit = diag.parse_lines(attacks)
        output = json.dumps(diag.summarize(records, audit, 100, 300))
        self.assertEqual(records, [])
        self.assertEqual(audit["rejectedLines"], len(attacks))
        self.assertNotIn(private, output)
        self.assertNotIn("https://", output)
        for line in attacks:
            with self.assertRaises(diag.Rejected) as ctx:
                diag.parse_line(line)
            self.assertNotIn(private, str(ctx.exception))

    def test_legacy_and_malformed_rejected(self):
        lines = [
            "StreamDiag id=12ab34cd stage=ui.flush n=1 avgUs=2",
            SPAN + " runToken=4", SPAN.replace("beginNs=120", "beginNs=-1"),
            SPAN.replace("endNs=280", "endNs=110"), SPAN.replace("main=true", "main=1"),
            SPAN.replace("thread=1", "thread=9223372036854775808"),
            SPAN.replace("thread=1", "thread=1\t"), SPAN + "\x1b[31m",
        ]
        records, audit = diag.parse_lines(lines)
        self.assertEqual(records, [])
        self.assertEqual(audit["rejectedLines"], len(lines))

    def test_record_cap_and_long_line(self):
        records, audit = diag.parse_lines([SPAN, FRAME, "StreamDiag " + "x" * diag.MAX_LINE], max_records=1)
        self.assertEqual(len(records), 1)
        self.assertEqual(audit["exporterTruncatedRecords"], 1)
        self.assertEqual(audit["rejectionReasons"], {"line_too_long": 1})

    def test_unsupported_runtime_not_fake_zero(self):
        record = diag.parse_line(RUNTIME)
        self.assertNotIn("cumulative", record)
        self.assertNotIn("delta", record)
        with self.assertRaises(diag.Rejected):
            diag.parse_line(RUNTIME + " cumulative=0")
        known = diag.parse_line(RUNTIME_SUPPORTED.replace("delta=unknown", "delta=2"))
        self.assertEqual(known["delta"], 2)

    def test_version_code_unknown_is_exact_and_numeric_versions_are_safe(self):
        self.assertIsNone(diag.parse_line(WINDOW.replace("versionCode=42", "versionCode=unknown"))["versionCode"])
        for version in ("unknown", "1.2", "12.345.6.7890"):
            self.assertEqual(diag.parse_line(WINDOW.replace("versionName=1.2.3", "versionName=" + version))["versionName"], version)
        invalid = [WINDOW.replace("versionCode=42", "versionCode=" + v)
                   for v in ("Unknown", "UNKNOWN", "-1", "42suffix")]
        invalid += [WINDOW.replace("versionName=1.2.3", "versionName=" + v)
                    for v in ("1", "1.2.3.4.5", "12345.1", "1.2-PRIVATE", "revision_PRIVATE", "１.２", "1..2")]
        for line in invalid:
            with self.subTest(line_index=invalid.index(line)), self.assertRaises(diag.Rejected):
                diag.parse_line(line)

    def test_fixed_enums_exact_case_and_no_numeric_pages(self):
        for page in diag.PAGES:
            record = diag.parse_line(SPAN.replace("page=Chat pageEnd=Chat", f"page={page} pageEnd={page}"))
            self.assertEqual(record["page"], page)
        for kind in diag.KINDS:
            self.assertEqual(diag.parse_line(SPAN.replace("kind=delta.text", "kind=" + kind))["kind"], kind)
        for stage in diag.STAGES:
            self.assertEqual(diag.parse_line(SPAN.replace("stage=ui.flush", "stage=" + stage))["stage"], stage)
        for counter in diag.RUNTIME_COUNTERS:
            self.assertEqual(diag.parse_line(RUNTIME.replace("art.gc.bytes-allocated", counter))["runtimeCounter"], counter)
        attacks = [
            SPAN.replace("page=Chat", "page=7"), FRAME.replace("pageEnd=Settings", "pageEnd=7"),
            SPAN.replace("page=Chat", "page=chat"), SPAN.replace("pageEnd=Chat", "pageEnd=PRIVATE"),
            SPAN.replace("kind=delta.text", "kind=Delta"), SPAN.replace("kind=delta.text", "kind=Unknown"),
            SPAN.replace("stage=ui.flush", "stage=unknown"), SPAN.replace("stage=ui.flush", "stage=render.PRIVATE"),
            SPAN.replace("stage=ui.flush", "stage=frame.page.PRIVATE"),
            RUNTIME.replace("art.gc.bytes-allocated", "gcCount"),
            WINDOW.replace("boundary=admissionSnapshot", "boundary=halfOpenCompletion"),
            WINDOW.replace("boundary=admissionSnapshot", "boundary=unknown"),
        ]
        for line in attacks:
            with self.assertRaises(diag.Rejected):
                diag.parse_line(line)

    def test_new_labels_and_canonical_unknowns_are_fixed_not_pattern_matched(self):
        self.assertEqual(len(RENDER_LABELS), 56)
        self.assertTrue(RENDER_LABELS <= diag.STAGES)
        for stage in sorted(RENDER_LABELS):
            with self.subTest(stage=stage):
                self.assertEqual(diag.parse_line(SPAN.replace("stage=ui.flush", "stage=" + stage))["stage"], stage)
        reviewed = {
            "ui.enqueue", "ipc.client.receive", "runtime.checkpoint.buffer.residency",
            "runtime.checkpoint.lockWait", "runtime.checkpoint.write", "settings.root.measure",
            "usage.load.dao.messages", "usage.ledger.serialize", "markdown.annotated.cell",
            "reveal.paths.nextGrapheme", "ui.event.hostedToolFinished", "frame.page.McpServerDetail",
            "render.unknown", "datastore.unknown", "runtime.checkpoint.unknown",
        }
        self.assertTrue(reviewed <= diag.STAGES)
        self.assertEqual(len(diag.PAGES), 42)
        self.assertEqual(len(diag.KINDS), 34)
        self.assertEqual(len(diag.RUNTIME_COUNTERS), 6)
        for stage in ("render.Unknown", "datastore.private", "ui.event.Delta", "runtime.checkpoint.PRIVATE"):
            with self.assertRaises(diag.Rejected):
                diag.parse_line(SPAN.replace("stage=ui.flush", "stage=" + stage))


class WindowTests(unittest.TestCase):
    def summary(self, lines, start=150, end=250):
        records, audit = diag.parse_lines(lines)
        return diag.summarize(records, audit, start, end)

    def test_inclusive_nesting_not_auto_added(self):
        child = SPAN.replace("span=1 parent=0", "span=2 parent=1").replace("stage=ui.flush", "stage=ui.messages.apply")
        child = child.replace("beginNs=120 endNs=280", "beginNs=160 endNs=190")
        result = self.summary([WINDOW, SPAN, child])
        group = result["groups"][0]
        stages = {r["stage"]: r for r in group["spanStageInclusive"]}
        self.assertEqual(stages["ui.flush"]["clippedInclusiveNs"], 100)
        self.assertEqual(stages["ui.messages.apply"]["clippedInclusiveNs"], 30)
        self.assertNotIn("totalBusinessNs", group)
        self.assertEqual(group["windows"][0]["counters"]["ringOverwritten"], 2)
        self.assertTrue(group["windows"][0]["partialRequestedOverlap"])

    def test_coverage_union_and_gaps(self):
        self.assertEqual(diag.union_ns([(0, 5), (3, 10), (10, 12), (20, 30)]), 22)
        partial = WINDOW.replace("windowEndNs=300", "windowEndNs=200")
        duplicate = partial
        result = self.summary([partial, duplicate])
        group = result["groups"][0]
        self.assertEqual(group["windowCoverageNs"], 50)
        self.assertEqual(group["windowUncoveredNs"], 50)
        # Duplicate input stays visible; don't claim deduped counter totals.
        self.assertEqual(len(group["windows"]), 2)

    def test_tokens_and_unknown_not_merged(self):
        other = SPAN.replace("runToken=3", "runToken=4").replace("page=Chat pageEnd=Chat", "page=Settings pageEnd=Browser")
        missing = SPAN.replace(" runToken=3", "").replace(" visibility=Selected", "")
        result = self.summary([SPAN, other, missing])
        layers = result["groups"][0]["spanStageInclusive"]
        self.assertEqual(len(layers), 3)
        self.assertEqual({r["runToken"] for r in layers}, {None, 3, 4})
        self.assertIsNone(next(r for r in layers if r["runToken"] is None)["visibility"])
        self.assertEqual(result["groups"][0]["windowUncoveredNs"], 100)

    def test_frames_deadlines_not_frametimeline(self):
        result = self.summary([FRAME])
        summary = result["groups"][0]["frames"]
        self.assertEqual(summary["frameMetricsDeadlineExceededComputed"], 1)
        self.assertEqual(summary["frameMetricsDeadlineMissReported"], 1)
        self.assertIsNone(summary["appFrameTimelineDeadlineMissed"])
        self.assertEqual(summary["metricsDroppedKnownRecordSum"], 2)
        missing = FRAME.replace(" deadlineNs=30", "").replace(" deadlineMiss=true", "").replace(" metricsDropped=2", "")
        summary = self.summary([missing])["groups"][0]["frames"]
        self.assertIsNone(summary["frameMetricsDeadlineExceededComputed"])
        self.assertIsNone(summary["metricsDroppedKnownRecordSum"])
        self.assertEqual(summary["missingTotalOrDeadline"], 1)

    def test_half_open_boundaries_and_explicit_window(self):
        self.assertTrue(diag.overlaps((150, 150), 150, 250))
        self.assertFalse(diag.overlaps((250, 250), 150, 250))
        self.assertFalse(diag.overlaps((100, 150), 150, 250))
        self.assertEqual(self.summary([SPAN], 300, 400)["selectedRecords"], [])
        for start, end in [(1, 1), (2, 1), (-1, 10)]:
            with self.assertRaises(diag.Rejected):
                self.summary([], start, end)

    def test_admission_snapshot_does_not_filter_span_timestamps_by_window(self):
        crossing = SPAN.replace("beginNs=120 endNs=280", "beginNs=50 endNs=350")
        records, audit = diag.parse_lines([WINDOW, crossing])
        self.assertEqual(audit["rejectedLines"], 0)
        self.assertEqual(records[1]["beginNs"], 50)
        self.assertEqual(records[1]["endNs"], 350)
        group = diag.summarize(records, audit, 150, 250)["groups"][0]
        self.assertEqual(group["windows"][0]["boundary"], "admissionSnapshot")
        self.assertEqual(group["spanStageInclusive"][0]["clippedInclusiveNs"], 100)
        # A selected span remains visible even if its admission window does not
        # overlap this selection. It is not completion-window membership.
        result = diag.summarize(records, audit, 300, 400)
        self.assertEqual([r["type"] for r in result["selectedRecords"]], ["span"])
        self.assertEqual(result["groups"][0]["windowCoverageNs"], 0)

    def test_exact_clock_anchors_and_loss_missing_is_null(self):
        result = self.summary([WINDOW])
        group = result["groups"][0]
        self.assertEqual(group["clock"]["anchors"][0], {"anchorNanoNs": 300, "uptimeMs": 1, "elapsedRealtimeNs": 900})
        self.assertEqual(group["windows"][0]["counters"]["spanOutputTruncated"], 5)
        self.assertEqual(result["traceHealth"]["status"], "missing_stats")
        minimal = PREFIX.format(kind="window").rstrip()
        counters = self.summary([minimal])["groups"][0]["windows"][0]["counters"]
        self.assertTrue(all(value is None for value in counters.values()))


class WhereTimeTests(unittest.TestCase):
    def report(self, lines, start=100, end=300, tables=None):
        records, audit = diag.parse_lines(lines)
        self.assertEqual(audit["rejectedLines"], 0)
        return diag.where_time(records, audit, start, end, tables=tables)["whereTime"]

    def test_protected_window_loss_is_not_hidden_by_six_legacy_zeros(self):
        # Legal split #1: callback protection was capped even though all six
        # legacy loss counters are zero. A correlation reports no retained match.
        window = ZERO_WINDOW.replace("protectedBudgetDropped=0", "protectedBudgetDropped=1")
        correlation = PREFIX.format(kind="frameCorrelation") + ZERO_CORRELATION.replace(
            "evidenceIncomplete=false", "evidenceIncomplete=true")
        def milliseconds(line):
            return re.sub(r"\b([A-Za-z][A-Za-z0-9]*Ns)=([0-9]+)",
                          lambda m: m[1] + "=" + str(int(m[2]) * 1000000), line)
        entry = self.report([milliseconds(window), milliseconds(FRAME), milliseconds(correlation)],
                            100000000, 300000000)["frames"][0]
        integrity = entry["recordIntegrity"]
        self.assertEqual(integrity["status"], "records_lost_or_truncated")
        self.assertEqual(integrity["byEvidence"]["spans"]["status"], "records_lost_or_truncated")
        self.assertEqual(integrity["counters"]["protectedBudgetDropped"], 1)
        for key in ("ringOverwritten", "spanOutputTruncated", "slowBudgetDropped",
                    "frameBudgetDropped", "mainRingOverwritten", "mainOutputTruncated"):
            self.assertEqual(integrity["counters"][key], 0)
        self.assertTrue(integrity["evidenceIncomplete"])
        self.assertEqual(entry["frameCorrelation"]["frameWallOutsideSpansNs"], 40000000)
        self.assertEqual(entry["instrumentation"]["gapMeaning"], "unattributed_or_record_loss")

    def test_source_window_loss_survives_zero_current_window_counters(self):
        # Legal split #2: the callback inspected a lossy preceding generation,
        # while the current admission snapshot reports no local record drops.
        correlation = PREFIX.format(kind="frameCorrelation") + ZERO_CORRELATION.replace(
            "sourceWindowLoss=false", "sourceWindowLoss=true").replace(
            "evidenceIncomplete=false", "evidenceIncomplete=true")
        def milliseconds(line):
            return re.sub(r"\b([A-Za-z][A-Za-z0-9]*Ns)=([0-9]+)",
                          lambda m: m[1] + "=" + str(int(m[2]) * 1000000), line)
        entry = self.report([milliseconds(ZERO_WINDOW), milliseconds(FRAME), milliseconds(correlation)],
                            100000000, 300000000)["frames"][0]
        integrity = entry["recordIntegrity"]
        self.assertTrue(all(value == 0 for value in integrity["counters"].values()))
        self.assertEqual(integrity["status"], "records_lost_or_truncated")
        self.assertTrue(integrity["sourceWindowLoss"])
        self.assertFalse(integrity["sourceWindowUnknown"])
        self.assertTrue(integrity["evidenceIncomplete"])
        self.assertEqual(entry["frameCorrelation"]["status"], "matched")
        self.assertEqual(entry["instrumentation"]["gapMeaning"], "unattributed_or_record_loss")

    def test_known_losses_are_classified_without_turning_every_gap_into_span_loss(self):
        domains = {
            "spans": ("ringOverwritten", "slowBudgetDropped", "spanOutputTruncated",
                      "protectedBudgetDropped", "frameCaptureTruncated"),
            "frames": ("frameBudgetDropped", "frameBudgetEvicted", "callbackRejected"),
            "mainDispatches": ("mainRingOverwritten", "mainOutputTruncated"),
            "listSamples": ("listSampleOverwritten",), "notes": ("noteBudgetDropped",),
            "admission": ("closedRejectedRecords", "openIdDropped"),
        }
        outside = SPAN.replace("beginNs=120 endNs=280", "beginNs=110 endNs=130")
        for domain, fields in domains.items():
            for field in fields:
                with self.subTest(field=field):
                    window = ZERO_WINDOW.replace(field + "=0", field + "=1")
                    correlation = ZERO_CORRELATION.replace("evidenceIncomplete=false", "evidenceIncomplete=true") if domain == "spans" else ZERO_CORRELATION
                    entry = self.report([window, FRAME, outside, PREFIX.format(kind="frameCorrelation") + correlation])["frames"][0]
                    self.assertEqual(entry["recordIntegrity"]["status"], "records_lost_or_truncated")
                    self.assertEqual(entry["recordIntegrity"]["byEvidence"][domain]["status"], "records_lost_or_truncated")
                    self.assertEqual(entry["instrumentation"]["gapMeaning"], "unattributed_or_record_loss"
                                     if domain == "spans" else "unattributed_not_proven_idle")
        for field, domain, status in (("tokenSaturated", "attribution", "attribution_incomplete"),
                                      ("rowTokenSaturated", "attribution", "attribution_incomplete"),
                                      ("eventLinksOverwritten", "attribution", "attribution_incomplete"),
                                      ("threadIdSaturated", "threadMapping", "mapping_incomplete")):
            entry = self.report([ZERO_WINDOW.replace(field + "=0", field + "=1"), FRAME, outside])["frames"][0]
            self.assertEqual(entry["recordIntegrity"]["byEvidence"][domain]["status"], status)
            self.assertFalse(entry["recordIntegrity"]["spanEvidenceIncomplete"])
            self.assertEqual(entry["instrumentation"]["gapMeaning"], "unattributed_not_proven_idle")
            self.assertEqual(entry["recordIntegrity"]["status"], "no_loss_reported_not_complete_coverage"
                             if domain == "threadMapping" else "evidence_incomplete_not_record_loss")
        informational = ZERO_WINDOW.replace("previousWindowSpans=0", "previousWindowSpans=1").replace("lateSpans=0", "lateSpans=1")
        entry = self.report([informational, FRAME])["frames"][0]
        self.assertEqual(entry["recordIntegrity"]["status"], "no_loss_reported_not_complete_coverage")

    def test_source_unknown_and_pending_evidence_are_not_reported_as_zero_loss(self):
        for window, fields in ((ZERO_WINDOW, ZERO_CORRELATION.replace("sourceWindowUnknown=false", "sourceWindowUnknown=true")),
                               (ZERO_WINDOW.replace("openSpansAtCutoff=0", "openSpansAtCutoff=1").replace("partial=false", "partial=true"), ZERO_CORRELATION)):
            correlation = PREFIX.format(kind="frameCorrelation") + fields.replace("evidenceIncomplete=false", "evidenceIncomplete=true")
            entry = self.report([window, FRAME, correlation])["frames"][0]
            self.assertEqual(entry["recordIntegrity"]["status"], "integrity_unavailable")
            self.assertTrue(entry["recordIntegrity"]["evidenceIncomplete"])
            self.assertTrue(entry["recordIntegrity"]["spanEvidenceIncomplete"])
            self.assertFalse(entry["recordIntegrity"]["sourceWindowLoss"])
            self.assertEqual(entry["instrumentation"]["gapMeaning"], "unattributed_or_incomplete_evidence")
        entry = self.report([ZERO_WINDOW, FRAME, PREFIX.format(kind="frameCorrelation") +
                             ZERO_CORRELATION.replace("sourceWindowUnknown=false", "sourceWindowUnknown=true").replace(
                                 "evidenceIncomplete=false", "evidenceIncomplete=true")])["frames"][0]
        self.assertTrue(entry["recordIntegrity"]["sourceWindowUnknown"])

    def test_abnormal_frame_index_is_not_joined_across_windows_sessions_or_final_prefixes(self):
        clean = PREFIX.format(kind="frameCorrelation") + ZERO_CORRELATION
        lossy = clean.replace("sourceWindowLoss=false", "sourceWindowLoss=true").replace("evidenceIncomplete=false", "evidenceIncomplete=true")
        for foreign in (lossy.replace("windowStartNs=100 windowEndNs=300", "windowStartNs=300 windowEndNs=500"),
                        lossy.replace("id=12ab34cd", "id=87654321"), lossy.replace("final=false", "final=true")):
            entry = self.report([ZERO_WINDOW, FRAME, foreign])["frames"][0]
            self.assertEqual(entry["recordIntegrity"]["status"], "no_loss_reported_not_complete_coverage")
            self.assertIsNone(entry["recordIntegrity"]["sourceWindowLoss"])
            self.assertEqual(entry["frameCorrelation"]["status"], "unavailable")
        def later(line):
            return line.replace("windowStartNs=100 windowEndNs=300", "windowStartNs=300 windowEndNs=500").replace(
                "intendedVsyncNs=150", "intendedVsyncNs=350").replace("vsyncNs=155", "vsyncNs=355")
        entries = self.report([ZERO_WINDOW, FRAME, clean, later(ZERO_WINDOW), later(FRAME), later(lossy)], 100, 500)["frames"]
        self.assertEqual([r["abnormalFrame"] for r in entries], [0, 0])
        self.assertEqual([r["recordIntegrity"]["status"] for r in entries],
                         ["no_loss_reported_not_complete_coverage", "records_lost_or_truncated"])
        self.assertEqual([r["windowStartNs"] for r in entries], [100, 300])

    def test_correlation_timing_mismatch_missing_fields_and_conflicts_are_conservative_unknown(self):
        clean = PREFIX.format(kind="frameCorrelation") + ZERO_CORRELATION
        lossy = clean.replace("sourceWindowLoss=false", "sourceWindowLoss=true").replace("evidenceIncomplete=false", "evidenceIncomplete=true")
        cases = (([lossy.replace("intendedVsyncNs=150", "intendedVsyncNs=151")], "timing_mismatch"),
                 ([lossy.replace("frameTotalNs=40", "frameTotalNs=41").replace("frameWallOutsideSpansNs=40", "frameWallOutsideSpansNs=41")], "timing_mismatch"),
                 ([lossy.replace(" intendedVsyncNs=150", "")], "identity_or_timing_unavailable"),
                 ([clean, lossy], "ambiguous"))
        for correlations, status in cases:
            entry = self.report([ZERO_WINDOW, FRAME] + correlations)["frames"][0]
            self.assertEqual(entry["frameCorrelation"]["status"], status)
            self.assertEqual(entry["recordIntegrity"]["status"], "integrity_unavailable")
            self.assertIsNone(entry["recordIntegrity"]["sourceWindowLoss"])
            self.assertEqual(entry["instrumentation"]["gapMeaning"], "unattributed_or_incomplete_evidence")
        entry = self.report([ZERO_WINDOW, FRAME, clean, clean])["frames"][0]
        self.assertEqual(entry["frameCorrelation"]["status"], "matched")
        self.assertEqual(entry["recordIntegrity"]["status"], "no_loss_reported_not_complete_coverage")
        old_correlation = clean.replace(" sourceWindowLoss=false sourceWindowUnknown=false", "")
        entry = self.report([ZERO_WINDOW, FRAME, old_correlation])["frames"][0]
        self.assertIsNone(entry["recordIntegrity"]["sourceWindowUnknown"])
        self.assertEqual(entry["recordIntegrity"]["status"], "integrity_unavailable")
        old_window = re.sub(r" (?:protectedBudgetDropped|frameCaptureTruncated|threadIdSaturated)=[0-9]+", "", ZERO_WINDOW)
        entry = self.report([old_window, FRAME])["frames"][0]
        self.assertIsNone(entry["recordIntegrity"]["counters"]["protectedBudgetDropped"])
        self.assertEqual(entry["recordIntegrity"]["status"], "integrity_unavailable")

    def test_delayed_frame_uses_its_admission_prefix_not_only_wall_overlapping_windows(self):
        delayed_window = ZERO_WINDOW.replace("windowStartNs=100 windowEndNs=300", "windowStartNs=300 windowEndNs=500").replace(
            "protectedBudgetDropped=0", "protectedBudgetDropped=1")
        delayed_frame = FRAME.replace("windowStartNs=100 windowEndNs=300", "windowStartNs=300 windowEndNs=500")
        entry = self.report([delayed_window, delayed_frame], 100, 200)["frames"][0]
        self.assertEqual(entry["recordIntegrity"]["status"], "records_lost_or_truncated")
        self.assertEqual(entry["recordIntegrity"]["counters"]["protectedBudgetDropped"], 1)
        self.assertEqual(entry["windowStartNs"], 300)

    def test_nested_span_union_and_independent_metric_axis(self):
        parent = SPAN.replace("beginNs=120 endNs=280", "beginNs=155 endNs=175")
        child = parent.replace("span=1 parent=0", "span=2 parent=1").replace("stage=ui.flush", "stage=reveal.step")
        child = child.replace("beginNs=155 endNs=175", "beginNs=160 endNs=170")
        entry = self.report([WINDOW, FRAME, parent, child, MAIN_MESSAGE])["frames"][0]
        self.assertEqual(entry["instrumentation"]["mainSpanUnionNs"], 20)
        self.assertEqual(entry["instrumentation"]["revealSpanUnionNs"], 10)
        self.assertEqual(entry["instrumentation"]["observedOutsideRevealNs"], 10)
        self.assertEqual(entry["instrumentation"]["noRetainedSpanCoverageNs"], 20)
        self.assertEqual(entry["metrics"]["unaccountedNs"], 4)
        self.assertEqual(entry["metrics"]["overlapNs"], 0)
        self.assertEqual(entry["metrics"]["vsyncLateNsOverlappingUnknown"], 5)
        self.assertEqual(entry["recordIntegrity"]["status"], "records_lost_or_truncated")
        self.assertEqual(entry["instrumentation"]["gapMeaning"], "unattributed_or_record_loss")
        message = entry["slowMainDispatches"]["fullDispatchAccountingNotProrated"][0]
        self.assertEqual(message["coveredNs"], 30)  # Not multiplied by overlap/full duration.
        self.assertEqual(message["nonRevealNs"], 70)
        self.assertEqual(entry["slowMainDispatches"]["overlappingEnvelopeUnionNs"], 40)

    def test_no_observations_not_observed_zero(self):
        absent = self.report([FRAME])["frames"][0]
        self.assertEqual(absent["instrumentation"]["status"], "unavailable")
        self.assertIsNone(absent["instrumentation"]["mainSpanUnionNs"])
        self.assertEqual(absent["recordIntegrity"]["status"], "integrity_unavailable")
        self.assertEqual(absent["slowMainDispatches"]["status"], "unavailable")
        outside = SPAN.replace("beginNs=120 endNs=280", "beginNs=110 endNs=130")
        observed_zero = self.report([FRAME, outside])["frames"][0]
        self.assertEqual(observed_zero["instrumentation"]["status"], "observed")
        self.assertEqual(observed_zero["instrumentation"]["mainSpanUnionNs"], 0)
        self.assertEqual(observed_zero["instrumentation"]["noRetainedSpanCoverageNs"], 40)

    def test_duplicate_envelopes_and_spans_use_union_not_sum(self):
        report = self.report([FRAME, SPAN, SPAN, MAIN_MESSAGE, MAIN_MESSAGE])
        entry = report["frames"][0]
        self.assertEqual(entry["instrumentation"]["mainSpanUnionNs"], 40)
        self.assertEqual(entry["slowMainDispatches"]["overlappingEnvelopeUnionNs"], 40)
        self.assertEqual(len(entry["slowMainDispatches"]["fullDispatchAccountingNotProrated"]), 2)

    def test_missing_component_not_fake_residual_zero(self):
        old = FRAME.replace(" unaccountedNs=4 overlapNs=0 vsyncLateNs=5 accounting=frameMetricsResidualNotAdditive", "")
        entry = self.report([old])["frames"][0]
        self.assertEqual(entry["metrics"]["residualSource"], "derived")
        self.assertEqual(entry["metrics"]["unaccountedNs"], 4)
        entry = self.report([old.replace(" unknownNs=1", "")])["frames"][0]
        self.assertEqual(entry["metrics"]["residualSource"], "unavailable")
        self.assertIsNone(entry["metrics"]["unaccountedNs"])
        self.assertIsNone(entry["metrics"]["components"]["unknownNs"])

    def test_missing_table_unavailable_present_empty_is_zero(self):
        tables = diag.parse_table_status_json(json.dumps({"schema": "eta.streamdiag.tables.v2", "tables": {
            "thread_state": {"available": False, "rows": None},
            "actual_frame_timeline_slice": {"available": True, "rows": 0},
            "slice": {"available": True, "rows": None},
        }}))
        status = self.report([FRAME], tables=tables)["traceTables"]
        self.assertEqual(status["thread_state"], {"status": "unavailable", "rows": None})
        self.assertEqual(status["actual_frame_timeline_slice"], {"status": "observed_zero", "rows": 0})
        self.assertEqual(status["cpu_profile_stack_sample"], {"status": "unavailable", "rows": None})
        self.assertEqual(status["slice"], {"status": "available_count_unknown", "rows": None})
        self.assertEqual(self.report([FRAME], tables=tables)["frameTimeline"]["status"], "unavailable")

    def test_strict_table_status_and_main_frame_fields_never_echo_private_text(self):
        attacks = [
            '{"schema":"eta.streamdiag.tables.v2","tables":{"PRIVATE_SENTINEL":{"available":false,"rows":null}}}',
            '{"schema":"eta.streamdiag.tables.v2","tables":{"slice":{"available":false,"rows":0}}}',
            '{"schema":"eta.streamdiag.tables.v2","tables":{"slice":{"available":true,"rows":true}}}',
            '{"schema":"eta.streamdiag.tables.v2","tables":{},"reason":"PRIVATE_SENTINEL"}',
            '{"schema":"eta.streamdiag.tables.v2","tables":{},"tables":{}}',
        ]
        for text in attacks:
            with self.assertRaises(diag.Rejected) as ctx:
                diag.parse_table_status_json(text)
            self.assertNotIn("PRIVATE_SENTINEL", str(ctx.exception))
        for line in [MAIN_MESSAGE + " name=PRIVATE_SENTINEL",
                     MAIN_MESSAGE.replace("coveredNs=30", "coveredNs=90"),
                     MAIN_MESSAGE.replace("uninstrumentedNs=50", "uninstrumentedNs=0"),
                     FRAME.replace("unaccountedNs=4", "unaccountedNs=0"),
                     FRAME.replace("accounting=frameMetricsResidualNotAdditive", "accounting=dispatchSubsetsNotAdditive")]:
            with self.assertRaises(diag.Rejected):
                diag.parse_line(line)

    def test_where_time_cli_preserves_parser_failure_exit(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "input.txt"
            output = Path(directory) / "output.json"
            source.write_text(FRAME + "\n" + MAIN_MESSAGE + "\n" + SPAN + " private=PRIVATE_SENTINEL\n")
            self.assertEqual(diag.main(["where-time", str(source), "--start-ns", "100", "--end-ns", "300",
                                        "--output", str(output)]), 2)
            text = output.read_text()
            self.assertNotIn("PRIVATE_SENTINEL", text)
            parsed = json.loads(text)
            self.assertEqual(parsed["whereTime"]["traceTables"]["sched"]["status"], "unavailable")
            self.assertEqual(parsed["parser"]["rejectedLines"], 1)


class IntegrityAndCLITests(unittest.TestCase):
    def test_negative_timestamp_error_never_hidden(self):
        health = diag.parse_stats_csv(STATS)
        self.assertEqual(health["status"], "compromised")
        self.assertEqual(health["problemRows"][0]["name"], "trace_sorter_negative_timestamp_dropped")
        self.assertEqual(health["problemRows"][0]["value"], 1)
        self.assertEqual(len(health["rows"]), 4)
        self.assertEqual(health["rows"][1]["value"], 0)
        self.assertEqual(health["rows"][2]["idx"], 0)
        self.assertEqual(health["rows"][3]["severity"], "warning")

    def test_empty_stats_not_zero_loss(self):
        health = diag.parse_stats_csv("name,idx,value,severity,source\n")
        self.assertEqual(health["status"], "missing_stats")
        zero = diag.parse_stats_csv("name,idx,value,severity,source\nparse_warning,,0,warning,trace\n")
        self.assertIn("not_loss_free", zero["status"])

    def test_csv_privacy_and_schema(self):
        for text in [
            "text,value\nPRIVATE_SENTINEL,1\n",
            "name,idx,value,severity,source\nPRIVATE_SENTINEL,,1,error,trace\n",
            "name,idx,value,severity,source\nvalid_name,,1,error,trace,PRIVATE_SENTINEL\n",
        ]:
            with self.assertRaises(diag.Rejected) as ctx:
                diag.parse_stats_csv(text)
            self.assertNotIn("PRIVATE_SENTINEL", str(ctx.exception))

    def test_sql_numeric_render_all_templates(self):
        for name in ("window-analysis", "cpu-samples", "heap-samples"):
            template = Path(diag.__file__).parent / "sql" / (name + ".sql")
            rendered = diag.render_sql(template.read_text(encoding="utf-8"), 100, 200, 42)
            self.assertNotIn("__START_NS__", rendered)
            self.assertNotIn("__END_NS__", rendered)
            self.assertNotIn("__UPID__", rendered)
        with self.assertRaises(diag.Rejected):
            diag.render_sql("__START_NS__ __END_NS__ __UPID__", 10, 9, 42)

    def test_cli_safe_output_fails_on_rejected_input(self):
        with tempfile.TemporaryDirectory() as root:
            source, target = Path(root) / "fake.txt", Path(root) / "summary.json"
            source.write_text(SPAN + "\n" + SPAN + " note=PRIVATE_SENTINEL\n", encoding="utf-8")
            result = diag.main(["summary", str(source), "--start-ns", "100", "--end-ns", "300", "--output", str(target)])
            self.assertEqual(result, 2)
            output = target.read_text(encoding="utf-8")
            self.assertNotIn("PRIVATE_SENTINEL", output)
            self.assertNotIn("12ab34cd", output)
            self.assertEqual(json.loads(output)["parser"]["rejectedLines"], 1)

    def test_cli_refuses_existing_output_input_and_symlinks_without_modifying_evidence(self):
        with tempfile.TemporaryDirectory() as root:
            source, target, link = (Path(root) / name for name in ("input.txt", "report.json", "alias"))
            raw = SPAN + "\n"
            source.write_text(raw, encoding="utf-8")
            target.write_text("original report", encoding="utf-8")
            link.symlink_to(source)
            dangling = Path(root) / "dangling"
            dangling.symlink_to(Path(root) / "missing")
            for output in (source, target, link, dangling):
                with contextlib.redirect_stderr(io.StringIO()):
                    code = diag.main(["summary", str(source), "--start-ns", "100", "--end-ns", "300", "--output", str(output)])
                self.assertEqual(code, 2)
                self.assertEqual(source.read_text(encoding="utf-8"), raw)
                self.assertEqual(target.read_text(encoding="utf-8"), "original report")
                self.assertFalse((Path(root) / "missing").exists())

    def test_cli_explicit_window_required(self):
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr), self.assertRaises(SystemExit) as ctx:
            diag.main(["summary", "fake.txt"])
        self.assertEqual(ctx.exception.code, 2)


if __name__ == "__main__":
    unittest.main()
