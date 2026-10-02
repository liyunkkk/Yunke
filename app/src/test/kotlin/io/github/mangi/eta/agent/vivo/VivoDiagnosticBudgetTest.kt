package io.github.mangi.eta.agent.vivo

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class VivoDiagnosticBudgetTest {
    @Test fun noisyStageDoesNotSpendAnotherStagesAllowance() {
        val budget = VivoDiagnosticBudget(3, totalLimit = 80, perStageLimit = 4)
        for (n in 1..4) assertEquals(n, budget.claim(0))
        repeat(10000) { assertNull(budget.claim(0)) }
        assertEquals(5, budget.claim(1))
        assertNull(budget.claim(-1))
        assertNull(budget.claim(3))
        assertEquals(6, budget.claim(2))
    }

    @Test fun globalAllowanceIsNeverExceeded() {
        val budget = VivoDiagnosticBudget(30, totalLimit = 80, perStageLimit = 4)
        val admitted = (0 until 30).flatMap { stage -> (0 until 8).mapNotNull { budget.claim(stage) } }
        assertEquals((1..80).toList(), admitted)
        repeat(100) { assertNull(budget.claim(29)) }
    }

    @Test fun concurrentStagesShareOneExactGlobalBudget() {
        val budget = VivoDiagnosticBudget(30, totalLimit = 80, perStageLimit = 4)
        val start = CountDownLatch(1)
        val counts = Array(30) { AtomicInteger() }
        val threads = counts.indices.map { stage ->
            Thread {
                start.await()
                repeat(50) { if (budget.claim(stage) != null) counts[stage].incrementAndGet() }
            }.also { it.start() }
        }
        start.countDown()
        threads.forEach { it.join(5000) }
        assertTrue(threads.none { it.isAlive })
        assertEquals(80, counts.sumOf { it.get() })
        assertTrue(counts.all { it.get() <= 4 })
    }
}
