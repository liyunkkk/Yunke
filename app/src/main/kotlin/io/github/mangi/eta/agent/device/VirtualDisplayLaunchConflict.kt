package io.github.mangi.eta.agent.device

import org.json.JSONArray
import org.json.JSONObject

/**
 * 副屏 launch_app 撞上「主屏正在使用同一应用」时的答复。
 *
 * 选项：接管（首选）/ 停止主屏实例后继续 / 改到主屏 / 取消。
 *
 * 「接管」对齐 Mangi-11/Eta PR #142 的 switchTask：把主屏那个 task 搬到副屏，
 * 不杀进程、不重置界面，收尾时再搬回主屏。自动停止默认关闭（auto_stop_default=false），
 * 本对象只产出选项与下一步要求，不在代码里替用户决定，也不在这里执行任何停止动作。
 */
internal object VirtualDisplayLaunchConflict {
    const val CODE_ACTIVE = "TARGET_TASK_ACTIVE"
    const val CODE_RECENT = "TARGET_TASK_RECENT"

    val codes: Set<String> = setOf(CODE_ACTIVE, CODE_RECENT)

    const val OPTION_TAKEOVER = "takeover"
    const val OPTION_STOP_AND_RETRY = "stop_main_and_retry"
    const val OPTION_CONTINUE_ON_MAIN = "continue_on_main"
    const val OPTION_CANCEL = "cancel"

    fun payload(
        packageName: String,
        code: String,
        detail: String,
        autoTakeoverDefault: Boolean = false,
    ): JSONObject = JSONObject()
        .put("ok", false)
        .put("error", code)
        .put("package_name", packageName)
        .put("executed", false)
        .put(
            "message",
            "主屏正在使用 $packageName，副屏不能同时打开同一应用；本次未执行，没有停止或改动任何应用。" +
                "首选「接管」：把它搬到副屏继续（不杀进程、不重置界面），收尾时自动还回主屏。",
        )
        .put(
            "conflict",
            JSONObject()
                .put("package_name", packageName)
                .put("code", code)
                .put("detail", detail.take(200))
                .put("auto_stop_default", false)
                .put("auto_takeover_default", autoTakeoverDefault)
                .put(
                    "options",
                    JSONArray()
                        .put(
                            option(
                                OPTION_TAKEOVER,
                                "接管：把它搬到副屏继续",
                                "不杀进程、不重置界面，保留应用当前状态；任务收尾时自动还回主屏。" +
                                    "同意后重试 launch_app 并带上 takeover=true",
                            ),
                        )
                        .put(
                            option(
                                OPTION_STOP_AND_RETRY,
                                "停止主屏那个实例，改在副屏继续",
                                "需要用户先同意；同意后才可 app_state_control(action=force_stop) 再重试 launch_app",
                            ),
                        )
                        .put(
                            option(
                                OPTION_CONTINUE_ON_MAIN,
                                "这次操作改到主屏做",
                                "副屏任务不能中途切到主屏；只能由用户在任务设置里改成前台后重新发起",
                            ),
                        )
                        .put(
                            option(
                                OPTION_CANCEL,
                                "取消这次操作",
                                "跳过该应用，继续其它步骤或直接结束任务",
                            ),
                        ),
                ),
        )
        .put(
            "next_step",
            "必须先用 ask_user 让用户在这四项里选择；用户选 takeover 就用 launch_app(takeover=true) 重试；" +
                "用户同意 stop_main_and_retry 后才可调用 app_state_control(action=force_stop) 再重试 launch_app；" +
                "用户选 cancel 就跳过；禁止未经用户同意自行停止、冻结或清理任何应用。",
        )

    private fun option(id: String, label: String, note: String): JSONObject = JSONObject()
        .put("id", id)
        .put("label", label)
        .put("note", note)
}
