"""Wiring guards only: no local Android/Kotlin build."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3] / "src/main/kotlin/io/github/mangi/eta"

class PromptForecastContract(unittest.TestCase):
    def read(self, name):
        return (ROOT / name).read_text()

    def test_separate_forecast_wire_does_not_persist_as_cloud(self):
        wire = self.read("agent/runtime/AgentRuntimeWire.kt")
        self.assertIn('putInt("forecast_prompt_tokens", it)', wire)
        self.assertIn('optionalInt("forecast_prompt_tokens")', wire)
        for name in ("ui/model/CloudUsageReceiptCodec.kt", "ui/app/AgentConversationStore.kt"):
            self.assertNotIn("forecastPromptTokens", self.read(name))

    def test_forecast_keeps_one_counting_basis_and_is_not_cloud_pressure(self):
        loop = self.read("agent/model/AgentLoop.kt")
        self.assertIn("promptForecast.requestStarted(requestLocal)", loop)
        self.assertIn("promptForecast.tokens(requestLocal, preparedRequestTokens ?: requestLocal)", loop)
        self.assertEqual(1, loop.count("promptForecast.contextReplaced()"))
        self.assertIn("silentBudget.cloudTokens()", loop)
        forecast = self.read("agent/model/AgentPromptForecast.kt")
        self.assertNotIn("countTokens", forecast)
        self.assertNotIn("encrypted_content", forecast)
        self.assertNotIn("cloudTokens()", forecast)

    def test_forecast_reaches_both_chat_entry_points_and_all_composer_layers(self):
        for name in ("ui/screens/chat/AgentChatScreen.kt", "ui/screens/home/AgentHomeScreen.kt"):
            self.assertIn("forecastPromptTokens = state.forecastPromptTokens", self.read(name))
        body = self.read("ui/components/AgentChatBody.kt")
        self.assertEqual(3, body.count("forecastPromptTokens: Int? = null"))
        self.assertEqual(3, body.count("forecastPromptTokens = forecastPromptTokens"))
        bar = self.read("ui/components/AgentChatInputBar.kt")
        self.assertIn("forecastTokens = nextRequestTokens", bar)
        self.assertIn("shouldBlockSendForContextWindow(autoCompressEnabled, sendBudget)", bar)
        self.assertIn("usage = if (billedContextTokens != null && billedContextTokens > 0) liveUsage else sendBudget", bar)
        controls = self.read("ui/components/AgentChatModelControls.kt")
        self.assertIn("if (child == null) nextRequestForecastLabel(", controls)
        self.assertIn("text = forecastLabel", controls)

    def test_resetting_receipt_or_replacing_history_drops_stale_prediction(self):
        app = self.read("ui/app/AgentAppState.kt")
        self.assertEqual(app.count("livePromptTokens = null,"),
            app.count("livePromptTokens = null, forecastPromptTokens = null,"))
        self.assertIn("previous.history != state.history", app)
        self.assertIn("scoped.copy(forecastPromptTokens = null)", app)
        body = app.split("private fun updatePromptForecast(", 1)[1].split("private fun updateLivePromptTokens", 1)[0]
        self.assertIn("conversationIdForRun(runId)", body)
        self.assertIn("invalidatedUsageRuns", body)
        self.assertIn("owner != (state.providerId to state.modelId)", body)
        self.assertNotIn("livePromptTokens =", body)

if __name__ == "__main__":
    unittest.main()
