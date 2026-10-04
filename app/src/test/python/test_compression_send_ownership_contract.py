import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[4]
APP = ROOT / "app/src/main/kotlin/io/github/mangi/eta/ui/app"


class CompressionSendOwnershipContract(unittest.TestCase):
    def test_send_gate_uses_current_owner_policy(self):
        source = (APP / "AgentAppState.kt").read_text()
        body = source.split("private fun isCompressionBlockingSend(): Boolean {", 1)[1].split(
            "private fun rejectSendIfCompressing()", 1)[0]
        for expected in (
            "conversationCompressionBlocksSend(",
            "conversationId = selectedConversationId",
            "isCompressingContext = homeState.isCompressingContext",
            "jobActive = compressionJob?.isActive == true",
            "jobConversationId = compressionJobConversationId",
            "hasPending = pending != null",
            "pendingConversationId = pending?.conversationId",
        ):
            self.assertIn(expected, body)
        self.assertNotIn("isActive == true ||", body)
        self.assertNotIn("isWaitingForCompression", body)

    def test_common_send_checks_remain_in_place(self):
        source = (APP / "AgentAppState.kt").read_text()
        self.assertEqual(8, source.count("rejectSendIfCompressing()") - 1)
        body = source.split("private fun rejectSendIfCompressing(): Boolean {", 1)[1].split(
            "private fun shouldKeepCompressingIndicator", 1)[0]
        self.assertIn("if (!isCompressionBlockingSend()) return false", body)


if __name__ == "__main__":
    unittest.main()
