package io.github.mangi.eta.ui.components

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w480dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SmoothTextRevealDrawTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun zeroPartialAppendAndCompleteFramesFadeOneGraphemeAtATime() {
        val coordinator = SmoothTextRevealCoordinator()
        val text = mutableStateOf("HHHH")
        val layouts = arrayOfNulls<TextLayoutResult>(1)
        compose.setContent {
            val state = rememberSmoothTextRevealState(RevealBlockKey(0), coordinator)
            Box(
                Modifier
                    .size(360.dp, 80.dp)
                    .background(Color.White)
                    .testTag("reveal-frame"),
            ) {
                BasicText(
                    text = text.value,
                    style = TextStyle(
                        color = Color.Black,
                        fontSize = 32.sp,
                        fontFamily = FontFamily.Monospace,
                    ),
                    softWrap = false,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.smoothTextReveal(state),
                    onTextLayout = { result ->
                        layouts[0] = result
                        state.onTextLayout(text.value, result)
                    },
                )
            }
        }
        compose.waitForIdle()

        setRevealProgress(coordinator, 0f)
        val emptyLayout = laidOutText(layouts, "HHHH")
        val empty = captureFrame()
        assertGlyphs(empty, emptyLayout, fullCount = 0, fadeIndex = null)
        val emptyInk = inkSum(empty, emptyLayout)

        setRevealProgress(coordinator, 1.5f)
        val partialLayout = laidOutText(layouts, "HHHH")
        val partial = captureFrame()
        assertGlyphs(partial, partialLayout, fullCount = 1, fadeIndex = 1)
        val partialInk = inkSum(partial, partialLayout)
        assertTrue("partial reveal should draw more than the empty frame", partialInk > emptyInk)

        compose.runOnIdle { text.value = "HHHHHHHH" }
        compose.waitForIdle()
        setRevealProgress(coordinator, 5.5f)
        val appendedLayout = laidOutText(layouts, "HHHHHHHH")
        val appended = captureFrame()
        assertGlyphs(appended, appendedLayout, fullCount = 5, fadeIndex = 5)
        val appendedInk = inkSum(appended, appendedLayout)
        assertTrue("appended text should reveal more ink without painting the tail", appendedInk > partialInk)

        val completeAt = coordinator.drawSnapshot(RevealBlockKey(0))!!.boundaries.lastIndex.toFloat()
        assertEquals(8f, completeAt, 0f)
        setRevealProgress(coordinator, completeAt)
        val completeLayout = laidOutText(layouts, "HHHHHHHH")
        val complete = captureFrame()
        assertGlyphs(complete, completeLayout, fullCount = 8, fadeIndex = null)
        assertTrue(
            "the finished line should be darker than the still-fading append",
            inkSum(complete, completeLayout) > appendedInk,
        )
    }

    private fun setRevealProgress(coordinator: SmoothTextRevealCoordinator, progress: Float) {
        compose.runOnIdle {
            val record = revealRecord(coordinator)
            val progressField = RevealRecord::class.java.getDeclaredField("progress")
            progressField.isAccessible = true
            progressField.setFloat(record, progress)
            val node = checkNotNull(record.node) { "smooth reveal node is not attached" }
            node.onRevealDataChanged()
        }
        compose.waitForIdle()
        assertEquals(progress, coordinator.drawSnapshot(RevealBlockKey(0))!!.progress, 0f)
    }

    @Suppress("UNCHECKED_CAST")
    private fun revealRecord(coordinator: SmoothTextRevealCoordinator): RevealRecord {
        val field = SmoothTextRevealCoordinator::class.java.getDeclaredField("records")
        field.isAccessible = true
        val records = field.get(coordinator) as Map<RevealBlockKey, RevealRecord>
        return records.getValue(RevealBlockKey(0))
    }

    private fun laidOutText(layouts: Array<TextLayoutResult?>, expected: String): TextLayoutResult {
        val layout = checkNotNull(layouts[0]) { "text layout did not run" }
        assertEquals(expected, layout.layoutInput.text.text)
        return layout
    }

    private fun captureFrame(): Bitmap =
        compose.onNodeWithTag("reveal-frame").captureToImage().asAndroidBitmap()

    private fun assertGlyphs(
        bitmap: Bitmap,
        layout: TextLayoutResult,
        fullCount: Int,
        fadeIndex: Int?,
    ) {
        val length = layout.layoutInput.text.length
        val inks = IntArray(length) { glyphDarkness(bitmap, layout, it) }
        for (index in 0 until fullCount) {
            assertTrue("grapheme $index should be opaque, ink=${inks[index]}", inks[index] >= 160)
        }
        if (fadeIndex != null) {
            val faded = inks[fadeIndex]
            val opaque = inks[fadeIndex - 1]
            assertTrue(
                "fading grapheme $fadeIndex should be lighter than the previous one ($faded vs $opaque)",
                faded + 40 < opaque,
            )
            assertTrue("fading grapheme $fadeIndex should still be visible ($faded)", faded >= 40)
        }
        val hiddenStart = fadeIndex?.plus(1) ?: fullCount
        for (index in hiddenStart until length) {
            assertTrue("grapheme $index should stay hidden, ink=${inks[index]}", inks[index] <= 24)
        }
    }

    private fun inkSum(bitmap: Bitmap, layout: TextLayoutResult): Int {
        val length = layout.layoutInput.text.length
        return (0 until length).sumOf { glyphDarkness(bitmap, layout, it) }
    }

    private fun glyphDarkness(bitmap: Bitmap, layout: TextLayoutResult, index: Int): Int {
        val box = layout.getBoundingBox(index)
        val left = (box.left + box.width * 0.35f).toInt()
        val right = (box.right - box.width * 0.35f).toInt().coerceAtLeast(left + 1)
        val top = box.top.toInt().coerceAtLeast(0)
        val bottom = box.bottom.toInt().coerceAtLeast(top + 1)
        var darkest = 0
        for (y in top until bottom) {
            if (y !in 0 until bitmap.height) continue
            for (x in left until right) {
                if (x !in 0 until bitmap.width) continue
                val pixel = bitmap.getPixel(x, y)
                if (AndroidColor.alpha(pixel) == 0) continue
                val ink = 255 - minOf(
                    AndroidColor.red(pixel),
                    AndroidColor.green(pixel),
                    AndroidColor.blue(pixel),
                )
                if (ink > darkest) darkest = ink
            }
        }
        return darkest
    }
}
