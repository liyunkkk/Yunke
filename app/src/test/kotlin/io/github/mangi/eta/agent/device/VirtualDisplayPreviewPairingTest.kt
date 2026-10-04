package io.github.mangi.eta.agent.device

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class VirtualDisplayPreviewPairingTest {
    private val read = "a".repeat(64)
    private val control = "b".repeat(64)

    @Test fun codecRoundTripsReadAndControlWithoutLoggingCapabilities() {
        for (token in listOf(null, control)) {
            val ticket = VirtualDisplayPreviewHttpServer.Ticket(45678, read, token)
            assertEquals(ticket, VirtualDisplayPreviewPairing.decode(VirtualDisplayPreviewPairing.encode(ticket)))
            assertFalse(ticket.toString().contains(read))
            assertFalse(ticket.toString().contains(control))
        }
    }

    @Test fun codecRejectsDamagedVersionsPortsTokensAndOversizedInput() {
        val valid = VirtualDisplayPreviewPairing.encode(VirtualDisplayPreviewHttpServer.Ticket(45678, read, control))
        val invalid = listOf("", valid.replaceFirst("1\n", "2\n"), valid + "extra\n",
            valid.replace("45678", "0"), valid.replace("45678", "3070"), valid.replace("45678", "65536"),
            valid.replace("45678", "045678"), valid.replace("45678", "+45678"),
            valid.replace(read, "short"), valid.replace(control, read), valid.replace(control, "B".repeat(64)),
            valid.replace("\n", "\r\n"), valid.dropLast(1), "a".repeat(513))
        invalid.forEach { assertNull(VirtualDisplayPreviewPairing.decode(it)) }
    }

    private class MemoryStore : VirtualDisplayPreviewLifecycle.Store {
        var saved: VirtualDisplayPreviewHttpServer.Ticket? = null
        var writeFails = false
        var writeThenFail = false
        var readFails = false
        var deleteFails = false
        var onRead: (() -> Unit)? = null
        override fun read(): VirtualDisplayPreviewHttpServer.Ticket? { onRead?.invoke(); check(!readFails); return saved }
        override fun write(ticket: VirtualDisplayPreviewHttpServer.Ticket) {
            check(!writeFails); saved = ticket; check(!writeThenFail)
        }
        override fun delete() { check(!deleteFails); saved = null }
    }

    private class Fixture(val store: MemoryStore = MemoryStore()) : AutoCloseable {
        val servers = mutableListOf<VirtualDisplayPreviewHttpServer>()
        var callbacks = 0
        fun manager() = VirtualDisplayPreviewLifecycle(store) { saved, control ->
            VirtualDisplayPreviewHttpServer(
                discover = { callbacks++; VirtualDisplayPreviewHttpServer.Displays() },
                capture = { callbacks++; VirtualDisplayPreviewHttpServer.Frame(error = "NO_VIRTUAL_SESSION") },
                prepareClose = if (control) { _ -> VirtualDisplayManualClose.Result("blocked") } else null,
                commitClose = if (control) { _, _ -> VirtualDisplayManualClose.Result("blocked") } else null,
                resumeTicket = saved,
            ).also { servers.add(it) }
        }
        override fun close() { servers.forEach { it.stop() } }
    }

    @Test fun restoreWithoutExplicitPairingDoesNothing() = Fixture().use { f ->
        val manager = f.manager()
        manager.restore()
        assertFalse(manager.isRunning())
        assertFalse(manager.hasPairing())
        assertEquals(0, f.servers.size)
        assertEquals(0, f.callbacks)
    }

    @Test fun repeatOpenReusesListenerAndPermissionChangesRotateAllCapabilities() = Fixture().use { f ->
        val manager = f.manager()
        val readOnly = manager.open(false)
        assertNull(readOnly.controlToken)
        assertEquals(readOnly, manager.open(false))
        assertEquals(1, f.servers.size)
        val controlled = manager.open(true)
        assertNotEquals(readOnly.token, controlled.token)
        assertNotNull(controlled.controlToken)
        assertFalse(f.servers[0].isRunning())
        assertEquals(controlled, manager.open(true))
        val downgraded = manager.open(false)
        assertNotEquals(controlled.token, downgraded.token)
        assertNull(downgraded.controlToken)
        assertFalse(f.servers[1].isRunning())
        assertEquals(3, f.servers.size)
        assertEquals(0, f.callbacks)
    }

    @Test fun processRestartRestoresExactPortAndCredentialsWithoutTouchingDisplay() = Fixture().use { f ->
        val first = f.manager()
        val saved = first.open(true)
        f.servers.single().stop() // Simulate process exit, not authorization revocation.
        val nextProcess = f.manager()
        nextProcess.restore()
        assertTrue(nextProcess.isRunning())
        assertEquals(saved, nextProcess.open(true))
        assertEquals(saved, f.store.saved)
        assertEquals(0, f.callbacks)
    }

    @Test fun occupiedPairingPortFailsClosedAndNeverFallsBack() = Fixture().use { f ->
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { occupied ->
            f.store.saved = VirtualDisplayPreviewHttpServer.Ticket(occupied.localPort, read, null)
            val manager = f.manager()
            assertTrue(runCatching { manager.restore() }.isFailure)
            assertFalse(manager.isRunning())
            assertTrue(manager.hasPairing())
            assertEquals(occupied.localPort, f.store.saved!!.port)
            assertTrue(f.servers.none { it.isRunning() })
        }
    }

    @Test fun revokeClearsPairingAndCannotBeUndoneByRestore() = Fixture().use { f ->
        val manager = f.manager()
        manager.open(true)
        manager.revoke()
        assertFalse(manager.isRunning())
        assertFalse(manager.hasPairing())
        manager.restore()
        f.manager().restore()
        assertEquals(1, f.servers.size)
        assertEquals(0, f.callbacks)
    }

    @Test fun saveFailureStopsListenerAndNeverReturnsSuccessTicket() = Fixture().use { f ->
        f.store.writeFails = true
        val manager = f.manager()
        assertTrue(runCatching { manager.open(true) }.isFailure)
        assertFalse(manager.isRunning())
        assertNull(f.store.saved)
        assertTrue(f.servers.none { it.isRunning() })
    }

    @Test fun failedDeletionIsReportedAndCannotRestartInThisProcess() = Fixture().use { f ->
        val manager = f.manager()
        manager.open(true)
        f.store.deleteFails = true
        assertTrue(runCatching { manager.revoke() }.isFailure)
        assertFalse(manager.isRunning())
        assertTrue(manager.hasPairing())
        manager.restore()
        assertFalse(manager.isRunning())
        assertEquals(1, f.servers.size)
        f.store.deleteFails = false
        manager.revoke()
        assertNull(f.store.saved)
    }

    @Test fun restoreAndRevokeAreSerializedSoLateRestoreCannotResurrectPairing() = Fixture().use { f ->
        val initial = f.manager().open(false)
        f.servers.single().stop()
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        f.store.onRead = { entered.countDown(); check(proceed.await(3, TimeUnit.SECONDS)) }
        val manager = f.manager()
        val restore = Thread { manager.restore() }
        restore.start()
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        val revoke = Thread { manager.revoke() }
        revoke.start()
        proceed.countDown()
        restore.join(3000); revoke.join(3000)
        assertFalse(restore.isAlive); assertFalse(revoke.isAlive)
        assertFalse(manager.isRunning()); assertNull(f.store.saved)
        assertTrue(f.servers.none { it.isRunning() })
        assertTrue(initial.port > 0)
    }

    @Test fun restoredReadOnlyCapabilityCannotAcquireControlCallbacks() {
        val ticket = VirtualDisplayPreviewHttpServer.Ticket(45678, read)
        assertTrue(runCatching {
            VirtualDisplayPreviewHttpServer(
                discover = { VirtualDisplayPreviewHttpServer.Displays() },
                capture = { VirtualDisplayPreviewHttpServer.Frame() },
                prepareClose = { VirtualDisplayManualClose.Result("blocked") },
                commitClose = { _, _ -> VirtualDisplayManualClose.Result("blocked") },
                resumeTicket = ticket,
            )
        }.isFailure)
    }
    @Test fun failedSaveAfterCommitRemovesPersistedAuthorityAndCannotRestore() = Fixture().use { f ->
        f.store.writeThenFail = true
        val manager = f.manager()
        assertTrue(runCatching { manager.open(true) }.isFailure)
        assertNull(f.store.saved)
        manager.restore(); f.manager().restore()
        assertFalse(manager.isRunning())
        assertEquals(1, f.servers.size)
    }

    @Test fun failedCleanupIsDistinctAndNeverRestoresInThisProcess() = Fixture().use { f ->
        f.store.writeThenFail = true; f.store.deleteFails = true
        val manager = f.manager()
        val result = runCatching { manager.open(true) }
        assertTrue(result.exceptionOrNull() is VirtualDisplayPreviewLifecycle.PersistenceCleanupException)
        assertTrue(manager.hasPairing())
        manager.restore()
        assertFalse(manager.isRunning())
        assertEquals(1, f.servers.size)
        f.store.deleteFails = false
        manager.revoke()
        assertNull(f.store.saved)
    }

    @Test fun diskReadFailureOrMissingFileCannotHideRevocationForLiveAuthority() = Fixture().use { f ->
        val manager = f.manager()
        manager.open(true)
        f.store.saved = null
        assertTrue(manager.hasPairing())
        f.store.readFails = true
        assertTrue(manager.hasPairing())
        assertTrue(f.manager().hasPairing()) // Unknown disk state must also retain the revoke action.
        manager.revoke()
        assertFalse(manager.isRunning())
        f.store.readFails = false
        assertFalse(manager.hasPairing())
    }

}
