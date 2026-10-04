"""Source wiring guards only; does not compile or execute Kotlin/Android."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3] / 'src/main/kotlin/io/github/mangi/eta'


class SubAgentResetLifecycleContractTest(unittest.TestCase):
    def read(self, path):
        return (ROOT / path).read_text(encoding='utf-8')

    def test_startup_gate_is_main_process_only_and_precedes_work_initialization(self):
        app = self.read('EtaApp.kt')
        startup = app.split('override fun onCreate()', 1)[1].split('override fun onServiceBind(', 1)[0]
        self.assertLess(startup.index('AppProcessPolicy.shouldInitializeFullRuntime('),
                        startup.index('initializeSubAgentConfigurationBeforeWork()'))
        self.assertLess(startup.index('initializeSubAgentConfigurationBeforeWork()'),
                        startup.index('UsageStatsRepository.initializeConversationUsage('))
        self.assertLess(startup.index('initializeSubAgentConfigurationBeforeWork()'),
                        startup.index('XposedServiceHelper.registerListener('))
        self.assertLess(startup.index('initializeSubAgentConfigurationBeforeWork()'),
                        startup.index('LauncherIconSync.apply('))
        self.assertIn('throw throwable', startup)
        self.assertIn('maintenanceAlreadyHeld = true', app)
        self.assertEqual(1, app.count('store.resetLegacyConfigurationOnce()'))

    def test_gate_holds_one_fence_through_recovery_reset_and_durable_confirmation(self):
        gate = self.read('SubAgentConfigurationResetLifecycle.kt')
        body = gate.split('suspend fun initializeBeforeWork(', 1)[1]
        calls = ['if (!isMainProcess)', 'beginMaintenance()',
                 'recoverInterruptedRestoreBeforeWork()', 'check(recoverConfigurationDurability())',
                 'check(resetLegacyConfigurationOnce())', 'check(isConfigurationResetComplete())',
                 'endMaintenance()']
        positions = [body.index(call) for call in calls]
        self.assertEqual(sorted(positions), positions)
        self.assertNotIn('finally {', body)
        self.assertIn('if (initialized) return@withLock true', body)
        self.assertLess(body.index('endMaintenance()'), body.index('initialized = true'))

    def test_backup_import_never_triggers_another_reset(self):
        repository = self.read('data/repository/EtaBackupRepository.kt')
        boundary = self.read('data/repository/BackupSubAgentConfig.kt')
        self.assertNotIn('resetLegacyConfigurationOnce()', repository)
        self.assertNotIn('resetLegacyConfigurationOnce()', boundary)
        self.assertIn('BackupSubAgentConfig.preferencesForRestore(', repository)
        self.assertIn('subAgentStore.refreshAfterRestore()', repository)
        self.assertIn('BackupSubAgentConfig.validateExternalPreferences(', repository)

    def test_external_restore_retains_current_generation_or_validates_new_generation(self):
        boundary = self.read('data/repository/BackupSubAgentConfig.kt')
        policy = boundary.split('fun preferencesForRestore(', 1)[1].split('fun restoreExactPreferences(', 1)[0]
        self.assertIn('store.isConfigurationResetComplete()', policy)
        self.assertIn('validateExternalPreferences(incoming, store)', policy)
        self.assertIn('incoming[MARKER] == COMPLETED_MARKER', policy)
        self.assertIn('current.filterKeys(::isSubAgentPreference)', policy)
        self.assertIn('(MARKER to COMPLETED_MARKER)', policy)
        self.assertIn('private const val COMPLETED_MARKER = "s:1"', boundary)
        for key in ['ConversationSubAgentPreferences.RESET_MARKER_KEY',
                    'ConversationSubAgentPreferences.UI_DRAFT_KEY', 'SubAgentModelDefaults.KEY']:
            self.assertIn(key, boundary)
        self.assertIn('store.validatePreferenceArchives(payloads)', boundary)

    def test_exact_undo_does_not_run_migration_or_marker_policy(self):
        boundary = self.read('data/repository/BackupSubAgentConfig.kt')
        undo = boundary.split('fun restoreExactPreferences(', 1)[1].split('fun archiveForImport(', 1)[0]
        for forbidden in ['Prefs.restoreAgentPreferences(', 'preferencesForRestore(',
                          'resetLegacyConfigurationOnce(', 'MARKER to', 'migrateAskToForegroundOnce(']:
            self.assertNotIn(forbidden, undo)
        for required in ['toBooleanStrictOrNull()', 'toIntOrNull()', 'toLongOrNull()',
                         'preferences.edit().clear()', 'check(editor.commit())']:
            self.assertIn(required, undo)
        repository = self.read('data/repository/EtaBackupRepository.kt')
        self.assertIn('exactPreferences -> document.agentPreferences', repository)
        self.assertIn('BackupSubAgentConfig.restoreExactPreferences(', repository)

    def test_missing_conversation_config_is_empty_and_disabled(self):
        boundary = self.read('data/repository/BackupSubAgentConfig.kt')
        fallback = boundary.split('private fun legacyArchive()', 1)[1]
        self.assertIn('.put("enabled", false)', fallback)
        self.assertIn('.put("agents", JSONArray())', fallback)
        self.assertNotIn('SubAgentProfile(', fallback)
        self.assertNotIn('listOf(0, 2, 3, 1)', fallback)


if __name__ == '__main__':
    unittest.main()
