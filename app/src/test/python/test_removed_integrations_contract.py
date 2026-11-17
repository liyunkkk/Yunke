"""Source contracts for retiring app integrations without touching general capabilities."""
import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[4]
MAIN = ROOT / "app/src/main"
KOTLIN = MAIN / "kotlin/io/github/mangi/eta"


class RemovedIntegrationsContractTest(unittest.TestCase):
    def test_redundant_overlay_configuration_is_absent(self):
        retired = (
            "AssistantOverlaySettings", "AssistantOverlayModel",
            "assistantOverlayRuntimeConfig", "applyAssistantOverlaySettings",
            "ASSISTANT_OVERLAY_SETTINGS",
        )
        for path in KOTLIN.rglob("*.kt"):
            text = path.read_text(encoding="utf-8")
            for symbol in retired:
                self.assertNotIn(symbol, text, str(path))

    def test_original_overlay_model_and_reasoning_controls_remain(self):
        source = (KOTLIN / "agent/voice/EtaAssistantOverlayService.kt").read_text(encoding="utf-8")
        for symbol in (
            "onModelSelected", "selectModel(modelId)", "setSelectedModelId(modelId)",
            "onReasoningEffortChange", "updateReasoningEffort",
        ):
            self.assertIn(symbol, source)

    def test_kimi_code_endpoints_launchers_and_installer_are_absent(self):
        retired = (
            "agent.kimi", "KimiCodeSubagentTool", "KimiWebLauncher", "KimiWebSession",
            "delegate_to_kimi_code", "KIMI_TOOLS_MARKER", "KIMI_TOOLS_REVISION",
            "LinuxPackageProfiles.KIMI", "KIMI_PERMISSION_MODE", "KIMI_WEB_USE_BUILTIN_BROWSER",
            "saveKimiModel", "kimiModel",
        )
        for path in KOTLIN.rglob("*.kt"):
            source = path.read_text(encoding="utf-8")
            for symbol in retired:
                self.assertNotIn(symbol, source, str(path))
        self.assertFalse((KOTLIN / "agent/tool/KimiCodeSubagentTool.kt").exists())
        self.assertFalse((MAIN / "res/drawable/ic_kimi_code.xml").exists())
        for path in (MAIN / "res").rglob("*.xml"):
            names = re.findall(r'<string\s+name="([^"]+)"', path.read_text(encoding="utf-8"))
            for name in names:
                self.assertFalse(name.startswith(("capability_kimi_", "linux_kimi_", "settings_kimi_")), name)
                self.assertNotEqual("action_launch_kimi_web", name)

    def test_general_tools_and_kimi_chat_provider_remain(self):
        for name in ("RootShellTerminalController.kt", "DetachedTaskSupervisor.kt", "LinuxPackageProfiles.kt"):
            self.assertTrue((KOTLIN / "agent/terminal" / name).is_file(), name)
        profiles = (KOTLIN / "agent/terminal/LinuxPackageProfiles.kt").read_text(encoding="utf-8")
        for name in ("PYTHON", "NODE", "SSH"):
            self.assertIn(name, profiles)
        provider = (KOTLIN / "data/provider/BuiltinProviders.kt").read_text(encoding="utf-8")
        self.assertIn('KIMI_ID = "builtin-kimi"', provider)
        self.assertTrue((MAIN / "res/drawable-xxxhdpi/provider_logo_kimi.webp").is_file())
        self.assertTrue((KOTLIN / "agent/model/ConversationTitleGenerator.kt").is_file())


if __name__ == "__main__":
    unittest.main()
