"""Source contracts; runtime parser/descriptor correctness is covered by Kotlin tests."""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
SRC = ROOT / "app/src/main/kotlin/io/github/mangi/eta/ui/components/StreamPerformanceDiagnostics.kt"

class MainThreadSchedstatHotPathContract(unittest.TestCase):
    def test_one_bulk_read_with_a_reused_buffer_and_no_per_byte_text_io(self):
        source = SRC.read_text()
        sampler = source.split("internal class MainThreadSchedstat", 1)[1].split("internal class MainThreadMessageLog", 1)[0]
        code = re.sub(r"//[^\n]*", "", sampler)
        self.assertIn("private val buffer = ByteArray(128)", code)
        self.assertEqual(code.count("opened.read(buffer)"), 1)
        self.assertIn("opened.seek(0)", code)
        self.assertNotIn("readLine(", code)
        self.assertNotIn("substring(", code)
        self.assertNotIn("String(", code)
        self.assertIn("Long.MAX_VALUE", code)
        self.assertIn("offset == length", code)
        self.assertIn("runCatching { opened?.close() }", code)

    def test_sampling_frequency_accounting_and_teardown_are_preserved(self):
        source = SRC.read_text()
        message = source.split("internal class MainThreadMessageLog", 1)[1].split("internal object", 1)[0]
        self.assertIn("val startedSched = schedstat?.invoke()", message)
        self.assertIn("val endedSched = schedstat?.invoke()", message)
        self.assertIn("schedstat = { mainThreadSchedstat.sample() }", source)
        self.assertIn("mainThreadSchedstat.close()", source)
        self.assertLess(source.index("Looper.getMainLooper().setMessageLogging(null)"), source.index("mainThreadSchedstat.close()"))

if __name__ == "__main__":
    unittest.main()
