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
        branch = session.split(
            'if (tool in setOf("replace_text","clear_text","input_text","paste_text","type_text")) {', 1
        )[1].split('if (tool == "wait_for_text"', 1)[0]
        self.assertIn(
            'writeVirtualText(context, s, c, c.displayId, mode, index, value, deferAfterAction = submit)', branch)
        # submit 时写入路径不得自己再回读一次：写入与回车之间只允许一次动作后取树。
        self.assertIn('val submit = tool == "type_text" && args.optBoolean("submit",false)', branch)
        self.assertIn('if (!deferAfterAction) afterActionSummary(context, s, displayId, before)', session)
        writer = session.split('private fun writeVirtualText(', 1)[1].split('private fun readClipboardText(', 1)[0]
        # 优先无障碍直接写节点，不回退主屏输入焦点。
        self.assertIn('service.setTextNode(snapshot, target.index, next)', writer)
        self.assertIn('VirtualDisplayTextTarget.pick(nodes)', writer)
        self.assertIn('.put("clipboard_untouched",true)', writer)
        self.assertNotIn('findFocusedEditableNode', writer)
        # 只有取不到节点时才借系统剪贴板，并在动作后尽量还原。
        self.assertIn('restoreVirtualClipboard(context, s)', writer)
        self.assertIn('clipboard.setPrimaryClip(ClipData.newPlainText("", value))', writer)


if __name__ == '__main__':
    unittest.main()
