package io.github.mangi.eta.agent.accessibility

/**
 * 无障碍「真实可用」判据：已连接，且真的能枚举默认屏窗口。
 *
 * 重装 APK 后服务可能处于「已启用、已绑定、实例也在，但窗口缓存为空」的状态：
 * 此时节点工具与自身前台判据都会静默失效，必须让后端重新绑定才能恢复。
 * 任一条件不成立都按不可用上报（后端据此重绑）；探针主线程超时时按不可用处理。
 */
internal fun accessibilityConnectionHealthy(
    instanceAvailable: Boolean,
    defaultDisplayWindowsUsable: Boolean,
): Boolean = instanceAvailable && defaultDisplayWindowsUsable
