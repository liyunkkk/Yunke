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
        self.assertIn('current.filterKeys(::isCurrentGenerationPreference)', policy)
        self.assertIn('MARKER to COMPLETED_MARKER', policy)
        self.assertIn('private const val COMPLETED_MARKER = "s:1"', boundary)
        for key in ['ConversationSubAgentPreferences.RESET_MARKER_KEY',
                    'ConversationSubAgentPreferences.UI_DRAFT_KEY', 'SubAgentModelDefaults.KEY']:
            self.assertIn(key, boundary)
        self.assertIn('store.validatePreferenceArchives(payloads)', boundary)

    def test_restore_preserves_local_reset_tombstones_but_never_imports_legacy_payloads(self):
        boundary = self.read('data/repository/BackupSubAgentConfig.kt')
        retained = boundary.split('private fun isCurrentGenerationPreference(', 1)[1].split(
            'fun validatePreferences(', 1)[0]
        self.assertIn('isSubAgentPreference(key) || isLegacyPreference(key)', retained)
        legacy = boundary.split('private fun isLegacyPreference(', 1)[1].split(
            'private fun isCurrentGenerationPreference(', 1)[0]
        self.assertIn('key == SubAgentPreferences.PROFILES_KEY', legacy)
        self.assertIn('legacySlot.matches(key)', legacy)
        self.assertIn('legacyParallelLimit.matches(key)', legacy)
        self.assertIn('key.startsWith("agent_collaboration_")', legacy)
        # Match reset's exact slot whitelist, including the two fields formerly left unfiltered.
        self.assertIn('Regex("agent_child_[0-3]_(provider|model|reasoning|task_tier|image_resolution|enabled)")', boundary)
        policy = boundary.split('fun preferencesForRestore(', 1)[1].split(
            'fun restoreExactPreferences(', 1)[0]
        marked, old = policy.split('} else {', 1)
        self.assertIn('incoming.filterKeys { !isLegacyPreference(it) }', marked)
        self.assertIn('SubAgentPreferences.PROFILES_KEY to "s:${JSONObject().put("version", 1).put("agents", JSONArray())}"', marked)
        self.assertIn('(incoming[ConversationSubAgentPreferences.UI_DRAFT_KEY] ?: "s:")', marked)
        # Incoming current owners/defaults are not overlaid with device-local ones or frozen seed.
        self.assertNotIn('current.filterKeys', marked)
        self.assertNotIn('SEED to', marked)
        self.assertNotIn('SubAgentModelDefaults.KEY to', marked)
        self.assertIn('!isSubAgentPreference(it) && !isLegacyPreference(it)', old)
        self.assertIn('current.filterKeys(::isCurrentGenerationPreference)', old)
        self.assertNotIn('Prefs.getString(', policy)
        self.assertNotIn('store.snapshot(', policy)

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

    def test_conversation_generation_is_serializable_optional_without_schema_bump(self):
        repository = self.read('data/repository/EtaBackupRepository.kt')
        self.assertIn('@Serializable\ninternal data class EtaConversationExport(', repository)
        document = repository.split('internal data class EtaConversationExport(', 1)[1].split(
            'internal data class EtaBackupSummary(', 1)[0]
        # A nullable constructor property with a default is optional for generated serializers.
        self.assertIn('val subAgentConfigGeneration: String? = null,', document)
        self.assertIn('const val SCHEMA_VERSION = 3', document)
        snapshot = repository.split('private suspend fun conversationSnapshot(', 1)[1].split(
            'private suspend fun restoreMetadata(', 1)[0]
        self.assertIn('subAgentConfigGeneration = "1",', snapshot)
        self.assertIn('subAgentConfigJson = BackupSubAgentConfig.archiveForExport(', snapshot)
        export = repository.split('suspend fun exportConversation(', 1)[1].split(
            'suspend fun import(', 1)[0]
        self.assertIn('json.encodeToString(document)', export)
        self.assertIn('encodeDefaults = true', repository)

    def test_unmarked_schema_three_ignores_archive_before_any_decode(self):
        boundary = self.read('data/repository/BackupSubAgentConfig.kt')
        gate = boundary.split('fun archiveForImport(', 1)[1].split('fun archiveForExport(', 1)[0]
        self.assertIn('generation: String? = null,', gate)
        legacy = gate.index('if (generation == null) return legacyArchive()')
        self.assertLess(legacy, gate.index('require(schemaVersion >= 3 || archive == null)'))
        self.assertLess(legacy, gate.index('val resolved = archive ?: legacyArchive()'))
        self.assertLess(legacy, gate.index('store.validateArchive(resolved)'))
        # No validation of the obsolete input on either the valid or malformed legacy path.
        self.assertNotIn('validateArchive', gate[:legacy])
        for forbidden in ['JSONObject(', 'decode(', 'store.snapshot(', 'store.export(',
                          'store.importOwner(', 'store.update(', 'Prefs.']:
            self.assertNotIn(forbidden, gate)

    def test_present_generation_is_rejected_or_strictly_validated_before_import_writes(self):
        boundary = self.read('data/repository/BackupSubAgentConfig.kt')
        gate = boundary.split('fun archiveForImport(', 1)[1].split('fun archiveForExport(', 1)[0]
        steps = ['require(generation == null || generation == "1")',
                 'if (generation == null) return legacyArchive()',
                 'val resolved = archive ?: legacyArchive()',
                 'store.validateArchive(resolved)', 'return resolved']
        positions = [gate.index(step) for step in steps]
        self.assertEqual(sorted(positions), positions)
        self.assertNotIn('runCatching', gate)
        repository = self.read('data/repository/EtaBackupRepository.kt')
        conversation_import = repository.split('if (conversation != null) {', 1)[1].split(
            'val planned = linkedMapOf<File, File>()', 1)[0]
        calls = ['BackupSubAgentConfig.archiveForImport(conversation.schemaVersion,',
                 'generation = conversation.subAgentConfigGeneration)',
                 'ConversationArchiveImport.prepare(', 'durableText(', 'journal.begin(',
                 'BackupConversationOwnerImport.plan(', 'owner.begin(appContext)',
                 'journal.replace(target, source)', 'importAsNewConversation(']
        positions = [conversation_import.index(call) for call in calls]
        self.assertEqual(sorted(positions), positions)

    def test_missing_conversation_config_is_empty_and_disabled(self):
        boundary = self.read('data/repository/BackupSubAgentConfig.kt')
        fallback = boundary.split('private fun legacyArchive()', 1)[1]
        self.assertIn('.put("enabled", false)', fallback)
        self.assertIn('.put("agents", JSONArray())', fallback)
        self.assertNotIn('SubAgentProfile(', fallback)
        self.assertNotIn('listOf(0, 2, 3, 1)', fallback)


if __name__ == '__main__':
    unittest.main()
