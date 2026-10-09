package io.github.mangi.eta.agent.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 副屏剪贴板还原：只在「当前值仍是我们写入的」时才还原，绝不覆盖用户新内容。 */
class VirtualClipboardRestoreTest {
    @Test
    fun restoresOnlyWhileOurValueIsStillCurrent() {
        assertTrue(VirtualClipboardRestore.shouldRestore(written = "aiagent", current = "aiagent"))
        assertFalse(VirtualClipboardRestore.shouldRestore(written = "aiagent", current = "user-copied"))
        assertFalse(VirtualClipboardRestore.shouldRestore(written = "aiagent", current = null))
        assertFalse(VirtualClipboardRestore.shouldRestore(written = null, current = "aiagent"))
    }

    @Test
    fun writesBackTheBackupWhenThereWasOne() {
        assertEquals(
            VirtualClipboardRestore.RestoreAction.WRITE_BACK,
            VirtualClipboardRestore.restoreAction("typed", "typed", "old-clip", hadBackup = true),
        )
    }

    @Test
    fun clearsWhenThereWasNoClipboardBefore() {
        assertEquals(
            VirtualClipboardRestore.RestoreAction.CLEAR,
            VirtualClipboardRestore.restoreAction("typed", "typed", backup = null, hadBackup = false),
        )
    }

    @Test
    fun leavesForeignClipboardAlone() {
        assertEquals(
            VirtualClipboardRestore.RestoreAction.NONE,
            VirtualClipboardRestore.restoreAction("typed", "user-copied", "old-clip", hadBackup = true),
        )
    }
}
