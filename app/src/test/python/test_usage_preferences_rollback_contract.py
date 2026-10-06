"""Source/CI wiring guards. Runtime/durability behavior is exercised by the Kotlin tests."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[4]
MAIN = ROOT / "app/src/main/kotlin/io/github/mangi/eta"
TEST = ROOT / "app/src/test/kotlin/io/github/mangi/eta"


def source(name):
    return (MAIN / name).read_text()


def section(text, begin, end):
    return text.split(begin, 1)[1].split(end, 1)[0]


class UsagePreferencesRollbackContractTest(unittest.TestCase):
    def test_migration_is_a_datastore_gate_not_a_second_lazy_store(self):
        settings = source("data/datastore/SettingsDataStore.kt")
        self.assertIn("produceMigrations = { context -> listOf(UsageLedgerRollbackMigration(", settings)
        self.assertIn('File(context.filesDir, "datastore/eta_usage_ledger.json")', settings)
        self.assertIn("PreferencesUsageLedger(preferencesStore)", settings)
        self.assertFalse((MAIN / "data/datastore/UsageLedgerStore.kt").exists())
        ledger = source("data/datastore/PreferencesUsageLedger.kt")
        for forbidden in ("delay(", "maxDirtyEdits", "scheduleFlush", "storage.write", "MutableModelUsageLedger"):
            self.assertNotIn(forbidden, ledger)
        self.assertIn("edit { prefs ->", ledger)
        self.assertIn("withContext(NonCancellable + Dispatchers.IO)", ledger)

    def test_raw_and_digest_receipt_share_migration_transaction_and_cleanup_is_postcommit_only(self):
        migration = source("data/datastore/UsageLedgerRollbackMigration.kt")
        self.assertIn(": DataMigration<Preferences>", migration)
        body = section(migration, "override suspend fun migrate", "override suspend fun cleanUp")
        self.assertIn("val migrated = currentData.toMutablePreferences()", body)
        self.assertIn("migrated[MODEL_USAGE_JSON] = raw", body)
        self.assertIn('migrated[USAGE_ROLLBACK_RECEIPT] = "v1:sha256:$digest"', body)
        self.assertIn("return migrated", body)
        self.assertNotIn("archive(source", body)
        self.assertNotIn(".toString()", body)
        cleanup = section(migration, "override suspend fun cleanUp", "private fun readSource")
        self.assertIn("usageLedgerDigest(readSource()) != digest", cleanup)
        self.assertIn("archive(source, digest)", cleanup)
        self.assertIn("reportArchiveFailure(failure)", cleanup)
        self.assertIn("CodingErrorAction.REPORT", migration)
        self.assertIn('getInstance("SHA-256")', migration)
        self.assertIn("ATOMIC_MOVE", migration)

    def test_receipt_permanently_fences_stale_corrupt_and_changed_sources_after_restore_reset(self):
        migration = source("data/datastore/UsageLedgerRollbackMigration.kt")
        body = section(migration, "override suspend fun migrate", "override suspend fun cleanUp")
        marked = section(body, "if (receipt != null)", "val migrated =")
        self.assertIn("return currentData", marked)
        self.assertNotIn("readSource()", marked)
        self.assertNotIn("MODEL_USAGE_JSON] =", marked)
        ledger = source("data/datastore/PreferencesUsageLedger.kt")
        replace = section(ledger, "fun replaceIn", "\n    }\n")
        self.assertIn("prefs[MODEL_USAGE_JSON] = raw", replace)
        self.assertIn("if (prefs[USAGE_ROLLBACK_RECEIPT] == null)", replace)
        self.assertNotIn("remove(MODEL_USAGE_JSON)", replace)
        self.assertNotIn("remove(USAGE_ROLLBACK_RECEIPT)", source("data/datastore/SettingsDataStore.kt"))

    def test_snapshot_and_restore_use_one_preferences_version_without_journal_replacement(self):
        settings = source("data/datastore/SettingsDataStore.kt")
        snapshot = section(settings, "suspend fun backupSnapshot", "suspend fun restoreBackup")
        self.assertEqual(snapshot.count("usageLedger.preferencesSnapshot()"), 1)
        self.assertIn("modelUsageJson = prefs[MODEL_USAGE_JSON].orEmpty()", snapshot)
        self.assertNotIn("dataStore.data.first()", snapshot)
        restore = section(settings, "suspend fun restoreBackup", "suspend fun setAppearanceSettings")
        self.assertEqual(restore.count("dataStore.edit { prefs ->"), 1)
        self.assertIn("usageLedger.replaceIn(prefs, it)", restore)
        self.assertNotIn("usageLedger.replace(", restore)
        self.assertNotIn("dataStore.data", restore)
        self.assertNotIn("journal.commit", restore)
        self.assertIn("prefs[MEMORY_ENABLED] = snapshot.memoryEnabled", restore)
        self.assertIn("RETIRED_HEATMAP_JSON", restore)
        outer = source("data/repository/EtaBackupRepository.kt")
        self.assertIn("SettingsDataStore.restoreBackup(it)", outer)
        self.assertIn("journal.rollback()", outer)
        self.assertIn("exactPreferences = true", outer)
        self.assertIn("AgentExecutionService.beginBackupMaintenance()", outer)

    def test_missing_statistics_are_not_implicitly_zero_but_explicit_empty_is_written(self):
        settings = source("data/datastore/SettingsDataStore.kt")
        for name, kind in (("modelUsageJson", "String"), ("retiredInputTokens", "Long"),
                           ("retiredOutputTokens", "Long"), ("retiredCachedTokens", "Long"),
                           ("retiredConversations", "Int"), ("retiredMessages", "Int"),
                           ("retiredHeatmapJson", "String")):
            self.assertIn(f"val {name}: {kind}? = null", settings)
            self.assertIn(f"snapshot.{name}?.let", settings)
        self.assertNotIn("emptyPreferences()", settings)
        self.assertNotIn(".catch {", settings)
        ledger = source("data/datastore/PreferencesUsageLedger.kt")
        self.assertIn("validateModelUsageJson(raw)", ledger)
        update = section(ledger, "suspend fun update", "suspend fun replace")
        self.assertLess(update.index("project(current)"), update.index("transform(current)"))

    def test_accounting_failure_is_observable_and_preserves_original_result_or_error(self):
        provider = source("agent/model/UsageRecordingProvider.kt")
        self.assertNotIn("runCatching", provider)
        self.assertIn("reportFailure(failure)", provider)
        self.assertIn("UsageStatsRepository.reportAccountingFailure(failure)", provider)
        self.assertIn("original.addSuppressed(it)", provider)
        self.assertIn("accountingFailures.size < 8", provider)
        self.assertEqual(provider.count("UUID.randomUUID().toString()"), 1)
        self.assertIn("requestId = requestId", provider)
        self.assertIn("saved = usage", provider)
        self.assertIn("try { onEvent(event) } finally { persist() }", provider)
        repository = source("data/repository/UsageStatsRepository.kt")
        self.assertIn("val accountingFailure = accountingFailureState.asStateFlow()", repository)
        self.assertIn('Log.e("UsageAccounting",', repository)
        self.assertNotIn("failure.message", repository)

    def test_replacement_behavior_coverage_and_frozen_incremental_oracle_remain_in_ci(self):
        expected = {
            "data/datastore/UsageLedgerRollbackMigrationTest.kt": (
                "authoritativeFourMegabyteSourceIsCopiedVerbatim", "failedDataStoreCommitPersistsNeitherLedgerNorReceipt",
                "unreadableSourceFailsFirstAccessThenRetries", "archiveFailureChangedAndCorruptSurvivingSource"),
            "data/datastore/PreferencesUsageLedgerTest.kt": (
                "everyPartialIsDurableBeforeReturn", "concurrentRequestsCannotLoseReplacements",
                "cancellationDuringCommit", "commitFailureKeepsOldSnapshotAndProjection"),
            "data/datastore/SettingsUsageAtomicityTest.kt": (
                "actualRestoreIsOneEditAndCommitFailure", "migrationThenActualRestoreOrResetAndRestart",
                "missingBackupStatisticsPreserveAllExistingFields", "backupSnapshotReadsOnlyOneCommitted"),
            "data/repository/EtaBackupRepositoryTest.kt": ("failureAfterSettingsCommitRollsBackRichUsageImmediatelyAndThroughStartupJournal",),
        }
        for name, cases in expected.items():
            text = (TEST / name).read_text()
            for case in cases:
                self.assertIn(case, text)
        oracle = (TEST / "data/repository/ModelUsageLedgerEquivalenceTest.kt").read_text()
        for case in ("3999, 4000, 4003", "progressiveRequestReplacement", "seededRandomBatchesMatchFrozenOracle",
                     "seedStringWrapperRetainsOriginalMigration", "decimal counter"):
            self.assertIn(case, oracle)
        workflow = (ROOT / ".github/workflows/build-debug.yml").read_text()
        self.assertLess(workflow.index("unittest discover -s app/src/test/python"), workflow.index("run_unit_tests.py"))
        self.assertLess(workflow.index("run_unit_tests.py"), workflow.index(":app:assembleRelease"))
        runner = (ROOT / ".github/scripts/run_unit_tests.py").read_text()
        self.assertIn(":app:testDebugUnitTest", runner)
        self.assertNotIn('"--tests"', runner)


if __name__ == "__main__":
    unittest.main()
