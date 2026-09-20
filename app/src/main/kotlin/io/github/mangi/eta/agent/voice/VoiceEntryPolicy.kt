package io.github.mangi.eta.agent.voice

import io.github.mangi.eta.agent.voice.doubao.DoubaoVoiceConfig

internal object VoiceEntryPolicy {
    fun modes(config: DoubaoVoiceConfig.Config): List<VoiceEntryMode> = buildList {
        if (config.inputEnabled) add(VoiceEntryMode.DICTATION)
        if (config.conversationEnabled) add(VoiceEntryMode.UNIVERSAL)
        if (config.duplexEnabled) add(VoiceEntryMode.DOUBAO_DUPLEX)
    }
    fun enabled(config: DoubaoVoiceConfig.Config, mode: VoiceEntryMode): Boolean = mode in modes(config)
    fun directMode(config: DoubaoVoiceConfig.Config, lastSelected: String = ""): VoiceEntryMode? {
        val available = modes(config)
        // Unknown/disabled history must not silently enable a mode or guess dictation.
        return available.firstOrNull { it.wireValue == lastSelected } ?: available.singleOrNull()
    }
    fun canChoose(config: DoubaoVoiceConfig.Config): Boolean = modes(config).size > 1
}
