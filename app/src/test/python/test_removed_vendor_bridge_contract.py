"""Guard removal of the retired OEM text bridge without building Android code."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[4]
KOTLIN = ROOT / 'app/src/main/kotlin/io/github/mangi/eta'
ANDROID = '{http://schemas.android.com/apk/res/android}'


class RemovedVendorBridgeContractTest(unittest.TestCase):
    def text(self, path):
        return (ROOT / path).read_text()

    def test_bridge_source_and_dedicated_tests_are_absent(self):
        for path in (
            'app/src/main/kotlin/io/github/mangi/eta/agent/vivo',
            'app/src/main/kotlin/io/github/mangi/eta/hook/vivo',
            'app/src/main/kotlin/io/github/mangi/eta/config/VivoBridgeConsent.kt',
            'app/src/test/kotlin/io/github/mangi/eta/agent/vivo',
            'app/src/test/kotlin/io/github/mangi/eta/hook/vivo',
            'app/src/test/kotlin/io/github/mangi/eta/config/VivoBridgeConsentTest.kt',
        ):
            target = ROOT / path
            self.assertFalse(target.is_file(), path)
            if target.is_dir():
                self.assertFalse(any(target.rglob('*.*')), path)

    def test_production_tree_has_no_retired_bridge_identifiers(self):
        needles = ('VivoHooks', 'VivoBridgeConsent', 'VIVO_TEXT_BRIDGE',
                   'vivo_text_bridge', 'com.vivo.ai.copilot', 'EtaVivoText',
                   'V2419A', '6090021')
        for path in (ROOT / 'app/src/main').rglob('*'):
            if path.is_file() and path.suffix in ('.kt', '.xml', '.list'):
                source = path.read_text()
                for needle in needles:
                    self.assertNotIn(needle, source, str(path.relative_to(ROOT)))

    def test_other_xposed_scopes_and_installers_are_preserved(self):
        scope = self.text('app/src/main/resources/META-INF/xposed/scope.list').splitlines()
        self.assertEqual(len(scope), len(set(scope)))
        self.assertEqual(set(scope), {
            'system', 'com.android.systemui', 'com.google.android.googlequicksearchbox',
            'com.coloros.colordirectservice', 'com.heytap.speechassist', 'com.oplus.aimemory',
            'com.miui.voiceassist', 'com.miui.home', 'com.mi.android.globallauncher',
        })
        source = (KOTLIN / 'ModuleMain.kt').read_text()
        for installer in ('BreenoHooks.install', 'ColorDirectHooks.install',
                          'ColorOsMemoryHooks.install', 'HyperOsScreenSearchHooks.install'):
            self.assertIn(installer, source)
        self.assertNotIn('VivoHooks', source)

    def test_exported_bridge_and_resource_are_absent(self):
        manifest = ET.fromstring(self.text('app/src/main/AndroidManifest.xml'))
        services = {node.attrib.get(ANDROID + 'name') for node in manifest.iter('service')}
        self.assertNotIn('.agent.vivo.VivoTextBridgeService', services)
        self.assertIn('.agent.runtime.AgentRuntimeService', services)
        self.assertIn('.agent.voice.EtaVoiceInteractionService', services)
        self.assertFalse((ROOT / 'app/src/main/res/values/vivo_text_bridge.xml').exists())

    def test_dedicated_release_checks_and_abi_keepnames_are_absent(self):
        self.assertFalse((ROOT / '.github/scripts/check_vivo_release_reflection.py').exists())
        workflow = self.text('.github/workflows/build-debug.yml')
        self.assertNotIn('check_vivo_release_reflection', workflow)
        self.assertNotIn('Vivo', workflow)
        self.assertIn('workflow_dispatch:', workflow)
        rules = self.text('app/proguard-rules.pro')
        self.assertNotIn('-keepnames interface kotlin.jvm.functions.Function2', rules)
        self.assertNotIn('-keepnames class kotlin.coroutines.jvm.internal.ContinuationImpl', rules)

    def test_shared_model_completion_retains_safety_without_bridge_diagnostics(self):
        source = (KOTLIN / 'agent/model/ModelFeatureCompletion.kt').read_text()
        self.assertNotIn('FailurePhase', source)
        self.assertNotIn('onFailurePhase', source)
        for marker in ('withoutOptionalThinking(config)', 'AssistantStopReason.END_TURN',
                       'optJSONArray("tool_calls")', 'watchdog.interrupt()',
                       'child.cancel()', 'binding.close()'):
            self.assertIn(marker, source)
        defaults_test = self.text('app/src/test/kotlin/io/github/mangi/eta/config/PrefsDefaultsTest.kt')
        self.assertNotIn('VIVO_TEXT_BRIDGE', defaults_test)


if __name__ == '__main__':
    unittest.main()
