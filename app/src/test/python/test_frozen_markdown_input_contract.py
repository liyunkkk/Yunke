"""Pin only existing frozen renderer inputs; do not alter rendering or reveal policy."""
from pathlib import Path
import unittest
from test_agent_chat_viewport_contract import balanced_end

ROOT = Path(__file__).resolve().parents[3] / 'src/main/kotlin/io/github/mangi/eta'

class FrozenMarkdownInputContract(unittest.TestCase):
    def test_pinning_is_inside_the_existing_block_key_with_one_renderer_call(self):
        text = (ROOT / 'ui/components/ChatMessageItem.kt').read_text()
        start = text.index('key(node.startOffset, node.type.name) {')
        opening = text.index('{', start)
        end = balanced_end(text, opening, '{', '}')
        body = text[opening:end]
        for token in (
            'val freeze = revealCoordinator != null &&',
            'shouldFreezeStreamingMarkdownBlock(node.startOffset, lastVisibleStartOffset)',
            'rememberFrozenMarkdownInput(node, freeze)',
            'rememberFrozenMarkdownInput(content, freeze)',
            'node = renderNode', 'content = renderContent',
            'components = components', 'freeze = freeze',
        ):
            self.assertIn(token, body)
        self.assertEqual(1, body.count('FrozenMarkdownElement('))
        self.assertNotIn('rememberFrozenMarkdownInput(components', text)
        self.assertNotIn('substring', body)

    def test_capture_is_conditional_and_released_on_unfreeze(self):
        helper = (ROOT / 'ui/components/FrozenMarkdownInput.kt').read_text()
        self.assertIn('if (freeze) {\n        remember { value }\n    } else {\n        value', helper)
        for token in ('LaunchedEffect', 'remember(value', 'mutableStateOf', 'rememberSaveable'):
            self.assertNotIn(token, helper)

    def test_only_streaming_host_supplies_the_conservative_transformer(self):
        text = (ROOT / 'ui/components/ChatMessageItem.kt').read_text()
        self.assertEqual(1, text.count('rememberStreamingMarkdownImageTransformer(parsed.state.content)'))
        start = text.index('private fun StreamingMarkdown(')
        end = text.index('private fun StreamingGfmSuccess(', start)
        body = text[start:end]
        self.assertIn('val imageTransformer = rememberStreamingMarkdownImageTransformer(parsed.state.content)', body)
        self.assertIn('imageTransformer = imageTransformer,', body)
        helper = (ROOT / 'ui/components/StreamingMarkdownImageTransformer.kt').read_text()
        for token in ('private var bracketSyntaxSeen = false', "'[' in content", 'if (bracketSyntaxSeen) NoOpImageTransformerImpl() else retained', 'remember { StreamingMarkdownImageTransformerPolicy() }'):
            self.assertIn(token, helper)
        for token in ('ReferenceLinkHandlerImpl', 'parseMarkdown(', 'LocalMarkdown', 'LaunchedEffect', 'mutableStateOf'):
            self.assertNotIn(token, helper)
