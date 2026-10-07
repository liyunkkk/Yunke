import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
SOURCE = ROOT / "app/src/main/kotlin/io/github/mangi/eta/data/repository"


class ConversationUsageFlowContextContractTest(unittest.TestCase):
    def test_repository_entry_reads_the_preferences_owned_bounded_projection(self):
        source = (SOURCE / "UsageStatsRepository.kt").read_text()
        self.assertIn("SettingsDataStore.conversationUsageFlow(id)", source)
        self.assertNotIn("conversationUsageTotals(raw, id)", source)
        settings = (ROOT / "app/src/main/kotlin/io/github/mangi/eta/data/datastore/SettingsDataStore.kt").read_text()
        self.assertIn("return usageLedger.conversationFlow(id)", settings)
        store = (ROOT / "app/src/main/kotlin/io/github/mangi/eta/data/datastore/PreferencesUsageLedger.kt").read_text()
        self.assertIn("else project(raw)[id]", store)
        self.assertIn("private var projectedRaw: String? = null", store)
        self.assertIn("private var projectedTotals: Map<String, ConversationUsageTotals>", store)
        self.assertIn("if (raw != projectedRaw)", store)
        self.assertIn("@Synchronized", store)
        self.assertNotIn("MutableModelUsageLedger", store)
        self.assertNotIn("ConcurrentHashMap", store)

    def test_totals_validation_decode_and_projection_stay_off_ui_and_dedup_settings_emissions(self):
        store = (ROOT / "app/src/main/kotlin/io/github/mangi/eta/data/datastore/PreferencesUsageLedger.kt").read_text()
        raw_flow = store.split("fun rawFlow()", 1)[1].split("fun conversationFlow", 1)[0]
        self.assertLess(raw_flow.index(".distinctUntilChanged()"), raw_flow.index("project(raw)"))
        self.assertLess(raw_flow.index("project(raw)"), raw_flow.index(".flowOn(Dispatchers.IO)"))
        projection = store.split("private fun project", 1)[1].split("fun rawFlow()", 1)[0]
        self.assertIn("validateModelUsageJson(raw)", projection)
        self.assertIn("decodeConversationUsageTotals(root)", projection)
        self.assertLess(projection.index("decodeConversationUsageTotals(root)"), projection.index("projectedRaw = raw"))
        self.assertIn("withContext(Dispatchers.IO)", store)
        conversation = store.split("fun conversationFlow", 1)[1].split("suspend fun preferencesSnapshot", 1)[0]
        self.assertIn(".distinctUntilChanged()", conversation)
        self.assertIn(".flowOn(Dispatchers.IO)", conversation)

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
