"""Source contract for the window-level high-refresh experiment."""
from pathlib import Path
import re
import unittest
from test_agent_chat_viewport_contract import balanced_end, code_only


ROOT = Path(__file__).resolve().parents[3] / "src/main/kotlin/io/github/mangi/eta/ui"


def body(source, name):
    found = re.search(rf"\bfun\s+{re.escape(name)}\s*\(", source)
    if found is None:
        raise AssertionError(f"Missing function {name}")
    opening = source.index("(", found.start())
    start = source.index("{", balanced_end(source, opening, "(", ")"))
    return source[start + 1:balanced_end(source, start, "{", "}")]


class MainActivityRefreshRateContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.raw_activity = (ROOT / "MainActivity.kt").read_text()
        cls.activity = code_only(cls.raw_activity)
        cls.policy = code_only((ROOT / "RefreshRatePolicy.kt").read_text())

    def test_resume_requests_and_reapplies_the_window_preference(self):
        resume = body(self.activity, "onResume")
        self.assertIn("applyPreferredRefreshRate()", resume)
        self.assertIn("window.decorView.post(refreshRateRequest)", resume)

    def test_preference_uses_the_highest_supported_rate_without_forcing_resolution(self):
        apply = body(self.activity, "applyPreferredRefreshRate")
        self.assertIn("window.decorView.display?.supportedModes", apply)
        self.assertIn("highestSupportedRefreshRate", apply)
        self.assertIn("if (preferredRefreshRate <= 0f) return", apply)
        self.assertLess(apply.index("if (preferredRefreshRate <= 0f) return"), apply.index("attributes.preferredRefreshRate ="))
        self.assertIn("attributes.preferredRefreshRate = preferredRefreshRate", apply)
        self.assertIn("Eta.refresh.request hz=", body(self.raw_activity, "applyPreferredRefreshRate"))
        self.assertIn("Trace.beginSection(", apply)
        self.assertIn("Trace.endSection()", apply)
        self.assertNotIn("preferredDisplayModeId", apply)

    def test_pause_cancels_retry_and_restores_only_the_owned_preference(self):
        pause = body(self.activity, "onPause")
        self.assertIn("refreshRateWindowResumed = false", pause)
        self.assertIn("window.decorView.removeCallbacks(refreshRateRequest)", pause)
        self.assertIn("window.attributes.preferredRefreshRate == requestedPreferredRefreshRate", pause)
        self.assertIn("attributes.preferredRefreshRate = previous", pause)
        self.assertIn("previousPreferredRefreshRate = null", pause)
        self.assertIn("requestedPreferredRefreshRate = null", pause)
        apply = body(self.activity, "applyPreferredRefreshRate")
        self.assertIn("if (!refreshRateWindowResumed || isFinishing || isDestroyed) return", apply)

    def test_selector_is_bounded_and_ignores_unusable_rates(self):
        select = body(self.policy, "highestSupportedRefreshRate")
        self.assertIn("refreshRate.isFinite()", select)
        self.assertIn("refreshRate > highest", select)
        self.assertRegex(select, r"return\s+highest")


if __name__ == "__main__":
    unittest.main()
