package io.github.mangi.eta.agent.runtime

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/** App-private, append-only ownership bindings; workspace metadata is not an authority. */
internal class AgentWorkspaceOwnershipStore(
    private val filesDir: File,
    private val readAttributes: (Path) -> BasicFileAttributes = { path ->
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    },
) {
    private data class Workspace(val environment: String, val project: String, val id: String)

    fun ids(ownerId: String, environment: String, project: String): Set<String> {
        validateOwner(ownerId)
        validateScope(environment, project)
        return locked { read().filter { (workspace, owner) ->
            owner == ownerId && workspace.environment == environment && workspace.project == project
        }.keys.mapTo(linkedSetOf()) { it.id } }
    }

    fun remember(ownerId: String, environment: String, project: String, workspaceId: String) =
        rememberAll(ownerId, environment, project, setOf(workspaceId))

    fun rememberAll(ownerId: String, environment: String, project: String, workspaceIds: Set<String>) {
        validateOwner(ownerId)
        validateScope(environment, project)
        require(workspaceIds.size <= MAX_ENTRIES && workspaceIds.all(::validId)) {
            "Invalid workspace IDs"
        }
        locked {
            val entries = read()
            val requested = workspaceIds.map { Workspace(environment, project, it) }
            check(requested.none { entries[it] != null && entries[it] != ownerId }) {
                "Workspace ownership conflict"
            }
            val additions = requested.filterNot(entries::containsKey)
            if (additions.isNotEmpty()) {
                check(entries.size + additions.size <= MAX_ENTRIES) { "Workspace ownership ledger limit exceeded" }
                additions.forEach { entries[it] = ownerId }
                write(entries)
            }
        }
    }

    private fun read(): LinkedHashMap<Workspace, String> {
        val path = filesDir.toPath().resolve(LEDGER_NAME)
        val attrs = try {
            readAttributes(path)
        } catch (_: NoSuchFileException) {
            return linkedMapOf()
        }
        if (!attrs.isRegularFile || attrs.size() > MAX_BYTES) invalid()
        val bytes = Files.readAllBytes(path)
        if (bytes.size > MAX_BYTES) invalid()
        try {
            val text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
            val root = JSONObject(text)
            if (root.keys().asSequence().toSet() != setOf("version", "entries") || root.opt("version") != VERSION) invalid()
            val records = root.get("entries") as? JSONArray ?: invalid()
            if (records.length() > MAX_ENTRIES) invalid()
            val entries = linkedMapOf<Workspace, String>()
            for (index in 0 until records.length()) {
                val item = records.get(index) as? JSONObject ?: invalid()
                if (item.keys().asSequence().toSet() != setOf("environment", "project", "id", "owner")) invalid()
                val environment = item.get("environment") as? String ?: invalid()
                val project = item.get("project") as? String ?: invalid()
                val id = item.get("id") as? String ?: invalid()
                val owner = item.get("owner") as? String ?: invalid()
                if (!validOwner(owner) || !validScope(environment, project) || !validId(id) ||
                    entries.put(Workspace(environment, project, id), owner) != null) invalid()
            }
            return entries
        } catch (_: Exception) {
            invalid()
        }
    }

    private fun write(entries: Map<Workspace, String>) {
        val records = JSONArray()
        entries.forEach { (workspace, owner) ->
            records.put(JSONObject().put("environment", workspace.environment)
                .put("project", workspace.project).put("id", workspace.id).put("owner", owner))
        }
        val bytes = JSONObject().put("version", VERSION).put("entries", records)
            .toString().toByteArray(StandardCharsets.UTF_8)
        check(bytes.size <= MAX_BYTES) { "Workspace ownership ledger limit exceeded" }
        val directory = filesDir.toPath()
        val temp = Files.createTempFile(directory, "workspace-ownership-", ".tmp")
        try {
            Files.newByteChannel(temp, StandardOpenOption.WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                (channel as java.nio.channels.FileChannel).force(true)
            }
            Files.move(temp, directory.resolve(LEDGER_NAME), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private inline fun <T> locked(block: () -> T): T = synchronized(processLock) {
        try {
            val directory = filesDir.toPath()
            check(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                "Workspace ownership ledger directory unavailable"
            }
            val lockPath = directory.resolve("$LEDGER_NAME.lock")
            check(!Files.isSymbolicLink(lockPath)) { "Workspace ownership ledger lock unavailable" }
            java.nio.channels.FileChannel.open(lockPath, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE).use { channel ->
                channel.lock().use { block() }
            }
        } catch (_: IOException) {
            throw IllegalStateException("Workspace ownership ledger I/O failed")
        } catch (_: SecurityException) {
            throw IllegalStateException("Workspace ownership ledger access failed")
        } catch (_: java.nio.channels.OverlappingFileLockException) {
            throw IllegalStateException("Workspace ownership ledger lock failed")
        }
    }

    private fun validateOwner(owner: String) = require(validOwner(owner)) { "Invalid workspace owner" }
    private fun validateScope(environment: String, project: String) =
        require(validScope(environment, project)) { "Invalid workspace scope" }

    private fun validOwner(owner: String) = owner.isNotBlank() && owner.length <= 256 &&
        owner.none(Char::isISOControl)
    private fun validScope(environment: String, project: String) =
        environment in setOf("debian", "alpine") && project.length <= 512 &&
            project.startsWith("/workspace/") &&
            project.substringAfter("/workspace/").let { name ->
                name.isNotEmpty() && name != "." && name != ".." &&
                    '/' !in name && name.none(Char::isISOControl)
            }
    private fun validId(id: String) = id.length == 32 && id.all { it in '0'..'9' || it in 'a'..'f' }
    private fun invalid(): Nothing = throw IllegalStateException("Workspace ownership ledger invalid or unsupported")

    private companion object {
        const val LEDGER_NAME = "agent-workspace-ownership.json"
        const val VERSION = 1
        const val MAX_ENTRIES = 4096
        const val MAX_BYTES = 2 * 1024 * 1024
        val processLock = Any()
    }
}
