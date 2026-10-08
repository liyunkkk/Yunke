package io.github.mangi.eta.agent.device

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 副屏文本落点只认副屏本次观察的节点，且不能猜错输入框。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VirtualDisplayTextTargetTest {
    private fun node(
        index: Int,
        editable: Boolean,
        focused: Boolean,
        enabled: Boolean = true,
    ): RootShellDeviceController.UiNode = RootShellDeviceController.UiNode(
        index = index,
        text = "",
        desc = "",
        className = "android.widget.EditText",
        packageName = "com.example.app",
        viewId = "com.example.app:id/field$index",
        bounds = Rect(0, index * 100, 400, index * 100 + 80),
        clickable = true,
        longClickable = false,
        scrollable = false,
        focused = focused,
        editable = editable,
        password = false,
        enabled = enabled,
    )

    @Test
    fun focusedEditableWinsOverOtherEditableNodes() {
        val picked = VirtualDisplayTextTarget.pick(listOf(node(0, editable = true, focused = false), node(1, editable = true, focused = true)))
        assertEquals(1, picked?.index)
    }

    @Test
    fun singleEditableIsUsedWhenNothingIsFocused() {
        val picked = VirtualDisplayTextTarget.pick(listOf(node(0, editable = false, focused = true), node(3, editable = true, focused = false)))
        assertEquals(3, picked?.index)
    }

    @Test
    fun ambiguousEditablesAreNotGuessed() {
        assertNull(VirtualDisplayTextTarget.pick(listOf(node(0, editable = true, focused = false), node(1, editable = true, focused = false))))
    }

    @Test
    fun disabledEditableIsIgnored() {
        assertNull(VirtualDisplayTextTarget.pick(listOf(node(0, editable = true, focused = true, enabled = false))))
    }

    @Test
    fun nonEditableNodesAreIgnored() {
        assertNull(VirtualDisplayTextTarget.pick(listOf(node(0, editable = false, focused = true))))
        assertNull(VirtualDisplayTextTarget.pick(emptyList()))
    }
}
