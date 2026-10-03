"""Source contract for the one-time “每次询问 → 前台执行” agent task preference migration.

Guards wiring only; behaviour is covered by the Robolectric/JVM tests. No Android SDK needed.

Confirmed local facts this pins down:
- ``AgentTaskSurface.stored()`` already defaults a missing key (and unreadable values) to
  FOREGROUND, so the only value worth rewriting is a stored, legal ``"ask"``.
- The migration is a one-shot keyed by an independent, fixed marker: never a forced write on
  every launch, never a version-code reset, never a permanent getter clamp.
- Startup has two preference boundaries that can rewrite the store: ``Prefs.initLocal``
  (before any read) and ``Prefs.restoreAgentPreferences`` (full clear + rewrite), so both
  re-apply the migration. A backup exported after the migration carries the marker, so
  restoring it keeps the user's choice; a legacy backup without the marker migrates once.
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[3]
MAIN = ROOT / "src/main/kotlin/io/github/mangi/eta"
MARKER_LITERAL = "agent_task_surface_ask_migrated"


def read(relative):
    return (MAIN / relative).read_text(encoding="utf-8")


def function_body(source, name):
    match = re.search(r"fun " + re.escape(name) + r"\(", source)
    if match is None:
        raise AssertionError(f"missing fun {name}")
    header_end = source.index(")", match.end())
    rest = source[header_end + 1:]
    # Expression-bodied functions (`fun x(): T = ...`) end at the next blank line.
    if re.match(r"\s*(:[^={]*)?=", rest):
        end = rest.find("\n\n")
        return rest if end < 0 else rest[:end]
    start = source.index("{", match.end())
    depth = 0
    for index in range(start, len(source)):
        if source[index] == "{":
            depth += 1
        elif source[index] == "}":
            depth -= 1
            if depth == 0:
                return source[start:index + 1]
    raise AssertionError(f"unclosed fun {name}")


class AgentTaskPreferenceMigrationContractTest(unittest.TestCase):
    def setUp(self):
        self.prefs = read("config/Prefs.kt")
        self.surface = read("agent/device/AgentTaskSurface.kt")
        self.migration = function_body(self.surface, "migrateAskToForegroundOnce")

    def test_marker_is_one_fixed_literal_not_a_version(self):
        self.assertIn(f'const val ASK_MIGRATION_KEY = "{MARKER_LITERAL}"', self.surface)
        for source in (self.surface, self.prefs):
            self.assertNotIn("BuildConfig", source)
            self.assertNotIn("VERSION_CODE", source)
            self.assertNotIn("versionCode", source)

    def test_reads_the_raw_value_from_the_same_local_store(self):
        # 必须直接读同一份 local SharedPreferences，不走跨后端封装，也不读远端。
        self.assertIn("prefs.getString(PREF_KEY, null)", self.migration)
        self.assertNotIn("Prefs.getString", self.migration)

    def test_only_a_stored_legal_ask_is_rewritten_to_foreground(self):
        self.assertIn("stored == AgentTaskSurfaceMode.ASK.wire", self.migration)
        self.assertIn("putString(PREF_KEY, AgentTaskSurfaceMode.FOREGROUND.wire)", self.migration)
        # 后台不是本迁移的目标，迁移里不能出现改写后台的写法。
        self.assertNotIn("BACKGROUND", self.migration)

    def test_marker_is_written_for_every_outcome_not_only_for_ask(self):
        # 缺键、前台、后台也要记“已检查”，否则用户以后选 ASK 才会被迁移。
        marker_at = self.migration.index("putBoolean(ASK_MIGRATION_KEY, true)")
        rewrite_at = self.migration.index("putString(PREF_KEY, AgentTaskSurfaceMode.FOREGROUND.wire)")
        self.assertLess(marker_at, rewrite_at)

    def test_unreadable_value_bails_out_before_marking_done(self):
        read_at = self.migration.index("prefs.getString(PREF_KEY, null)")
        bail_at = self.migration.index("return false", read_at)
        write_at = self.migration.index("prefs.edit()")
        self.assertLess(read_at, bail_at)
        self.assertLess(bail_at, write_at)

    def test_marker_and_value_share_one_atomic_editor(self):
        self.assertEqual(1, self.migration.count("prefs.edit()"))
        self.assertEqual(1, self.migration.count("commit()"))
        # 值必须写在同一个 editor 上，而不是另开一次 edit/apply。
        self.assertIn("editor.putString(PREF_KEY, AgentTaskSurfaceMode.FOREGROUND.wire)", self.migration)

    def test_commit_result_is_reported_not_ignored(self):
        self.assertIn("return editor.commit()", self.migration)

    def test_migration_is_marker_guarded_so_it_never_reapplies_every_launch(self):
        guard_at = self.migration.index("getBoolean(ASK_MIGRATION_KEY, false)")
        write_at = self.migration.index("prefs.edit()")
        self.assertLess(guard_at, write_at)
        self.assertIn("return", self.migration[:write_at])

    def test_init_local_runs_migration_before_any_read(self):
        body = function_body(self.prefs, "initLocal")
        self.assertIn("AgentTaskSurface.migrateAskToForegroundOnce()", body)

    def test_restore_reapplies_migration_after_commit(self):
        # 无标记旧备份：落盘后补做一次迁移；带标记备份：标记已在，迁移直接返回。
        body = function_body(self.prefs, "restoreAgentPreferences")
        call_at = body.index("AgentTaskSurface.migrateAskToForegroundOnce()")
        self.assertLess(body.index("commit()"), call_at)

    def test_getter_ui_and_runtime_paths_are_untouched(self):
        self.assertNotIn("migrateAskToForegroundOnce", function_body(self.surface, "stored"))
        self.assertNotIn("migrateAskToForegroundOnce", function_body(self.surface, "effective"))


if __name__ == "__main__":
    unittest.main()
