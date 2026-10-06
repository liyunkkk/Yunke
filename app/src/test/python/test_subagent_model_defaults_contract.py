"""Independent source contract guards, not Kotlin/Android behavioral execution."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3] / 'src/main/kotlin/io/github/mangi/eta'


class SubAgentModelDefaultsContractTest(unittest.TestCase):
    def setUp(self):
        self.repo = (ROOT / 'agent/delegation/ConversationSubAgentPreferences.kt').read_text()
        self.editor = (ROOT / 'ui/components/ConversationSubAgentEditor.kt').read_text()
        self.defaults = (ROOT / 'agent/delegation/SubAgentModelDefaults.kt').read_text()

    def test_exact_api_and_backup_keys(self):
        for signature in ('fun newProfileDraft(): SubAgentProfile',
                          'fun changeProfileModel(profile: SubAgentProfile, selection: ModelFeatureSelection): SubAgentProfile',
                          'fun rememberedParallelLimit(profile: SubAgentProfile): Int?',
                          'suspend fun commitProfileDraft(',
                          'internal data class SubAgentParallelLimitChange(val model: SubAgentParallelModel, val value: Int, val expected: Int)'):
            self.assertIn(signature, self.editor)
        self.assertIn('internal object SubAgentModelDefaults', self.defaults)
        self.assertIn('const val KEY = "agent_subagent_model_defaults_v1"', self.defaults)
        self.assertIn('fun validate(raw: String)', self.defaults)
        self.assertIn('RESET_MARKER_KEY = "agent_subagent_configuration_reset_v2"', self.repo)
        self.assertIn('fun resetLegacyConfigurationOnce(): Boolean', self.repo)
        self.assertIn('fun isConfigurationResetComplete(): Boolean', self.repo)

    def test_draft_restore_is_read_only_and_is_not_old_profile_memory(self):
        draft = self.editor.split('fun newProfileDraft()', 1)[1].split('suspend fun commitProfileDraft(', 1)[0]
        for forbidden in ('transaction(', 'repository.update(', 'putString(', 'commit(', 'configure('):
            self.assertNotIn(forbidden, draft)
        self.assertIn('UUID.randomUUID()', draft)
        self.assertIn('repository.modelDefaults(blank)?.restore(blank) ?: blank', draft)
        self.assertIn('reasoningByModel = emptyMap(), gptSpeedByModel = emptyMap()', draft)
        self.assertIn('SubAgentProfile.modelReasoningKey(providerId, modelId)', self.defaults)
        for field in ('enabled', 'role', 'tier', 'reasoning', 'imageResolution', 'gptSpeed', 'parallelLimit'):
            self.assertIn('val ' + field + ':', self.defaults)

    def test_confirm_lookups_precede_atomic_transaction_and_cas(self):
        confirm = self.editor.split('suspend fun commitProfileDraft(', 1)[1].split('/** Legacy explicit add', 1)[0]
        self.assertLess(confirm.index('repository.ownerState(owner)'), confirm.index('providerLookup('))
        self.assertLess(confirm.index('providerLookup('), confirm.index('repository.updateConfirmedProfile('))
        self.assertIn('owner is SubAgentConfigKey.Draft && !capturedOwnerState.exists', confirm)
        self.assertIn('expectedOwnerState = capturedOwnerState', confirm)
        for check in ('current != expectedProfile', 'current != null', 'old.presetApplicationToken != expectedApplicationToken',
                      'old.parallelLimit(parallelLimitChange.model) != parallelLimitChange.expected',
                      'parallelLimitChange.model != binding', 'model.supportsSpeechSynthesis',
                      'old.parallelLimits', 'enabled && canCommit()'):
            self.assertIn(check, confirm)
        txn = self.repo.split('fun updateConfirmedProfile(', 1)[1].split('fun saveLegacyConfiguration(', 1)[0]
        self.assertIn('key(owner) to encode(next)', txn)
        self.assertIn('changes[SubAgentModelDefaults.KEY]', txn)
        self.assertEqual(1, txn.count('transaction(changes, owner)'))
        self.assertNotIn('providerLookup', txn)
        self.assertIn('expectedOwnerState: OwnerState? = null', txn)
        missing_draft = 'owner is SubAgentConfigKey.Draft && stored(key(owner)) == null'
        owner_cas = 'expectedOwnerState != null && ownerState(owner) != expectedOwnerState'
        self.assertEqual(2, txn.count(missing_draft))
        self.assertEqual(2, txn.count(owner_cas))
        self.assertLess(txn.index(missing_draft), txn.index('change(read(owner).detached())'))
        self.assertLess(txn.index(owner_cas), txn.index('change(read(owner).detached())'))
        self.assertLess(txn.rindex(owner_cas), txn.index('transaction(changes, owner)'))
        self.assertIn('?: old.parallelLimits', confirm)  # null parallel change is not a default write

    def test_owner_fence_is_read_only_and_delete_recreate_keeps_revision_history(self):
        probe = self.repo.split('fun ownerState(', 1)[1].split('fun flow(', 1)[0]
        self.assertIn('synchronized(lock)', probe)
        self.assertIn('OwnerState(revision(owner).value, stored(key(owner)) != null)', probe)
        self.assertNotIn('transaction(', probe)
        self.assertNotIn('putString(', probe)
        transaction = self.repo.split('private fun transaction(', 1)[1].split('fun isConfigurationResetComplete()', 1)[0]
        self.assertIn('state.owners.getOrPut(owner)', transaction)
        self.assertIn('flow.value = flow.value + 1', transaction)
        delete = self.repo.split('fun delete(owner:', 1)[1].split('fun export(', 1)[0]
        self.assertIn('transaction(names, owner)', delete)
        self.assertNotIn('state.owners.remove(', delete)
        recreate = self.repo.split('fun importOwner(', 1)[1].split('private fun ownerFromSuffix(', 1)[0]
        self.assertIn('transaction(mapOf(key(owner) to encode(config.detached())), owner)', recreate)

    def test_reset_is_explicit_typed_and_whitelisted(self):
        reset = self.repo.split('fun resetLegacyConfigurationOnce()', 1)[1].split('private fun seed()', 1)[0]
        self.assertIn('if (isConfigurationResetComplete())', reset)
        for write in ('changes[RESET_MARKER_KEY] = "1"', 'changes[UI_DRAFT_KEY] = ""',
                      'enabled = false', 'SubAgentPresetCatalog.encode(emptyList())', 'SubAgentModelDefaults.encode(emptyMap())'):
            self.assertIn(write, reset)
        self.assertNotIn('.clear()', self.repo)
        self.assertEqual(1, self.repo.count('resetLegacyConfigurationOnce()'))
        self.assertIn('Map<String, Any?>?', self.repo)
        self.assertIn('putRaw(rollback, name, value)', self.repo)
        self.assertIn('putRaw(edit, name, value)', self.repo)
        self.assertIn('state.pending = old', self.repo)

    def test_no_frozen_seed_import_or_default_workers(self):
        catalog = self.repo.split('private fun catalogForWrite()', 1)[1].split('private fun validatePresetOwners(', 1)[0]
        self.assertNotIn('seed(', catalog)
        self.assertNotIn('DEFAULT_ID', catalog)
        self.assertIn('emptyList<SubAgentPresetCatalog.Entry>()', catalog)
        self.assertIn('ConversationSubAgentConfig(emptyList(), enabled = false)', self.repo)
        archives = self.repo.split('fun validatePreferenceArchives(', 1)[1].split('fun validateRestoredPreferences()', 1)[0]
        self.assertIn('values[SubAgentModelDefaults.KEY]?.let(SubAgentModelDefaults::validate)', archives)
        self.assertIn('values[RESET_MARKER_KEY]', archives)


if __name__ == '__main__':
    unittest.main()
