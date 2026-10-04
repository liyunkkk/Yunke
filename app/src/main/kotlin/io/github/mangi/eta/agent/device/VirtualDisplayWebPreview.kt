package io.github.mangi.eta.agent.device

import android.app.Application
import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException

/** Explicit pairing outlives settings navigation; only the main app process owns the listener. */
internal object VirtualDisplayWebPreview {
    private var lifecycle: VirtualDisplayPreviewLifecycle? = null

    @Synchronized private fun manager(context: Context): VirtualDisplayPreviewLifecycle {
        val app = context.applicationContext
        check(Application.getProcessName() == app.packageName) { "Preview requires main process" }
        return lifecycle ?: VirtualDisplayPreviewLifecycle(
            PairingStore(File(app.noBackupFilesDir, "vd-web-pairing")),
        ) { saved, control ->
            VirtualDisplayPreviewHttpServer(
                discover = { VirtualDisplaySession.previewDisplays(app) },
                capture = { selected -> VirtualDisplaySession.previewFrame(app, selected) },
                prepareClose = if (control) { selected ->
                    VirtualDisplaySession.prepareManualClose(app, selected)
                } else null,
                commitClose = if (control) { selected, nonce ->
                    VirtualDisplaySession.commitManualClose(app, selected, nonce)
                } else null,
                resumeTicket = saved,
            )
        }.also { lifecycle = it }
    }

    fun open(context: Context): String = manager(context).open(control = false).viewerUri
    fun openWithManualClose(context: Context): String = manager(context).open(control = true).viewerUri
    fun restore(context: Context) = manager(context).restore()
    fun hasPairing(context: Context): Boolean = manager(context).hasPairing()
    fun revoke(context: Context) = manager(context).revoke()
    @Synchronized fun isRunning(): Boolean = lifecycle?.isRunning() == true

    /** Host-local capabilities must not be exported by Android backup or ordinary prefs backup. */
    private class PairingStore(private val base: File) : VirtualDisplayPreviewLifecycle.Store {
        private val file = AtomicFile(base)
        override fun read(): VirtualDisplayPreviewHttpServer.Ticket? {
            return try {
                file.openRead().use { input ->
                    val buffer = ByteArray(VirtualDisplayPreviewPairing.MAX_BYTES + 1)
                    var count = 0
                    while (count < buffer.size) {
                        val n = input.read(buffer, count, buffer.size - count)
                        if (n < 0) break
                        count += n
                    }
                    check(count <= VirtualDisplayPreviewPairing.MAX_BYTES) { "Preview pairing invalid" }
                    checkNotNull(VirtualDisplayPreviewPairing.decode(String(buffer, 0, count, Charsets.UTF_8))) {
                        "Preview pairing invalid"
                    }
                }
            } catch (ex: FileNotFoundException) {
                // Unreadable or interrupted records are unknown, not an absence of authority.
                if (listOf(base, File(base.path + ".bak"), File(base.path + ".new")).any { it.exists() }) throw ex
                null
            }
        }
        override fun write(ticket: VirtualDisplayPreviewHttpServer.Ticket) {
            val bytes = VirtualDisplayPreviewPairing.encode(ticket).toByteArray(Charsets.UTF_8)
            val output = file.startWrite()
            var committed = false
            try {
                output.write(bytes)
                file.finishWrite(output)
                committed = true
                check(read() == ticket) { "Preview pairing save failed" }
            } catch (ex: Exception) {
                if (!committed) file.failWrite(output)
                // Lifecycle also removes a possibly committed base record on any save failure.
                throw ex
            }
        }
        override fun delete() {
            file.delete()
            check(listOf(base, File(base.path + ".bak"), File(base.path + ".new")).none { it.exists() }) {
                "Preview pairing revocation failed"
            }
        }
    }
}
