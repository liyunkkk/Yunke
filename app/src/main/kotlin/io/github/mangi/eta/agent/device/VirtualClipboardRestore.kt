package io.github.mangi.eta.agent.device

/**
 * 副屏文本写入的剪贴板策略。
 *
 * 副屏优先用无障碍 SET_TEXT 写入文本，完全不碰系统剪贴板；只有在取不到节点、
 * 必须借系统剪贴板 + PASTE 键传输时才写剪贴板，并在动作结束后还原。
 *
 * 还原必须满足「当前剪贴板仍然是我们写入的那个值」，否则说明用户或其它应用
 * 在这期间改了剪贴板，此时绝不能覆盖——宁可不还原，也不能吃掉用户的新内容。
 */
internal object VirtualClipboardRestore {
    fun shouldRestore(written: String?, current: String?): Boolean =
        written != null && current == written

    /** 备份文本为空且原本没有剪贴板内容时，还原动作是「清空」而不是写回空串。 */
    fun restoreAction(written: String?, current: String?, backup: String?, hadBackup: Boolean): RestoreAction {
        if (!shouldRestore(written, current)) return RestoreAction.NONE
        return if (hadBackup && backup != null) RestoreAction.WRITE_BACK else RestoreAction.CLEAR
    }

    enum class RestoreAction { NONE, WRITE_BACK, CLEAR }
}
