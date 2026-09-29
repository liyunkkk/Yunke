package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w480dp-h900dp-mdpi")
class StreamingListItemLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun unstartedRowsDoNotAccumulateMarkerHeightOrPadding() {
        val visible = mutableStateOf(false)
        val laidOutRows = mutableSetOf<Int>()
        compose.setContent {
            Column(Modifier.width(ListWidth).testTag("list")) {
                repeat(ROW_COUNT) { index ->
                    ListRow(
                        text = "列表项 $index",
                        visible = visible.value,
                        tag = "row-$index",
                        onTextLayout = { laidOutRows += index },
                    )
                }
            }
        }
        compose.waitForIdle()

        // 20 个未开始的行：alpha 0 的 marker 行高和上下 padding 都不能累计成空白。
        compose.onNodeWithTag("list").assertHeightIsEqualTo(0.dp)
        compose.onNodeWithTag("row-0").assertHeightIsEqualTo(0.dp).assertWidthIsEqualTo(ListWidth)
        compose.runOnIdle {
            assertEquals("hidden rows must still lay out their text", (0 until ROW_COUNT).toSet(), laidOutRows)
        }

        compose.runOnIdle { visible.value = true }
        compose.waitForIdle()

        val listHeight = compose.onNodeWithTag("list").getUnclippedBoundsInRoot().heightDp()
        val minimumRowHeight = MarkerSize + RowVerticalPadding * 2
        assertTrue(
            "visible rows should take marker height plus padding again, list=$listHeight",
            listHeight.value >= (minimumRowHeight * ROW_COUNT).value - 0.5f,
        )
    }

    @Test
    fun hiddenRowStillMeasuresAndDeliversTextLayoutForStreamingUpdates() {
        val text = mutableStateOf("第一段")
        val laidOutTexts = mutableListOf<String>()
        val layouts = arrayOfNulls<TextLayoutResult>(1)
        compose.setContent {
            Column(Modifier.width(ListWidth)) {
                ListRow(
                    text = text.value,
                    visible = false,
                    tag = "row",
                    onTextLayout = { result ->
                        layouts[0] = result
                        laidOutTexts += result.layoutInput.text.text
                    },
                )
            }
        }
        compose.waitForIdle()

        compose.runOnIdle {
            assertEquals("第一段", laidOutTexts.lastOrNull())
            val layout = checkNotNull(layouts[0]) { "hidden row text was not measured" }
            assertTrue("hidden text should be measured with real width", layout.size.width > 0)
            assertTrue("hidden text should be measured with real height", layout.size.height > 0)
        }
        compose.onNodeWithTag("row").assertHeightIsEqualTo(0.dp)

        // 流式追加时隐藏行也要重新排版，否则显现协调器拿不到新目标。
        compose.runOnIdle { text.value = "第一段追加的内容" }
        compose.waitForIdle()

        compose.runOnIdle { assertEquals("第一段追加的内容", laidOutTexts.lastOrNull()) }
        compose.onNodeWithTag("row").assertHeightIsEqualTo(0.dp).assertWidthIsEqualTo(ListWidth)
    }

    @Test
    fun switchingToVisibleRestoresHeightContentAndWidth() {
        val visible = mutableStateOf(false)
        val content = "恢复显示后的列表项正文"
        compose.setContent {
            Column(Modifier.width(ListWidth)) {
                ListRow(
                    text = content,
                    visible = visible.value,
                    tag = "item",
                    textModifier = Modifier.testTag("item-text"),
                )
                ListRow(
                    text = content,
                    visible = true,
                    tag = "reference",
                    textModifier = Modifier.testTag("reference-text"),
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithTag("item").assertHeightIsEqualTo(0.dp).assertWidthIsEqualTo(ListWidth)
        val reference = compose.onNodeWithTag("reference").getUnclippedBoundsInRoot()
        assertTrue("reference row must have real height", reference.heightDp().value > 0f)

        compose.runOnIdle { visible.value = true }
        compose.waitForIdle()

        val item = compose.onNodeWithTag("item").getUnclippedBoundsInRoot()
        assertDpEquals("row width", reference.widthDp(), item.widthDp())
        assertDpEquals("row height", reference.heightDp(), item.heightDp())

        val itemText = compose.onNodeWithTag("item-text").assertIsDisplayed().getUnclippedBoundsInRoot()
        val referenceText = compose.onNodeWithTag("reference-text").getUnclippedBoundsInRoot()
        val placedReference = compose.onNodeWithTag("reference").getUnclippedBoundsInRoot()
        assertDpEquals("text width", referenceText.widthDp(), itemText.widthDp())
        assertDpEquals("text height", referenceText.heightDp(), itemText.heightDp())
        assertDpEquals("text left inset", referenceText.left - placedReference.left, itemText.left - item.left)
        assertDpEquals("text top inset", referenceText.top - placedReference.top, itemText.top - item.top)
    }

    @Test
    fun hiddenRowsRegisterWithRealCoordinatorAndStartInOrder() {
        val coordinator = SmoothTextRevealCoordinator()
        val keys = listOf(RevealBlockKey(0), RevealBlockKey(100))
        val texts = listOf("第一项", "第二项内容")
        // Captured synchronously on every reveal frame: (first progress, second progress).
        val frames = mutableListOf<Pair<Float, Float>>()
        coordinator.setOnRevealAdvanced {
            frames += progressOf(coordinator, keys[0]) to progressOf(coordinator, keys[1])
        }
        compose.setContent {
            LaunchedEffect(coordinator) { coordinator.runFrameClock() }
            val started by coordinator.started.collectAsState()
            Column(Modifier.width(ListWidth)) {
                keys.forEachIndexed { index, key ->
                    val state = rememberSmoothTextRevealState(key, coordinator)
                    // 与 ChatMessageItem 接线一致：块开始显现（marker 可见）才展开整行。
                    ListRow(
                        text = texts[index],
                        visible = key in started,
                        tag = "row-$index",
                        textModifier = Modifier.smoothTextReveal(state),
                        onTextLayout = { result -> state.onTextLayout(texts[index], result) },
                    )
                }
            }
        }
        // waitForIdle drives the reveal frame clock until the coordinator stops requesting frames.
        compose.waitForIdle()

        compose.runOnIdle {
            val fullCounts = keys.map { key ->
                val snapshot = checkNotNull(coordinator.drawSnapshot(key)) { "block $key never received layout" }
                snapshot.boundaries.lastIndex.toFloat()
            }
            assertTrue("reveal should be driven by frames, not completed eagerly", frames.isNotEmpty())
            assertTrue(
                "first item must advance while the second row is still hidden and unstarted: $frames",
                frames.any { (first, second) -> first > 0f && second == 0f },
            )
            assertTrue(
                "hidden second item must start once the first finishes: $frames",
                frames.any { (_, second) -> second > 0f },
            )
            frames.filter { (_, second) -> second > 0f }.forEach { (first, _) ->
                assertEquals("second item must not start before the first completes", fullCounts[0], first, 0f)
            }
            assertEquals(fullCounts[0], progressOf(coordinator, keys[0]), 0f)
            assertEquals(fullCounts[1], progressOf(coordinator, keys[1]), 0f)
            assertTrue(coordinator.drained.value)
            assertEquals(keys.toSet(), coordinator.started.value)
        }
        keys.indices.forEach { index ->
            val row = compose.onNodeWithTag("row-$index").assertWidthIsEqualTo(ListWidth)
            assertTrue("started row $index should expand", row.getUnclippedBoundsInRoot().heightDp().value > 0f)
        }
    }

    private fun progressOf(coordinator: SmoothTextRevealCoordinator, key: RevealBlockKey): Float =
        coordinator.drawSnapshot(key)?.progress ?: 0f

    @Composable
    private fun ListRow(
        text: String,
        visible: Boolean,
        tag: String,
        textModifier: Modifier = Modifier,
        onTextLayout: (TextLayoutResult) -> Unit = {},
    ) {
        // 与生产接线相同的顺序：fillMaxWidth 之后、上下 padding 之前。
        Row(
            Modifier
                .testTag(tag)
                .fillMaxWidth()
                .streamingListItemLayout(visible)
                .padding(vertical = RowVerticalPadding),
        ) {
            // 未开始的 marker 只是 alpha 0，仍然占据行高。
            Box(Modifier.size(MarkerSize).alpha(0f))
            BasicText(
                text = text,
                style = TextStyle(fontSize = 16.sp, fontFamily = FontFamily.Monospace),
                modifier = Modifier.weight(1f).then(textModifier),
                onTextLayout = onTextLayout,
            )
        }
    }

    private fun assertDpEquals(message: String, expected: Dp, actual: Dp) {
        assertEquals(message, expected.value, actual.value, 0.5f)
    }

    private fun DpRect.widthDp(): Dp = right - left

    private fun DpRect.heightDp(): Dp = bottom - top

    private companion object {
        const val ROW_COUNT = 20
        val ListWidth = 320.dp
        val MarkerSize = 20.dp
        val RowVerticalPadding = 3.dp
    }
}
