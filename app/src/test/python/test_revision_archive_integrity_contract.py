"""Guard the shared bounded snapshot and restoration/fork validation wiring, without compiling."""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
MODEL = ROOT / "app/src/main/kotlin/io/github/mangi/eta/agent/model"


class RevisionArchiveIntegrityContract(unittest.TestCase):
    def test_restore_decodes_the_verified_bytes_not_a_second_file_read(self):
        text = (MODEL / "AgentCompactionArchive.kt").read_text()
        body = text[text.index("private fun readVerifiedArchive"):text.index("private fun decodeArchive")]
        self.assertIn("AgentCompactionArchiveIntegrity.verifiedBytes(file, checksum, budget.byteLimit - budget.bytes)", body)
        self.assertIn("budget.bytes += bytes.size", body)
        self.assertIn("return bytes.toString(Charsets.UTF_8)", body)
        self.assertNotIn("file.readText", body)
        self.assertNotIn("BackupDurability.digest(file)", body)

    def test_restore_and_fork_share_marker_recognition_and_position_checks(self):
        restore = (MODEL / "AgentCompactionArchive.kt").read_text()
        fork = (MODEL / "AgentCompactionArchiveFork.kt").read_text()
        helper = (MODEL / "AgentCompactionArchiveIntegrity.kt").read_text()
        self.assertIn("AgentCompactionArchiveIntegrity.toolReference(message)", restore)
        self.assertIn("AgentCompactionArchiveIntegrity.toolReference(message)", fork)
        self.assertIn("AgentCompactionArchiveIntegrity.toolReference(source)", fork)
        self.assertIn("marker.findAll(message.content).take(2)", helper)
        self.assertIn("message.content[start - 1] != '\\n'", helper)
        self.assertIn("message.content[after] != '\\n'", helper)
        self.assertNotIn("private val toolMarker", fork)

    def test_checksum_and_content_reads_are_bounded_and_use_same_byte_snapshot(self):
        helper = (MODEL / "AgentCompactionArchiveIntegrity.kt").read_text()
        self.assertIn("LinkOption.NOFOLLOW_LINKS", helper)
        self.assertIn("boundedBytes(it, 64L)", helper)
        self.assertIn("boundedBytes(it, minOf(remainingBytes, MAX_FILE_BYTES.toLong()))", helper)
        self.assertIn('MessageDigest.getInstance("SHA-256").digest(bytes)', helper)
        self.assertIn("actual != expected", helper)
        self.assertIn("return bytes", helper)
        self.assertIn("limit - output.size() + 1L", helper)
        self.assertIn("Thread.currentThread().isInterrupted", helper)

    def test_fork_verifies_actual_content_before_staging_and_does_not_follow_assistant_titles(self):
        text = (MODEL / "AgentCompactionArchiveFork.kt").read_text()
        self.assertIn("AgentCompactionArchiveIntegrity.verifiedBytes(json, sha, byteLimit - total)", text)
        self.assertLess(text.index(".verifiedBytes("), text.index("stagedJson.outputStream()"))
        self.assertIn('message.role in listOf("user", "system") && AgentContextCompactor.isCompressionSummary(message)', text)
        self.assertIn("if (staging.exists()) staging.deleteRecursively()", text)


if __name__ == "__main__":
    unittest.main()
