"""Emitter-schema golden lines with synthetic numbers, never real capture data.

Static spellings come from reviewed f9f5713b core output templates and
FramePageTimeline; 944b31c9 supplies the 56 render/data/recorder labels. These are
schema compatibility tests, not device capture or clock-alignment evidence.
"""
import contextlib
import importlib.util
import io
import json
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("eta_diag_analyze", Path(__file__).with_name("analyze.py"))
diag = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(diag)

PREFIX = "StreamDiag id=12ab34cd windowStartNs=100 windowEndNs=300 final=false v=2 type={kind} "
WINDOW = PREFIX.format(kind="window") + (
    "anchorNanoNs=300 uptimeMs=1 elapsedRealtimeNs=900 package=io.github.mangi.eta "
    "versionCode=42 versionName=1.2.3 buildType=unknown boundary=admissionSnapshot duration=inclusive "
    "heap=proxyNotAllocationStack gcTime=runtimeCounterNotPause spanCapacity=2048 "
    "slowBudget=256 frameBudget=120 ringOverwritten=2 slowBudgetDropped=3 "
    "frameBudgetDropped=4 spanOutputTruncated=5 tokenSaturated=6 "
    "eventLinksOverwritten=7 mainRingOverwritten=8 mainOutputTruncated=9 noteBudgetDropped=10 "
    "admission=open openSpansAtCutoff=1 closedRejectedRecords=0 lateSpans=0 "
    "postCloseObservation=notTracked"
)
SPAN = PREFIX.format(kind="span") + (
    "span=1 parent=0 stage=ui.flush beginNs=120 endNs=280 thread=1 main=true "
    "duration=inclusive value=5 runToken=3 conversationToken=7 visibility=Selected "
    "kind=delta.text replay=false eventSeq=4 sourceSpan=0 page=Chat pageEnd=Chat"
)
FRAME = PREFIX.format(kind="frame") + (
    "abnormalFrame=0 page=Chat pageEnd=Settings pageChanged=true pageSource=route "
    "intendedVsyncNs=150 vsyncNs=155 totalNs=40 deadlineNs=30 deadlineMiss=true "
    "metricsDropped=2 unknownNs=1 inputNs=2 animationNs=3 layoutNs=4 drawNs=5 "
    "syncNs=6 commandNs=7 swapNs=8 gpuNs=9"
)
RUNTIME = PREFIX.format(kind="runtime") + (
    "runtimeCounter=art.gc.bytes-allocated supported=false"
)
RUNTIME_SUPPORTED = PREFIX.format(kind="runtime") + (
    "runtimeCounter=art.gc.bytes-freed supported=true cumulative=10 delta=unknown"
)
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

    def test_final_admission_schema_is_exact_and_preserves_cutoff_counters(self):
        record = diag.parse_line(WINDOW)
        self.assertEqual(record["admission"], "open")
        self.assertEqual(record["openSpansAtCutoff"], 1)
        self.assertEqual(record["closedRejectedRecords"], 0)
        self.assertEqual(record["lateSpans"], 0)
        self.assertEqual(record["postCloseObservation"], "notTracked")
        closed = diag.parse_line(WINDOW.replace("final=false", "final=true")
                                 .replace("admission=open", "admission=closed")
                                 .replace("closedRejectedRecords=0", "closedRejectedRecords=2")
                                 .replace("lateSpans=0", "lateSpans=1"))
        self.assertEqual(closed["admission"], "closed")
        self.assertEqual(closed["closedRejectedRecords"], 2)
        self.assertEqual(closed["lateSpans"], 1)
        attacks = [WINDOW.replace("admission=open", "admission=" + value)
                   for value in ("Open", "PRIVATE", "0")]
        attacks += [WINDOW.replace("postCloseObservation=notTracked", "postCloseObservation=" + value)
                    for value in ("tracked", "unknown", "PRIVATE")]
        attacks += [WINDOW.replace("openSpansAtCutoff=1", "openSpansAtCutoff=-1"),
                    WINDOW.replace("closedRejectedRecords=0", "closedRejectedRecords=-1"),
                    WINDOW.replace("lateSpans=0", "lateSpans=-1")]
        for line in attacks:
            with self.assertRaises(diag.Rejected):
                diag.parse_line(line)

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

    def test_cli_explicit_window_required(self):
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr), self.assertRaises(SystemExit) as ctx:
            diag.main(["summary", "fake.txt"])
        self.assertEqual(ctx.exception.code, 2)


if __name__ == "__main__":
    unittest.main()
