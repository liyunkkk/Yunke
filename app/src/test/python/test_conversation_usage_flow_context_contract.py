import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
SOURCE = ROOT / "app/src/main/kotlin/io/github/mangi/eta/data/repository"


class ConversationUsageFlowContextContractTest(unittest.TestCase):
    def test_repository_entry_reads_the_ledger_owned_totals_flow(self):
        source = (SOURCE / "UsageStatsRepository.kt").read_text()
        # The totals map now lives inside the ledger store, not in SettingsDataStore.
        self.assertIn("SettingsDataStore.conversationUsageFlow(id)", source)
        self.assertNotIn("conversationUsageTotals(raw, id)", source)
        settings = (ROOT / "app/src/main/kotlin/io/github/mangi/eta/data/datastore/SettingsDataStore.kt").read_text()
        self.assertIn("return usageLedger.conversationFlow(id)", settings)
        store = (ROOT / "app/src/main/kotlin/io/github/mangi/eta/data/datastore/UsageLedgerStore.kt").read_text()
        self.assertIn("emitAll(totalsState.filterNotNull().map { totals ->", store)
        self.assertIn(".distinctUntilChanged())", store)

    def test_totals_decode_stays_off_the_ui_dispatcher_in_the_ledger_store(self):
        store = (ROOT / "app/src/main/kotlin/io/github/mangi/eta/data/datastore/UsageLedgerStore.kt").read_text()
        self.assertIn("withContext(Dispatchers.IO) { mutex.withLock { ensureLoaded(requireDurable = false) } }", store)
        self.assertIn("install(raw: String)", store)
        self.assertIn("conversationTotalsSnapshot()", store)

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
