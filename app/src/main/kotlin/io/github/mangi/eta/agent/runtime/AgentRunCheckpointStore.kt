package io.github.mangi.eta.agent.runtime

import android.content.Context
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.RuntimeInFlightEventEntity
import io.github.mangi.eta.data.db.RuntimeInFlightRunEntity
import io.github.mangi.eta.ui.components.StreamPerformanceDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * 在途 UI run 的进程持久化日志。
 *
 * 它不保存 Provider 配置、API Key、工具调用参数增量或原始工具结果，只保存 UI 已可见的
 * 参数摘要、终端命令与结果摘要。
 */
internal object AgentRunCheckpointStore {
    data class Checkpoint(
        val runId: String,
        val ownerInstanceId: String,
        val handoff: AgentRuntimeWire.EntryHandoff,
        val events: List<AgentEvent>,
        val createdAt: Long,
        val updatedAt: Long,
    )

    fun start(
        context: Context,
        request: AgentRuntimeWire.RunRequest,
        ownerInstanceId: String = AgentRuntimeProcessIdentity.id,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val handoff = request.handoff ?: return false
        if (handoff.source != AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE) return false
        val runId = request.runId.takeIf(String::isNotBlank) ?: return false
        runBlocking(Dispatchers.IO) {
            EtaDatabase.get(context.applicationContext).runtimeRunDao().replaceInFlightRun(
                RuntimeInFlightRunEntity(
                    runId = runId,
                    ownerInstanceId = ownerInstanceId,
                    handoffId = handoff.id,
                    handoffSource = handoff.source,
                    handoffPayload = handoff.payload,
                    dismissEntrySurface = handoff.dismissEntrySurfaceOnForegroundOperation,
                    createdAt = now,
                    updatedAt = now,
                )
            )
        }
        return true
    }

    fun append(
        context: Context,
        runId: String,
        sortIndex: Int,
        event: AgentEvent,
        now: Long = System.currentTimeMillis(),
    ) {
        val diagnose = StreamPerformanceDiagnostics.enabled
        val attribution = if (diagnose) StreamPerformanceDiagnostics.captureAttribution() else null
        runBlocking(Dispatchers.IO) {
            // Keep DAO acquisition before encoding, as in the original call expression.
            val dao = EtaDatabase.get(context.applicationContext).runtimeRunDao()
            val eventJson = StreamPerformanceDiagnostics.withAttribution(attribution) {
                StreamPerformanceDiagnostics.measure("runtime.checkpoint.encode") { AgentEventJsonCodec.encode(event) }
            }
            val entity = RuntimeInFlightEventEntity(
                runId = runId,
                sortIndex = sortIndex,
                eventJson = eventJson,
            )
            val started = if (diagnose) System.nanoTime() else 0L
            try {
                // This is suspend DAO caller wall time, not a claim of pure disk time.
                dao.appendInFlightEvent(event = entity, updatedAt = now)
            } finally {
                if (diagnose) {
                    val elapsed = System.nanoTime() - started
                    StreamPerformanceDiagnostics.withAttribution(attribution) {
                        StreamPerformanceDiagnostics.record("runtime.checkpoint.write", elapsed, eventJson.length.toLong())
                    }
                }
            }
        }
    }

    /** 返回所有未确认 run；是否 active 或已完成由恢复协调器结合 Runtime 状态判断。 */
    fun list(context: Context): List<Checkpoint> =
        runBlocking(Dispatchers.IO) {
            EtaDatabase.get(context.applicationContext)
                .runtimeRunDao()
                .inFlightRuns()
                .asSequence()
                .map { stored ->
                    Checkpoint(
                        runId = stored.run.runId,
                        ownerInstanceId = stored.run.ownerInstanceId,
                        handoff = AgentRuntimeWire.EntryHandoff(
                            id = stored.run.handoffId,
                            source = stored.run.handoffSource,
                            payload = stored.run.handoffPayload,
                            dismissEntrySurfaceOnForegroundOperation =
                                stored.run.dismissEntrySurface,
                        ),
                        events = stored.events
                            .sortedBy { it.sortIndex }
                            .mapNotNull { AgentEventJsonCodec.decode(it.eventJson) },
                        createdAt = stored.run.createdAt,
                        updatedAt = stored.run.updatedAt,
                    )
                }
                .toList()
        }

    fun remove(context: Context, runId: String) {
        if (runId.isBlank()) return
        runBlocking(Dispatchers.IO) {
            EtaDatabase.get(context.applicationContext)
                .runtimeRunDao()
                .deleteInFlightRun(runId)
        }
    }
}
