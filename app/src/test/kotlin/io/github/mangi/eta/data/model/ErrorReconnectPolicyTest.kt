package io.github.mangi.eta.data.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ErrorReconnectPolicyTest {
    @Test
    fun policiesUseStableValuesAndTotalRetryWindows() {
        val expected = listOf(
            Triple(ErrorReconnectPolicy.NONE, "none", 0L),
            Triple(ErrorReconnectPolicy.WINDOW_30S, "window_30s", 30_000L),
            Triple(ErrorReconnectPolicy.WINDOW_1M, "window_1m", 60_000L),
            Triple(ErrorReconnectPolicy.WINDOW_5M, "window_5m", 300_000L),
            Triple(ErrorReconnectPolicy.CONTINUOUS, "continuous", null),
        )
        assertEquals(expected.map { it.first }, ErrorReconnectPolicy.entries.toList())
        expected.forEach { (policy, persisted, window) ->
            assertEquals(persisted, policy.persistedValue)
            assertEquals(window, policy.windowMillis)
            assertEquals(policy, ErrorReconnectPolicy.fromPersistedValue(persisted))
        }
    }

    @Test
    fun missingAndUnknownValuesNeverEnableReconnect() {
        listOf(null, "", "unknown", "WINDOW_30S", "30", " continuous ").forEach { raw ->
            assertEquals(ErrorReconnectPolicy.NONE, ErrorReconnectPolicy.fromPersistedValue(raw))
        }
    }

    @Test
    fun oldSettingsAndUnknownSerializedPolicyUseSafeDefault() {
        assertEquals(ErrorReconnectPolicy.NONE, Json.decodeFromString<Settings>("{}").errorReconnectPolicy)
        assertEquals(
            ErrorReconnectPolicy.NONE,
            Json.decodeFromString<Settings>("""{"errorReconnectPolicy":"future_policy"}""").errorReconnectPolicy,
        )
    }

    @Test
    fun legacyDefaultsUpgradeOnceWithoutChangingSelectedWindows() {
        // 旧默认（缺失或 none）与 v1 被强制写入的 continuous 一律回退到安全的 NONE。
        assertEquals(ErrorReconnectPolicy.NONE, ErrorReconnectPolicy.fromStoredSettings(null, null))
        assertEquals(ErrorReconnectPolicy.NONE, ErrorReconnectPolicy.fromStoredSettings("none", null))
        assertEquals(ErrorReconnectPolicy.NONE, ErrorReconnectPolicy.fromStoredSettings("none", 1))
        assertEquals(ErrorReconnectPolicy.NONE, ErrorReconnectPolicy.fromStoredSettings("continuous", 1))
        assertEquals(ErrorReconnectPolicy.NONE, ErrorReconnectPolicy.fromStoredSettings("future_policy", null))
        assertEquals(ErrorReconnectPolicy.NONE, ErrorReconnectPolicy.fromStoredSettings(null, 2))
        // 显式选择的时间窗档位在各版本间保持不变；升级后显式选的 continuous 也保留。
        listOf(
            ErrorReconnectPolicy.WINDOW_30S,
            ErrorReconnectPolicy.WINDOW_1M,
            ErrorReconnectPolicy.WINDOW_5M,
        ).forEach { policy ->
            assertEquals(policy, ErrorReconnectPolicy.fromStoredSettings(policy.persistedValue, null))
            assertEquals(policy, ErrorReconnectPolicy.fromStoredSettings(policy.persistedValue, 1))
            assertEquals(policy, ErrorReconnectPolicy.fromStoredSettings(policy.persistedValue, 2))
        }
        assertEquals(ErrorReconnectPolicy.CONTINUOUS, ErrorReconnectPolicy.fromStoredSettings("continuous", 2))
    }

    @Test
    fun serializedSettingsRoundTripEveryPolicyUsingStableValues() {
        ErrorReconnectPolicy.entries.forEach { policy ->
            val settings = Settings(errorReconnectPolicy = policy)
            val encoded = Json { encodeDefaults = true }.encodeToString(settings)
            assertEquals(policy, Json.decodeFromString<Settings>(encoded).errorReconnectPolicy)
            assertEquals("\"${policy.persistedValue}\"", Json.encodeToString(policy))
        }
    }
}
