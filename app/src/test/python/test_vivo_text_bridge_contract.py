"""Source/manifest safety contracts, not a substitute for device verification."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
MAIN = ROOT / 'src/main'
ANDROID = '{http://schemas.android.com/apk/res/android}'


class VivoTextBridgeContractTest(unittest.TestCase):
    def test_component_requires_independent_opt_in_and_is_not_an_agent(self):
        manifest = ET.parse(MAIN / 'AndroidManifest.xml').getroot()
        services = manifest.findall('application/service')
        matches = [s for s in services if s.get(ANDROID + 'name') == '.agent.vivo.VivoTextBridgeService']
        self.assertEqual(1, len(matches))
        service = matches[0]
        self.assertEqual('@bool/vivo_text_bridge_enabled', service.get(ANDROID + 'enabled'))
        self.assertEqual('true', service.get(ANDROID + 'exported'))
        self.assertEqual([], service.findall('intent-filter'))
        values = ET.parse(MAIN / 'res/values/vivo_text_bridge.xml').getroot()
        value = values.find("bool[@name='vivo_text_bridge_enabled']")
        self.assertIsNotNone(value)
        self.assertEqual('true', value.text.strip())
        prefs = (MAIN / 'kotlin/io/github/mangi/eta/config/Prefs.kt').read_text()
        self.assertIn('VIVO_TEXT_BRIDGE to false', prefs)
        service_source = (MAIN / 'kotlin/io/github/mangi/eta/agent/vivo/VivoTextBridgeService.kt').read_text()
        self.assertIn('&& VivoBridgeConsent.localEnabled()', service_source)

    def test_service_has_no_agent_executor_or_sensitive_logging(self):
        source = (MAIN / 'kotlin/io/github/mangi/eta/agent/vivo/VivoTextBridgeService.kt').read_text()
        for forbidden in ('AgentRuntimeService.', 'AgentExecutionService', 'AgentLoop',
                          'Log.', 'println(', 'printStackTrace(', 'FileOutputStream',
                          'setSelectedProviderId(', 'setSelectedModelId('):
            self.assertNotIn(forbidden, source)
        self.assertIn('msg.sendingUid', source)
        self.assertIn('linkToDeath', source)
        self.assertIn('unlinkToDeath', source)
        self.assertIn('compareAndSet(false, true)', source)


    def test_selection_is_read_only_and_body_overrides_fail_closed(self):
        source = (MAIN / 'kotlin/io/github/mangi/eta/agent/vivo/VivoTextModelGateway.kt').read_text()
        for forbidden in ('currentRuntimeConfig()', 'repairSelection(', 'selectedOrFirstModel(',
                          'ensureBuiltInsMerged(', 'setSelection('):
            self.assertNotIn(forbidden, source)
        self.assertIn('configForProviderAndModel(provider, model)', source)
        self.assertIn('config.customBody.isNotEmpty()', source)
        self.assertIn('config.extraBodyJson.isNotBlank()', source)
        self.assertIn('selected != readSelection()', source)

    def test_typed_identity_seam_and_no_cloud_retry_after_claim(self):
        source = (MAIN / 'kotlin/io/github/mangi/eta/hook/vivo/VivoHooks.kt').read_text()
        self.assertIn('IdentityHashMap<Any, Candidate>', source)
        self.assertIn('LinkServer.m(ChatPayload,linkId,callback)', source)
        self.assertNotIn('JSONObject(', source)
        self.assertNotIn('invokeOriginalMethod', source)
        self.assertNotIn('getPayload', source)
        self.assertIn('active.put(link, owned)', source)
        self.assertIn('if (active[link] !== owned) return@post', source)
        scope = (MAIN / 'resources/META-INF/xposed/scope.list').read_text().splitlines()
        self.assertEqual(1, scope.count('com.vivo.ai.copilot'))
        self.assertNotIn('com.vivo.ai.gptagent', scope)
        policy = (MAIN / 'kotlin/io/github/mangi/eta/core/ModuleConfig.kt').read_text()
        self.assertNotIn('com.vivo.ai.copilot', policy)


if __name__ == '__main__':
    unittest.main()
