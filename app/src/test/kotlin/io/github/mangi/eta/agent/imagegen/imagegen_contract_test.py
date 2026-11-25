"""Non-Gradle source contracts; execute with python3 imagegen_contract_test.py."""
from pathlib import Path
import unittest

# Locate source alongside test rather than depending on the process working directory.
ROOT = next(p for p in Path(__file__).resolve().parents if p.name == 'src') / 'main/kotlin/io/github/mangi/eta'


class ImageGenSourceContracts(unittest.TestCase):
    def read(self, name):
        return (ROOT / name).read_text()

    def test_real_provider_constants_and_one_candidate_policy(self):
        source = self.read('data/repository/ImageGenSettingsRepository.kt')
        for invalid in ('BuiltinProviders.GEMINI_ID', 'BuiltinProviders.XAI_ID', 'ProviderSourceTypes.XAI'):
            self.assertNotIn(invalid, source)
        self.assertIn('linkedProviderCandidates(backend, ProviderRepository.allProviders())', source)
        self.assertIn('val candidates = linkedProviderCandidates(backend, allProviders)', source)
        self.assertIn('compatible && host == "api.x.ai"', source)

    def test_run_local_commit_precedes_room_and_history_uses_codec(self):
        source = self.read('agent/imagegen/ImageGenToolExecutor.kt')
        self.assertLess(source.index('lastCommittedId?.let'), source.index('latestSuccessfulToolMessage'))
        self.assertGreater(source.index('lastCommittedId = savedRecords'), source.index('saveGeneratedImagesBatch'))
        self.assertIn('decodeUserMessageImages(latestUserWithImages.imagesJson)', source)
        self.assertIn('attachments.videoFlags.getOrNull(index) == true || AgentVideoCodec.isVideoSource(ref)', source)
        self.assertIn('AgentFileReferenceGateway.safeImportName(conversationId)', source)
        self.assertIn('expected.canonicalFile != expected', source)

    def test_full_metadata_and_durable_session_owner(self):
        source = self.read('agent/imagegen/GeneratedImageStore.kt')
        self.assertNotIn('MAX_INDEX_RECORDS', source)
        self.assertIn('val sessionKey: String? = null', source)
        self.assertIn('it.sessionKey == sessionKey', source)
        self.assertIn('newRecords.asReversed() + existingRecords.filterNot', source)

    def test_all_channels_share_count_and_byte_budget(self):
        source = self.read('agent/imagegen/ImageGenClients.kt')
        channels = [line for line in source.splitlines() if 'normalizedRequest, runController, httpClient' in line]
        self.assertEqual(6, len(channels))
        self.assertTrue(all(', budget)' in line for line in channels))
        self.assertEqual(5, source.count('budget.commit(bytes.size)'))
        self.assertIn('MAX_BATCH_OUTPUT_BYTES = 48 * 1024 * 1024', source)
        self.assertIn('if (!target.isHttps', source)
        self.assertIn('target.resolve(it)', source)
        self.assertIn('.followRedirects(false).followSslRedirects(false)', source)


if __name__ == '__main__':
    unittest.main()
