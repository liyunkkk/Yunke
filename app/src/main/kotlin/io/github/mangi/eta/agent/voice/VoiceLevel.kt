package io.github.mangi.eta.agent.voice

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * 麦克风 PCM 电平（0..1），供浮窗语音态的声波驱动使用。
 *
 * 口径：先算归一化 RMS，再折算成 dB 并映射到 [0, 1]（-60dB 记为 0，0dB 记为 1）。
 * 静音时 RMS 极小（约 -80dB），会被夹到 0，不会误触发声波状态机。
 */
internal fun pcmLevel(bytes: ByteArray, offset: Int, count: Int): Float {
    if (count <= 0) return 0f
    var sum = 0.0
    var index = 0
    while (index + 1 < count) {
        val at = offset + index
        val sample = ((bytes[at].toInt() and 0xFF) or (bytes[at + 1].toInt() shl 8)).toShort().toInt()
        val value = sample / 32768.0
        sum += value * value
        index += 2
    }
    val pairs = index / 2
    if (pairs == 0) return 0f
    return levelOf(sqrt(sum / pairs))
}

internal fun pcmLevel(samples: ShortArray, count: Int): Float {
    if (count <= 0) return 0f
    var sum = 0.0
    for (index in 0 until count) {
        val value = samples[index] / 32768.0
        sum += value * value
    }
    return levelOf(sqrt(sum / count))
}

private fun levelOf(rms: Double): Float {
    if (rms <= 0.0) return 0f
    val db = 20.0 * log10(rms)
    return ((db + 60.0) / 60.0).coerceIn(0.0, 1.0).toFloat()
}
