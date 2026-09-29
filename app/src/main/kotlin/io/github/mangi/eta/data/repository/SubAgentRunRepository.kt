package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.agent.delegation.SubAgentSample
import io.github.mangi.eta.agent.delegation.SubAgentScope
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.SubAgentRunEntity
import kotlinx.coroutines.runBlocking

/**
 * 子代理消耗样本读写。
 *
 * 读取按档位取最近若干条用于预算估算；写入后按总条数裁剪，保持滑动窗口不无限增长。
 * 只在子代理工作线程调用，使用 runBlocking 与运行时的同步派发保持一致。
 */
internal object SubAgentRunRepository {
    private const val SAMPLE_LIMIT = 64
    private const val KEEP_ROWS = 200

    fun recent(context: Context, scope: SubAgentScope): List<SubAgentSample> = runBlocking {
        EtaDatabase.get(context).subAgentRunDao().recent(scope.wire, SAMPLE_LIMIT)
    }.map { SubAgentSample(tokens = it.tokens, rounds = it.rounds, ok = it.ok) }

    fun record(context: Context, scope: SubAgentScope, sample: SubAgentSample) {
        runBlocking {
            val dao = EtaDatabase.get(context).subAgentRunDao()
            dao.insert(
                SubAgentRunEntity(
                    scope = scope.wire,
                    tokens = sample.tokens,
                    rounds = sample.rounds,
                    ok = sample.ok,
                    createdAt = System.currentTimeMillis(),
                )
            )
            dao.trim(KEEP_ROWS)
        }
    }
}
