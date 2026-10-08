"""节点文本动作必须按 display 取根：副屏 replace_text/clear_text 不能被默认屏校验拦下。"""
import pathlib
import unittest

AGENT = pathlib.Path(__file__).resolve().parents[3] / 'src/main/kotlin/io/github/mangi/eta/agent'


class NodeTextActionDisplayContractTest(unittest.TestCase):
    def test_validation_root_is_display_scoped(self):
        service = (AGENT / 'accessibility/AgentAccessibilityService.kt').read_text()
        self.assertIn('NodeValidationDisplay.usesDisplayScopedRoot(snapshot.displayId)', service)
        self.assertIn('val displayId: Int = android.view.Display.DEFAULT_DISPLAY,', service)
        self.assertIn('displayId = displayId,', service)
        validation = service.split('private fun validateNode(', 1)[1].split('private fun findNode(', 1)[0]
        self.assertIn('rootForDisplay(snapshot.displayId)', validation)
        # 默认屏仍走 rootInActiveWindow，主屏语义不变。
        self.assertIn('rootInActiveWindow', validation)

    def test_virtual_text_path_stays_on_the_virtual_display(self):
        session = (AGENT / 'device/VirtualDisplaySession.kt').read_text()
        text = session.split('if (tool == "replace_text" || tool == "clear_text") {', 1)[1].split(
            'if (tool == "wait_for_text"', 1)[0]
        self.assertIn('service.setTextNode(snapshot, target.index, value)', text)
        self.assertIn('VirtualDisplayTextTarget.pick(nodes)', text)
        self.assertIn('副屏可改用 tap_element 聚焦后 paste_text', text)
        # 副屏文本写入绝不回退主屏输入焦点。
        self.assertNotIn('findFocusedEditableNode', text)


if __name__ == '__main__':
    unittest.main()
