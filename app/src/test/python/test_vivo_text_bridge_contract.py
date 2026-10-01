"""Source/manifest safety contracts, not a substitute for device verification."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
MAIN = ROOT / 'src/main'
ANDROID = '{http://schemas.android.com/apk/res/android}'


class VivoTextBridgeContractTest(unittest.TestCase):
    def test_component_defaults_disabled_and_is_not_a_general_agent(self):
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
        self.assertEqual('false', value.text.strip())

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


if __name__ == '__main__':
    unittest.main()
