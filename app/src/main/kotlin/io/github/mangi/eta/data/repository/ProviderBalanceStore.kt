package io.github.mangi.eta.data.repository

import java.math.BigDecimal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 同一配置下两次成功查询之间的余额变化。
 *
 * - [seq] 进程内递增，UI 以它判断是否为新事件。
 * - [delta] 新值减旧值；负数表示扣费。
 * - [atMillis] 产生变化的时间，用于丢弃过时事件。
 */
internal data class BalanceChange(
    val seq: Long,
    val delta: BigDecimal,
    val atMillis: Long,
)

/**
 * 单个 provider 的余额刷新状态。
 *
 * - [amount] 保留最后一次成功金额（失败不清空）。
 * - [amountValue] 与 [amount] 对应的原始数值；非数字结果为 null。
 * - [lastChange] 最近一次数值变化；配置变更会随状态一起重置，不跨账号计算差值。
 * - [updatedAtMillis] 仅在成功时更新。
 * - [refreshing] 当前是否有在途请求。
 * - [error] 最近一次失败的安全错误信息（不含响应正文）。
 */
internal data class ProviderBalanceState(
    val amount: String? = null,
    val updatedAtMillis: Long? = null,
    val refreshing: Boolean = false,
    val error: String? = null,
    val amountValue: BigDecimal? = null,
    val lastChange: BalanceChange? = null,
)

internal object ProviderBalanceStore {
    private const val POLL_INTERVAL_MS = 30_000L

    private val coordinator = ProviderBalanceCoordinator(
        fetch = { provider -> ProviderBalanceFetcher.fetch(provider) },
        clock = { System.currentTimeMillis() },
    )

    private val poller = ProviderBalancePoller(
        refresh = { scope, providers -> coordinator.refresh(scope, providers) },
        providersFlow = { ProviderRepository.providersFlow() },
        intervalMs = POLL_INTERVAL_MS,
    )

    /** 富状态，供新 UI 消费者使用。 */
    val states: StateFlow<Map<String, ProviderBalanceState>> = coordinator.states

    /** 兼容旧消费者：仅暴露成功金额的映射。 */
    val balances: StateFlow<Map<String, String>> = coordinator.balances

    fun start(scope: CoroutineScope) {
        poller.start(scope)
    }

    fun requestRefresh(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            coordinator.refresh(scope, ProviderRepository.allProviders())
        }
    }
}
