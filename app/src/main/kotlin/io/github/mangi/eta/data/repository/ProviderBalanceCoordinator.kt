package io.github.mangi.eta.data.repository

import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.canQueryBalance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One independently published, cancellable request per provider. No network work under gate. */
internal class ProviderBalanceCoordinator(
    private val fetch: suspend (ProviderSetting) -> Result<String>,
    private val clock: () -> Long,
    private val format: (String) -> String = ::formatBalanceDisplay,
) {
    private val gate = Any()
    // Guarded by gate; monotonically increasing so UI can detect fresh balance changes.
    private var changeSeq = 0L
    private val jobs = mutableMapOf<String, Job>()
    // Compare query inputs only; timestamps, models and presentation edits do not invalidate balances.
    private val configurations = mutableMapOf<String, List<Any?>>()
    private val stateFlow = MutableStateFlow<Map<String, ProviderBalanceState>>(emptyMap())
    val states: StateFlow<Map<String, ProviderBalanceState>> = stateFlow.asStateFlow()
    private val amountFlow = MutableStateFlow<Map<String, String>>(emptyMap())
    val balances: StateFlow<Map<String, String>> = amountFlow.asStateFlow()

    suspend fun refresh(scope: CoroutineScope, providers: List<ProviderSetting>) {
        if (!scope.isActive) return
        val enabled = providers.filter(ProviderSetting::canQueryBalance).distinctBy { it.id }
        val pending = mutableListOf<Job>()
        synchronized(gate) {
            val ids = enabled.mapTo(mutableSetOf()) { it.id }
            (jobs.keys - ids).forEach { jobs.remove(it)?.cancel() }
            configurations.keys.retainAll(ids)
            publishStates(stateFlow.value.filterKeys { it in ids })
            for (provider in enabled) {
                val id = provider.id
                val configuration = listOf(provider.baseUrl, provider.apiKey, provider.balanceOption, provider.customHeaders, provider.authMode)
                val previousJob = jobs[id]
                if (previousJob != null && !previousJob.isCompleted && !previousJob.isCancelled && configurations[id] == configuration) continue
                jobs.remove(id)?.cancel()
                val previous = if (configurations[id] == configuration) stateFlow.value[id] ?: ProviderBalanceState() else ProviderBalanceState()
                configurations[id] = configuration
                publishStates(stateFlow.value + (id to previous.copy(refreshing = true)))
                val job = scope.launch(start = CoroutineStart.LAZY) {
                    val self = coroutineContext[Job]!!
                    val result = try { fetch(provider).mapCatching { raw -> raw to format(raw) } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { Result.failure(error) }
                    synchronized(gate) {
                        if (jobs[id] !== self || !self.isActive) return@synchronized
                        val current = stateFlow.value[id] ?: ProviderBalanceState()
                        val next = result.fold(
                            onSuccess = { (raw, display) ->
                                val now = clock()
                                val value = raw.trim().toBigDecimalOrNull()
                                val old = current.amountValue
                                val change = if (old != null && value != null && old.compareTo(value) != 0) {
                                    BalanceChange(seq = ++changeSeq, delta = value - old, atMillis = now)
                                } else {
                                    current.lastChange
                                }
                                current.copy(
                                    amount = display,
                                    amountValue = value,
                                    lastChange = change,
                                    updatedAtMillis = now,
                                    refreshing = false,
                                    error = null,
                                )
                            },
                            onFailure = { current.copy(refreshing = false, error = "Balance query failed; retry shortly") },
                        )
                        jobs.remove(id)
                        publishStates(stateFlow.value + (id to next))
                    }
                }
                jobs[id] = job
                job.invokeOnCompletion {
                    synchronized(gate) {
                        if (jobs[id] === job) {
                            jobs.remove(id)
                            stateFlow.value[id]?.let { previousState ->
                                publishStates(stateFlow.value + (id to previousState.copy(refreshing = false)))
                            }
                        }
                    }
                }
                pending.add(job)
            }
        }
        pending.forEach { it.start() }
    }

    private fun publishStates(states: Map<String, ProviderBalanceState>) {
        stateFlow.value = states
        amountFlow.value = states.mapNotNull { (id, state) -> state.amount?.let { id to it } }.toMap()
    }
}

/** The poller follows its current owner scope and can be started again after that scope is gone. */
internal class ProviderBalancePoller(
    private val refresh: suspend (CoroutineScope, List<ProviderSetting>) -> Unit,
    private val providersFlow: () -> Flow<List<ProviderSetting>>,
    private val intervalMs: Long,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    @Volatile private var job: Job? = null
    val isRunning: Boolean get() = job?.isActive == true

    @Synchronized fun start(scope: CoroutineScope) {
        if (!scope.isActive || job?.isActive == true) return
        job?.cancel()
        job = scope.launch(dispatcher) {
            val pollScope = this
            providersFlow().collectLatest { providers ->
                while (isActive) {
                    refresh(pollScope, providers)
                    delay(intervalMs)
                }
            }
        }
    }
    @Synchronized fun stop() { job?.cancel(); job = null }
}
