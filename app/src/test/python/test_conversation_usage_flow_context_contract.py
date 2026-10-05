import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
SOURCE = ROOT / "app/src/main/kotlin/io/github/mangi/eta/data/repository"


class ConversationUsageFlowContextContractTest(unittest.TestCase):
    def test_repository_entry_uses_the_background_flow(self):
        source = (SOURCE / "UsageStatsRepository.kt").read_text()
        self.assertIn("conversationUsageTotalsFlow(SettingsDataStore.modelUsageFlow(), id)", source)
        self.assertNotIn("conversationUsageTotals(raw, id)", source)

    def test_raw_dedup_precedes_decode_and_dispatcher_follows_decode(self):
        source = (SOURCE / "ConversationUsageFlow.kt").read_text()
        dedup = source.index(".distinctUntilChanged()")
        decode = source.index(".map { raw -> conversationUsageTotals(raw, conversationId) }")
        background = source.index(".flowOn(Dispatchers.Default)")
        self.assertLess(dedup, decode)
        self.assertLess(decode, background)
        self.assertEqual(1, source.count(".distinctUntilChanged()"))

    def test_no_throttling_conflation_or_global_cache(self):
        source = (SOURCE / "ConversationUsageFlow.kt").read_text()
        for forbidden in ("conflate(", "debounce(", "sample(", "delay(", "mapLatest(",
                          "shareIn(", "stateIn(", "mutableStateOf(", "ConcurrentHashMap"):
            self.assertNotIn(forbidden, source)

    def test_existing_conversation_identity_guard_is_preserved(self):
        source = (ROOT / "app/src/main/kotlin/io/github/mangi/eta/ui/app/AgentAppRoot.kt").read_text()
        self.assertIn(".map { usageConversationId to it }", source)
        self.assertIn("recordedUsageState?.takeIf { it.first == usageConversationId }?.second", source)


if __name__ == "__main__":
    unittest.main()
