package io.github.mangi.eta.agent.skill

import android.content.Context
import android.content.res.AssetManager
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.AssistantStorage
import io.github.mangi.eta.data.db.SkillRegistryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

private const val BUILTIN_SKILL_MANIFEST_ASSET = "builtin_skills/manifest.json"
internal const val BUILTIN_SKILL_SOURCE = "builtin"
internal const val USER_SKILL_SOURCE = "user"
private const val BUILTIN_SOURCE = BUILTIN_SKILL_SOURCE
private const val USER_SOURCE = USER_SKILL_SOURCE
private const val INSTALL_STATE_INSTALLED = "installed"
private const val INSTALL_STATE_REMOVED_BUILTIN = "removed_builtin"

internal fun isSafeBuiltinSkillInstallation(targetDir: File): Boolean {
    val skillFile = File(targetDir, "SKILL.md")
    return !Files.isSymbolicLink(targetDir.toPath()) &&
        !Files.isSymbolicLink(skillFile.toPath()) &&
        skillFile.isFile
}

/** 内置技能 manifest 条目。 */
private data class BuiltinSkillAsset(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val assetPath: String = "",
    val hasScripts: Boolean = false,
    val hasReferences: Boolean = false,
    val hasAssets: Boolean = false,
    val hasEvals: Boolean = false,
)

/** 注册表中的技能状态记录。 */
private data class SkillRegistryEntry(
    val enabled: Boolean = true,
    val source: String = USER_SOURCE,
    val installState: String = INSTALL_STATE_INSTALLED,
)

// =====================================================================================
// SkillRegistryStore — 持久化技能安装元数据；技能正文仍保留在文件树中。
// =====================================================================================

private class SkillRegistryStore(
    context: Context,
) {
    private val appContext = context.applicationContext

    fun read(): LinkedHashMap<String, SkillRegistryEntry> {
        return runCatching { readStrict() }.getOrElse { linkedMapOf() }
    }

    fun readStrict(): LinkedHashMap<String, SkillRegistryEntry> = runBlocking(Dispatchers.IO) {
        EtaDatabase.get(appContext)
            .skillDao()
            .registryEntries()
            .associateTo(linkedMapOf()) { entity ->
                entity.skillId to SkillRegistryEntry(
                    enabled = entity.enabled,
                    source = entity.source.ifBlank { USER_SOURCE },
                    installState = entity.installState.ifBlank { INSTALL_STATE_INSTALLED },
                )
            }
    }

    fun write(entries: Map<String, SkillRegistryEntry>) {
        runBlocking(Dispatchers.IO) {
            EtaDatabase.get(appContext)
                .skillDao()
                .replaceRegistry(
                    entries.toSortedMap().map { (skillId, value) ->
                        SkillRegistryEntity(
                            skillId = skillId,
                            enabled = value.enabled,
                            source = value.source,
                            installState = value.installState,
                        )
                    }
                )
        }
    }

    fun set(skillId: String, entry: SkillRegistryEntry) {
        runBlocking(Dispatchers.IO) {
            EtaDatabase.get(appContext)
                .skillDao()
                .upsertRegistryEntry(
                    SkillRegistryEntity(
                        skillId = skillId,
                        enabled = entry.enabled,
                        source = entry.source,
                        installState = entry.installState,
                    )
                )
        }
    }

    fun remove(skillId: String) {
        runBlocking(Dispatchers.IO) {
            EtaDatabase.get(appContext)
                .skillDao()
                .deleteRegistryEntry(skillId)
        }
    }
}

// =====================================================================================
// BuiltinSkillAssetStore — 从 assets 读取并安装内置技能
// =====================================================================================

private class BuiltinSkillAssetStore(
    private val context: Context,
    private val skillsRoot: File,
) {
    private val builtins: List<BuiltinSkillAsset> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching {
            context.assets.open(BUILTIN_SKILL_MANIFEST_ASSET).bufferedReader().use { reader ->
                val json = JSONObject(reader.readText())
                val arr = json.optJSONArray("skills") ?: return@runCatching emptyList()
                (0 until arr.length()).mapNotNull { i ->
                    val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                    BuiltinSkillAsset(
                        id = obj.optString("id"),
                        name = obj.optString("name"),
                        description = obj.optString("description"),
                        assetPath = obj.optString("assetPath"),
                        hasScripts = obj.optBoolean("hasScripts"),
                        hasReferences = obj.optBoolean("hasReferences"),
                        hasAssets = obj.optBoolean("hasAssets"),
                        hasEvals = obj.optBoolean("hasEvals"),
                    )
                }.filter { it.id.isNotBlank() && it.assetPath.isNotBlank() }
            }
        }.getOrElse { emptyList() }
    }

    fun listBuiltins(): List<BuiltinSkillAsset> = builtins

    fun findBuiltin(skillId: String): BuiltinSkillAsset? =
        listBuiltins().firstOrNull { it.id == skillId }

    fun seedMissingBuiltins(registryStore: SkillRegistryStore) {
        val registry = registryStore.read()
        var changed = false
        listBuiltins().forEach { builtin ->
            val entry = registry[builtin.id]
            if (entry?.installState == INSTALL_STATE_REMOVED_BUILTIN) return@forEach
            if (entry?.source == USER_SOURCE) return@forEach
            val targetDir = File(skillsRoot, builtin.id)
            if (!isSafeBuiltinSkillInstallation(targetDir)) {
                installBuiltinInternal(builtin)
            }
            if (entry?.source == BUILTIN_SOURCE && entry.installState == INSTALL_STATE_INSTALLED) {
                return@forEach
            }
            registry[builtin.id] = SkillRegistryEntry(
                enabled = true,
                source = BUILTIN_SOURCE,
                installState = INSTALL_STATE_INSTALLED,
            )
            changed = true
        }
        if (changed) registryStore.write(registry)
    }

    fun installBuiltin(skillId: String, registryStore: SkillRegistryStore) {
        val builtin = findBuiltin(skillId)
            ?: throw IllegalArgumentException("未找到内置 skill：$skillId")
        installBuiltinInternal(builtin)
        registryStore.set(
            skillId,
            SkillRegistryEntry(enabled = true, source = BUILTIN_SOURCE, installState = INSTALL_STATE_INSTALLED),
        )
    }

    private fun installBuiltinInternal(builtin: BuiltinSkillAsset) {
        val targetDir = File(skillsRoot, builtin.id)
        if (Files.exists(targetDir.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            if (!deleteSkillPathWithoutFollowingLinks(skillsRoot, targetDir)) {
                error("无法安全清理内置 Skill 目录：${builtin.id}")
            }
        }
        copyAssetRecursively(context.assets, builtin.assetPath, targetDir)
    }

    private fun copyAssetRecursively(assetManager: AssetManager, assetPath: String, target: File) {
        val children = assetManager.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            assetManager.open(assetPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        if (!target.exists()) target.mkdirs()
        children.forEach { child ->
            copyAssetRecursively(assetManager, "$assetPath/$child", File(target, child))
        }
    }
}

// =====================================================================================
// SkillIndexService — 扫描、索引、管理技能
// =====================================================================================

class SkillIndexService(
    private val context: Context,
    internal val skillsRoot: File,
) {
    private val indexLock = Any()
    private val registryStore = SkillRegistryStore(context.applicationContext)
    private val builtinStore = BuiltinSkillAssetStore(context.applicationContext, skillsRoot)

    @Volatile
    private var builtinsSeeded = false

    @Volatile
    private var cachedManagementEntries: List<SkillIndexEntry>? = null

    /**
     * 所有可读写索引的入口都先在同一把跨进程锁内完成待处理文件事务与 Room 快照恢复。
     * 仅能读取文件、不具备 registry 恢复能力的 Loader 会由锁实现保持 fail-closed。
     */
    internal fun <T> withMutationLock(block: () -> T): T = SkillMutationLock.withLock(
        skillsRoot = skillsRoot,
        recoveryHandler = ::restoreRecoveredRegistry,
        block = block,
    )

    fun seedBuiltinSkillsIfNeeded() {
        withMutationLock {
            if (builtinsSeeded) return@withMutationLock
            synchronized(indexLock) {
                seedBuiltinSkillsLocked()
            }
        }
    }

    fun listSkillsForManagement(forceRefresh: Boolean = false): List<SkillIndexEntry> =
        withMutationLock {
            synchronized(indexLock) {
                if (forceRefresh) builtinsSeeded = false
                seedBuiltinSkillsLocked()
                if (forceRefresh) cachedManagementEntries = null
                cachedManagementEntries ?: buildManagementEntries().also {
                    cachedManagementEntries = it
                }
            }
        }

    fun listInstalledSkills(): List<SkillIndexEntry> =
        listSkillsForManagement().filter { it.installed }

    fun findInstalledSkill(identifier: String): SkillIndexEntry? {
        val normalized = SkillParser.normalizeSkillLookup(identifier)
        if (normalized.isBlank()) return null
        val entries = listSkillsForManagement().filter { it.installed }
        return entries.firstOrNull { SkillParser.normalizeSkillLookup(it.id) == normalized }
            ?: entries.firstOrNull { SkillParser.normalizeSkillLookup(it.name) == normalized }
            ?: entries.firstOrNull { SkillParser.normalizeSkillLookup(it.skillFilePath) == normalized }
            ?: entries.firstOrNull { SkillParser.normalizeSkillLookup(it.rootPath) == normalized }
    }

    fun setSkillEnabled(skillId: String, enabled: Boolean): SkillIndexEntry {
        return withMutationLock {
            synchronized(indexLock) {
                val entry = listSkillsForManagement().firstOrNull { it.id == skillId && it.installed }
                    ?: throw IllegalArgumentException("未找到已安装 skill：$skillId")
                registryStore.set(
                    entry.id,
                    SkillRegistryEntry(enabled = enabled, source = entry.source, installState = INSTALL_STATE_INSTALLED),
                )
                invalidateIndexLocked()
                entry.copy(enabled = enabled)
            }
        }
    }

    fun deleteSkill(skillId: String): Boolean {
        return withMutationLock {
            synchronized(indexLock) {
                val entry = listSkillsForManagement().firstOrNull { it.id == skillId && it.installed }
                    ?: return@synchronized false
                val targetDir = managedSkillDirectory(entry) ?: return@synchronized false
                val registrySnapshot = captureRegistryRecoverySnapshots(listOf(entry.id)).single()
                val operation = runCatching {
                    createSkillRecoveryOperationDirectory(skillsRoot)
                }.getOrElse { return@synchronized false }
                val workRoot = skillInstallerWorkRoot(skillsRoot)
                val backupRoot = File(operation, "backup")
                if (!backupRoot.mkdir()) {
                    deleteSkillPathWithoutFollowingLinks(workRoot, operation)
                    return@synchronized false
                }
                val backupDir = File(backupRoot, entry.id)
                var registryMutationStarted = false
                try {
                    val journal = PendingSkillRecoveryJournal.begin(
                        skillsRoot = skillsRoot,
                        operationDirectory = operation,
                        records = listOf(
                            SkillRecoveryRecord(
                                id = entry.id,
                                originalTargetExisted = true,
                                registrySnapshot = registrySnapshot,
                            )
                        ),
                    )
                    moveSkillDirectoryAtomically(targetDir, backupDir)
                    journal.markBackupCompleted(entry.id)
                    registryMutationStarted = true
                    if (entry.source == BUILTIN_SOURCE) {
                        registryStore.set(
                            entry.id,
                            SkillRegistryEntry(
                                enabled = false,
                                source = BUILTIN_SOURCE,
                                installState = INSTALL_STATE_REMOVED_BUILTIN,
                            ),
                        )
                    } else {
                        registryStore.remove(entry.id)
                    }
                    invalidateIndexLocked()
                    journal.clear()
                    true
                } catch (error: Exception) {
                    val journalExists = Files.exists(
                        File(operation, JOURNAL_FILE_NAME).toPath(),
                        LinkOption.NOFOLLOW_LINKS,
                    )
                    val rollbackComplete = !journalExists || runCatching {
                        val recovered = recoverPendingSkillOperations(skillsRoot)
                        restoreRecoveredRegistry(recovered)
                        completeRecoveredSkillOperations(skillsRoot, recovered)
                    }.isSuccess
                    if (!rollbackComplete) {
                        throw SkillRecoveryRequiredException(
                            "Skill 删除失败，且文件或 registry 尚未完整恢复",
                            error,
                        )
                    }
                    invalidateIndexLocked()
                    if (registryMutationStarted) throw error
                    false
                } finally {
                    if (!Files.exists(
                            File(operation, JOURNAL_FILE_NAME).toPath(),
                            LinkOption.NOFOLLOW_LINKS,
                        )
                    ) {
                        // 成功删除时备份内可能含历史 symlink，必须只 unlink，不能递归跟随。
                        deleteSkillPathWithoutFollowingLinks(workRoot, operation)
                    }
                }
            }
        }
    }

    fun installBuiltinSkill(skillId: String): SkillIndexEntry {
        return withMutationLock {
            synchronized(indexLock) {
                builtinStore.installBuiltin(skillId, registryStore)
                invalidateIndexLocked()
                findInstalledSkill(skillId)
                    ?: throw IllegalStateException("安装内置 skill 后索引失败：$skillId")
            }
        }
    }

    /** 文件提交成功后，以单次 Room 事务登记用户 Skill，并同步清除索引缓存。 */
    internal fun registerInstalledUserSkills(skillIds: List<String>) {
        withMutationLock {
            synchronized(indexLock) {
                require(skillIds.none { builtinStore.findBuiltin(it) != null }) {
                    "不能把内置 Skill 登记为用户 Skill"
                }
                val registry = registryStore.readStrict()
                skillIds.distinct().forEach { skillId ->
                    registry[skillId] = SkillRegistryEntry(
                        enabled = true,
                        source = USER_SOURCE,
                        installState = INSTALL_STATE_INSTALLED,
                    )
                }
                registryStore.write(registry)
                invalidateIndexLocked()
            }
        }
    }

    /** 在正式目录发生任何移动前，持久化事务涉及 id 的完整旧 registry 状态。 */
    internal fun captureRegistryRecoverySnapshots(
        skillIds: List<String>,
    ): List<SkillRegistryRecoverySnapshot> = synchronized(indexLock) {
        val registry = registryStore.readStrict()
        skillIds.distinct().map { skillId ->
            val entry = registry[skillId]
            if (entry == null) {
                SkillRegistryRecoverySnapshot(skillId = skillId, entryExisted = false)
            } else {
                SkillRegistryRecoverySnapshot(
                    skillId = skillId,
                    entryExisted = true,
                    enabled = entry.enabled,
                    source = entry.source,
                    installState = entry.installState,
                )
            }
        }
    }

    /** 文件恢复完成后，以单次 Room 事务还原全部旧快照；失败时调用方保留 journal。 */
    internal fun restoreRecoveredRegistry(recovered: List<RecoveredSkillOperation>) {
        if (recovered.isEmpty()) return
        synchronized(indexLock) {
            val registry = registryStore.readStrict()
            recovered.flatMap { it.records }.forEach { record ->
                val snapshot = record.registrySnapshot
                if (snapshot.entryExisted) {
                    registry[snapshot.skillId] = SkillRegistryEntry(
                        enabled = snapshot.enabled,
                        source = snapshot.source,
                        installState = snapshot.installState,
                    )
                } else {
                    registry.remove(snapshot.skillId)
                }
            }
            registryStore.write(registry)
            invalidateIndexLocked()
        }
    }

    internal fun isBuiltinSkillId(skillId: String): Boolean =
        synchronized(indexLock) { builtinStore.findBuiltin(skillId) != null }

    private fun seedBuiltinSkillsLocked() {
        if (builtinsSeeded) return
        if (!skillsRoot.exists() && !skillsRoot.mkdirs()) {
            error("无法创建 Skills 目录：${skillsRoot.absolutePath}")
        }
        builtinStore.seedMissingBuiltins(registryStore)
        builtinsSeeded = true
        invalidateIndexLocked()
    }

    private fun buildManagementEntries(): List<SkillIndexEntry> {
        val registry = registryStore.read()
        val builtinAssets = builtinStore.listBuiltins().associateBy { it.id }
        val installed = scanInstalledEntries(registry, builtinAssets)
        val installedIds = installed.mapTo(mutableSetOf()) { it.id }
        val removedBuiltins = builtinAssets.values
            .asSequence()
            .filter { it.id !in installedIds && registry[it.id]?.installState == INSTALL_STATE_REMOVED_BUILTIN }
            .map { buildBuiltinPlaceholder(it, registry[it.id]) }
            .toList()
        return (installed + removedBuiltins).sortedWith(
            compareByDescending<SkillIndexEntry> { it.installed }
                .thenBy { sourceRank(it.source) }
                .thenBy { it.name.lowercase() },
        )
    }

    private fun invalidateIndexLocked() {
        cachedManagementEntries = null
    }

    private fun scanInstalledEntries(
        registry: Map<String, SkillRegistryEntry>,
        builtinAssets: Map<String, BuiltinSkillAsset>,
    ): List<SkillIndexEntry> {
        if (!skillsRoot.exists()) return emptyList()
        val canonicalRoot = skillsRoot.canonicalFile.toPath()
        return skillsRoot.walkTopDown()
            .onEnter { dir ->
                // `.assistant` / `.visible` 是当前助手的发布拷贝，不能当成又一份已安装 Skill。
                (dir == skillsRoot || !dir.name.startsWith(".")) &&
                    !shouldSkipSkillCopy(dir) &&
                    !Files.isSymbolicLink(dir.toPath()) &&
                    runCatching { dir.canonicalFile.toPath().startsWith(canonicalRoot) }.getOrDefault(false)
            }
            .filter {
                it.isFile && it.name == "SKILL.md" && !Files.isSymbolicLink(it.toPath())
            }
            .mapNotNull { skillFile ->
                buildInstalledEntry(skillFile.parentFile ?: return@mapNotNull null, registry, builtinAssets)
            }
            .distinctBy { it.rootPath }
            .groupBy { it.id }
            .values
            .map { copies ->
                copies.firstOrNull { copy ->
                    File(copy.rootPath).parentFile?.canonicalFile == skillsRoot.canonicalFile
                } ?: copies.first()
            }
            .toList()
    }

    private fun buildInstalledEntry(
        skillDir: File,
        registry: Map<String, SkillRegistryEntry>,
        builtinAssets: Map<String, BuiltinSkillAsset>,
    ): SkillIndexEntry? {
        val canonicalRoot = skillsRoot.canonicalFile.toPath()
        val canonicalDir = skillDir.canonicalFile
        val canonicalPath = canonicalDir.toPath()
        if (!canonicalPath.startsWith(canonicalRoot) || canonicalPath == canonicalRoot) return null
        val skillFile = File(canonicalDir, "SKILL.md")
        if (Files.isSymbolicLink(skillFile.toPath())) return null
        val parsed = SkillParser.parseSkillFile(skillFile) ?: return null
        val frontmatter = parsed.frontmatter
        val id = SkillParser.sanitizeSkillId(canonicalDir.name, frontmatter["name"])
        val metadata = frontmatter["metadata"]?.let { SkillParser.parseIndentedBlock(it) } ?: emptyMap()
        val registryState = registry[id]
        val builtinAsset = builtinAssets[id]
        return SkillIndexEntry(
            id = id,
            name = frontmatter["name"]?.ifBlank { id } ?: id,
            description = frontmatter["description"]?.trim().orEmpty(),
            compatibility = frontmatter["compatibility"]?.trim(),
            metadata = metadata,
            rootPath = canonicalDir.absolutePath,
            skillFilePath = skillFile.absolutePath,
            hasScripts = File(canonicalDir, "scripts").isDirectory,
            hasReferences = File(canonicalDir, "references").isDirectory,
            hasAssets = File(canonicalDir, "assets").isDirectory,
            hasEvals = File(canonicalDir, "evals").isDirectory,
            enabled = registryState?.enabled ?: true,
            source = registryState?.source?.ifBlank { null }
                ?: if (builtinAsset != null) BUILTIN_SOURCE else USER_SOURCE,
            installed = true,
        )
    }

    private fun buildBuiltinPlaceholder(
        builtin: BuiltinSkillAsset,
        registryState: SkillRegistryEntry?,
    ): SkillIndexEntry {
        val targetDir = File(skillsRoot, builtin.id)
        val skillFile = File(targetDir, "SKILL.md")
        return SkillIndexEntry(
            id = builtin.id,
            name = builtin.name.ifBlank { builtin.id },
            description = builtin.description,
            rootPath = targetDir.absolutePath,
            skillFilePath = skillFile.absolutePath,
            hasScripts = builtin.hasScripts,
            hasReferences = builtin.hasReferences,
            hasAssets = builtin.hasAssets,
            hasEvals = builtin.hasEvals,
            enabled = registryState?.enabled ?: false,
            source = BUILTIN_SOURCE,
            installed = false,
        )
    }

    private fun sourceRank(source: String): Int = when (source) {
        BUILTIN_SOURCE -> 0
        else -> 2
    }

    private fun managedSkillDirectory(entry: SkillIndexEntry): File? {
        val canonicalRoot = skillsRoot.canonicalFile.toPath()
        val requested = File(entry.rootPath)
        if (Files.isSymbolicLink(requested.toPath())) return null
        val canonical = runCatching { requested.canonicalFile }.getOrNull() ?: return null
        val candidatePath = canonical.toPath()
        return canonical.takeIf {
            candidatePath.startsWith(canonicalRoot) && candidatePath != canonicalRoot && it.isDirectory
        }
    }
}

// =====================================================================================
// SkillLoader — 加载技能正文和附属资源
// =====================================================================================

class SkillLoader(private val skillsRoot: File) {
    private val canonicalSkillsRoot = skillsRoot.canonicalFile
    private val resourceReader = SkillResourceReader(skillsRoot)

    fun load(entry: SkillIndexEntry, triggerReason: String): ResolvedSkillContext? =
        SkillMutationLock.withLock(
            skillsRoot = skillsRoot,
            recoveryHandler = { },
        ) {
            loadAfterRecovery(entry, triggerReason)
        }

    private fun loadAfterRecovery(
        entry: SkillIndexEntry,
        triggerReason: String,
    ): ResolvedSkillContext? {
        if (!entry.installed) return null
        val requestedRoot = File(entry.rootPath)
        if (Files.isSymbolicLink(requestedRoot.toPath())) return null
        val skillDir = runCatching { requestedRoot.canonicalFile }.getOrNull() ?: return null
        val rootPath = canonicalSkillsRoot.toPath()
        val skillPath = skillDir.toPath()
        if (!skillPath.startsWith(rootPath) || skillPath == rootPath) return null
        val skillFile = File(skillDir, "SKILL.md")
        if (Files.isSymbolicLink(skillFile.toPath())) return null
        val parsed = SkillParser.parseSkillFile(skillFile) ?: return null
        val loadedReferences = when (val resources = resourceReader.listResources(entry, "references")) {
            is SkillResourceListResult.Success -> resources.resources.map { it.relativePath }
            is SkillResourceListResult.Failure -> emptyList()
        }
        return ResolvedSkillContext(
            skillId = entry.id,
            frontmatter = parsed.frontmatter,
            metadata = entry.metadata,
            bodyMarkdown = parsed.body,
            loadedReferences = loadedReferences,
            scriptsDir = File(skillDir, "scripts").takeIf { it.isDirectory }?.absolutePath,
            assetsDir = File(skillDir, "assets").takeIf { it.isDirectory }?.absolutePath,
            triggerReason = triggerReason,
        )
    }
}

// =====================================================================================
// SkillRuntime — 工厂入口
// =====================================================================================

internal const val ASSISTANT_SKILL_DIR = ".assistant"
internal const val VISIBLE_SKILL_DIR = ".visible"

private val skippedSkillCopyNames = setOf(
    "__pycache__",
    ".git",
)

private fun shouldSkipSkillCopy(file: File): Boolean {
    val name = file.name
    return name in skippedSkillCopyNames ||
        name.endsWith(".pyc") ||
        name.endsWith(".pyo")
}

object SkillRuntime {
    private val runLeases = java.util.concurrent.ConcurrentHashMap<String, Pair<java.io.RandomAccessFile, java.nio.channels.FileLock>>()

    @Volatile
    private var sharedIndexService: SkillIndexService? = null

    fun skillsRoot(context: Context): File = File(context.filesDir, "skills")

    fun assistantSkillsDirectory(context: Context, assistantId: String): File =
        File(skillsRoot(context), "$ASSISTANT_SKILL_DIR/${AssistantStorage.id(assistantId)}")

    fun visibleSkillsDirectory(context: Context): File =
        visibleSkillsDirectory(context, io.github.mangi.eta.data.repository.AssistantRepository.active().id)

    fun visibleSkillsDirectory(context: Context, assistantId: String): File =
        File(skillsRoot(context), "$VISIBLE_SKILL_DIR/${AssistantStorage.id(assistantId)}/skills")

    /** Only UI/user terminals use this assistant-owned view. Agent runs have separate immutable views. */
    fun publishVisibleSkills(context: Context, assistantId: String, entries: List<SkillIndexEntry>): List<SkillIndexEntry> =
        SkillMutationLock.withLock(skillsRoot(context)) {
            val root = File(skillsRoot(context), "$VISIBLE_SKILL_DIR/${AssistantStorage.id(assistantId)}")
            val result = publishSkillView(context, assistantId, entries, root)
            revokeRunSkills(context, assistantId, entries.map { it.id }.toSet())
            result
        }

    fun bindSkillsToAssistant(context: Context, assistantId: String, entries: List<SkillIndexEntry>): List<SkillIndexEntry> =
        publishVisibleSkills(context, assistantId, entries)

    fun createRunSkills(context: Context, assistantId: String, entries: List<SkillIndexEntry>): Pair<File, List<SkillIndexEntry>> =
        SkillMutationLock.withLock(skillsRoot(context)) {
            val runs = File(assistantSkillsDirectory(context, assistantId), ".runs")
            check(runs.mkdirs() || runs.isDirectory)
            cleanupAbandonedRunViews(context, runs)
            check(runs.listFiles().orEmpty().size < 128) { "技能运行快照已达上限，请先清理已结束任务的快照" }
            val root = File(runs, java.util.UUID.randomUUID().toString())
            check(root.mkdirs())
            val leaseFile = java.io.RandomAccessFile(File(root, ".lease"), "rw")
            try {
                runLeases[root.canonicalPath] = leaseFile to leaseFile.channel.lock()
            } catch (error: Throwable) { leaseFile.close(); throw error }
            try {
                val bound = publishSkillView(context, assistantId, entries, root)
                File(root, "skills") to bound
            } catch (error: Throwable) {
                closeRunLease(root)
                deleteSkillPathWithoutFollowingLinks(skillsRoot(context), root)
                throw error
            }
        }

    private fun closeRunLease(root: File) {
        runLeases.remove(root.canonicalPath)?.let { (file, lock) ->
            try { lock.release() } finally { file.close() }
        }
    }

    /** A process crash releases its file lock. Never collect a live or daemon-retained view. */
    private fun cleanupAbandonedRunViews(context: Context, runs: File) {
        runs.listFiles().orEmpty().forEach { root ->
            if (!root.isDirectory || File(root, ".retained").exists() || runLeases.containsKey(root.canonicalPath)) return@forEach
            val lease = File(root, ".lease")
            if (Files.isSymbolicLink(lease.toPath()) || !lease.isFile) return@forEach
            runCatching {
                java.io.RandomAccessFile(lease, "rw").use { file ->
                    file.channel.tryLock()?.use {
                        // 回收孤儿快照同样按尽力而为处理：失败留到下次，不让绑定流程失败。
                        if (!deleteSkillPathWithoutFollowingLinks(skillsRoot(context), root)) {
                            AndroidAgentLogger.warnThrottled("skill-abandoned-cleanup", message = {
                                "未能回收孤儿技能快照，已跳过：" + root.name
                            })
                        }
                    }
                }
            }
        }
    }

    /**
     * Revocation hides code, never deletes the assistant's writable data.
     *
     * 撤销只是「把已授权的快照收窄」，不是事务：某个副本删不掉（并发清理、目录已消失、
     * 短暂占用）不该让整个绑定流程失败。历史上这里用 check() 抛 IllegalStateException，
     * 而调用方在 IO 线程上没有任何捕获，直接把进程打死 —— 打开别的助手的对话就会崩。
     * 现在改为尽力删除：失败的项留在下次绑定或任务结束时再清，最多写一条告警。
     */
    private fun revokeRunSkills(context: Context, assistantId: String, enabled: Set<String>) {
        val runs = File(assistantSkillsDirectory(context, assistantId), ".runs")
        runs.listFiles().orEmpty().forEach { run ->
            File(run, "skills").listFiles().orEmpty().filter { it.name !in enabled }.forEach { target ->
                if (!deleteSkillPathWithoutFollowingLinks(skillsRoot(context), target)) {
                    AndroidAgentLogger.warnThrottled("skill-revoke-delete", message = {
                        "技能撤销时未能删除快照副本，已跳过：" + target.name
                    })
                }
            }
        }
    }

    fun pruneRunSkills(context: Context, root: File, allowed: Set<String>) =
        SkillMutationLock.withLock(skillsRoot(context)) {
            root.listFiles().orEmpty().filter { it.name !in allowed }.forEach { target ->
                if (!deleteSkillPathWithoutFollowingLinks(skillsRoot(context), target)) {
                    AndroidAgentLogger.warnThrottled("skill-prune-delete", message = {
                        "技能快照裁剪未能删除副本，已跳过：" + target.name
                    })
                }
            }
        }

    private fun publishSkillView(context: Context, assistantId: String, entries: List<SkillIndexEntry>, view: File): List<SkillIndexEntry> {
        require(entries.size <= 128) { "单次技能快照条目超过限制" }
        var publishedBytes = 0L
        val assistantRoot = assistantSkillsDirectory(context, assistantId)
        require(!Files.isSymbolicLink(assistantRoot.toPath())) { "助手技能根目录不能是符号链接" }
        val dataRoot = File(assistantRoot, ".data")
        require(!Files.isSymbolicLink(dataRoot.toPath())) { "助手技能数据根目录不能是符号链接" }
        check(dataRoot.mkdirs() || dataRoot.isDirectory)
        check(view.mkdirs() || view.isDirectory)
        // Host-only pointer. Linux mounts individual authorized data folders, not this whole root.
        val dataLink = File(view, "skill-data")
        if (!Files.exists(dataLink.toPath(), LinkOption.NOFOLLOW_LINKS)) Files.createSymbolicLink(dataLink.toPath(), dataRoot.toPath())
        require(dataLink.canonicalFile == dataRoot.canonicalFile) { "技能数据目录归属不一致" }
        val destRoot = File(view, "skills")
        check(destRoot.mkdirs() || destRoot.isDirectory)
        val keep = entries.map { it.id }.toSet()
        destRoot.listFiles().orEmpty().filter { it.name !in keep }.forEach { stale ->
            // 发布新快照时清掉旧副本；删不掉不影响本次发布，下次再清。
            if (!deleteSkillPathWithoutFollowingLinks(skillsRoot(context), stale)) {
                AndroidAgentLogger.warnThrottled("skill-publish-cleanup", message = {
                    "技能快照发布时未能清理旧副本，已跳过：" + stale.name
                })
            }
        }
        return entries.map { entry ->
            require(entry.id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,199}"))) { "技能 ID 无效" }
            val privateData = File(dataRoot, entry.id)
            require(!Files.isSymbolicLink(privateData.toPath())) { "技能数据目录不能是符号链接" }
            if (!privateData.exists()) {
                val legacy = File(assistantRoot, "${entry.id}/data")
                if (legacy.isDirectory && !Files.isSymbolicLink(legacy.toPath())) copySkillTree(legacy, privateData)
                else check(privateData.mkdirs())
            }
            val dest = File(destRoot, entry.id)
            publishedBytes += syncSkillPackage(File(entry.rootPath), dest)
            require(publishedBytes <= 256L * 1024 * 1024) { "技能快照超过 256 MiB" }
            entry.copy(rootPath = dest.canonicalPath, skillFilePath = File(dest, "SKILL.md").canonicalPath, enabled = true)
        }
    }

    /** Ordinary runs release their code snapshot; daemon-backed views must remain until stop. */
    fun releaseRunSkills(context: Context, root: File, retain: Boolean = false) = SkillMutationLock.withLock(skillsRoot(context)) {
        val view = root.parentFile!!
        if (retain && view.isDirectory) File(view, ".retained").writeText("daemon")
        closeRunLease(view)
        if (!retain && !deleteSkillPathWithoutFollowingLinks(skillsRoot(context), view)) {
            // 快照已 closeRunLease，剩余目录留待下次回收；这里失败不影响任务结束。
            AndroidAgentLogger.warnThrottled("skill-release-delete", message = {
                "任务结束时未能删除技能快照，已跳过：" + view.name
            })
        }
    }

    fun copyAssistantSkills(context: Context, fromId: String, toId: String) {
        val root = skillsRoot(context)
        val source = File(root, "$ASSISTANT_SKILL_DIR/${AssistantStorage.id(fromId)}")
        val dest = File(root, "$ASSISTANT_SKILL_DIR/${AssistantStorage.id(toId)}")
        if (!source.isDirectory) return
        dest.parentFile?.mkdirs()
        dest.deleteRecursively()
        check(dest.mkdirs() || dest.isDirectory)
        source.listFiles().orEmpty().filter { it.name != ".runs" }.forEach { copySkillTree(it, File(dest, it.name)) }
    }

    fun deleteAssistantSkills(context: Context, assistantId: String) {
        SkillMutationLock.withLock(skillsRoot(context)) {
            check(deleteSkillPathWithoutFollowingLinks(skillsRoot(context), assistantSkillsDirectory(context, assistantId)))
            check(deleteSkillPathWithoutFollowingLinks(skillsRoot(context), File(skillsRoot(context), "$VISIBLE_SKILL_DIR/${AssistantStorage.id(assistantId)}")))
        }
    }

    fun exportUserSkills(context: Context): Map<String, ByteArray> {
        val root = skillsRoot(context)
        if (!root.isDirectory) return emptyMap()
        require(!Files.isSymbolicLink(root.toPath())) { "技能根目录不能是符号链接" }
        val skip = setOf(ASSISTANT_SKILL_DIR, VISIBLE_SKILL_DIR, ".eta-skill-installer")
        val budget = io.github.mangi.eta.data.repository.BackupBlobBudget()
        return budget.readTree(root) { file ->
            shouldSkipSkillCopy(file) ||
                (file.parentFile == root && (file.name in skip || file.name.startsWith(".") || !file.isDirectory))
        }
    }

    fun importUserSkills(context: Context, files: Map<String, ByteArray>) {
        val root = skillsRoot(context)
        val skip = setOf(ASSISTANT_SKILL_DIR, VISIBLE_SKILL_DIR, ".eta-skill-installer")
        // Validate the entire map before deleting anything. Never silently drop damaged entries.
        val targets = files.map { (relative, bytes) ->
            val normalized = io.github.mangi.eta.data.repository.BackupArchiveSafety.relativePath(relative)
            val first = normalized.substringBefore('/')
            require(first !in skip && !first.startsWith(".")) { "技能备份包含受保护目录" }
            io.github.mangi.eta.data.repository.BackupArchiveSafety.target(root, normalized) to bytes
        }
        check(root.mkdirs() || root.isDirectory) { "无法创建技能目录" }
        root.listFiles().orEmpty()
            .filter { it.isDirectory && it.name !in skip && !it.name.startsWith(".") }
            .forEach { check(it.deleteRecursively()) { "无法清理旧技能" } }
        targets.forEach { (target, bytes) ->
            check(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory) { "无法创建技能目录" }
            target.writeBytes(bytes)
        }
    }

    private fun syncSkillPackage(source: File, dest: File): Long {
        require(source.isDirectory && !Files.isSymbolicLink(source.toPath())) { "技能源目录无效" }
        val staging = File(dest.parentFile, ".stage-${java.util.UUID.randomUUID()}")
        val old = File(dest.parentFile, ".old-${java.util.UUID.randomUUID()}")
        check(staging.mkdirs())
        try {
            var bytes = 0L
            var files = 0
            source.walkTopDown().onEnter { dir ->
                when {
                    dir == source -> true
                    shouldSkipSkillCopy(dir) -> false
                    dir.name == "data" && dir.parentFile == source -> false
                    !dir.canRead() -> false
                    else -> true
                }
            }.forEach { file ->
                if (file == source || shouldSkipSkillCopy(file) || file.relativeTo(source).path.substringBefore(File.separator) == "data") return@forEach
                require(++files <= 4096 && !Files.isSymbolicLink(file.toPath())) { "技能包条目过多或包含符号链接" }
                if (file.isFile) bytes += file.length()
                require(bytes <= 16L * 1024 * 1024) { "技能包超过 16 MiB" }
                val target = File(staging, file.relativeTo(source).path)
                if (file.isDirectory) check(target.mkdirs() || target.isDirectory)
                else {
                    require(file.isFile) { "技能包包含特殊文件" }
                    if (!file.canRead()) {
                        if (shouldSkipSkillCopy(file)) return@forEach
                        error("技能文件不可读：${file.name}")
                    }
                    check(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
                    try {
                        file.copyTo(target)
                    } catch (error: java.io.IOException) {
                        if (shouldSkipSkillCopy(file)) return@forEach
                        throw error
                    }
                }
            }
            check(File(staging, "SKILL.md").isFile) { "技能正文缺失" }
            val dataDir = File(staging, "data")
            check(dataDir.mkdirs() || dataDir.isDirectory)
            if (dest.exists()) moveSkillDirectoryAtomically(dest, old)
            try { moveSkillDirectoryAtomically(staging, dest) }
            catch (error: Throwable) {
                if (old.exists()) moveSkillDirectoryAtomically(old, dest)
                throw error
            }
            return bytes
        } finally {
            staging.deleteRecursively()
            if (dest.exists()) old.deleteRecursively()
        }
    }

    private fun copySkillTree(source: File, dest: File) {
        if (shouldSkipSkillCopy(source)) return
        require(!Files.isSymbolicLink(source.toPath())) { "技能复制不接受符号链接" }
        if (source.isDirectory) {
            if (!source.canRead()) return
            check(dest.mkdirs() || dest.isDirectory)
            source.listFiles().orEmpty().forEach { copySkillTree(it, File(dest, it.name)) }
        } else {
            require(source.isFile) { "技能文件无效" }
            if (!source.canRead()) {
                if (shouldSkipSkillCopy(source)) return
                error("技能文件不可读：${source.name}")
            }
            check(dest.parentFile!!.mkdirs() || dest.parentFile!!.isDirectory)
            try {
                source.copyTo(dest, overwrite = true)
            } catch (error: java.io.IOException) {
                if (shouldSkipSkillCopy(source)) return
                throw error
            }
        }
    }

    fun createIndexService(context: Context): SkillIndexService {
        sharedIndexService?.let { return it }
        return synchronized(this) {
            sharedIndexService ?: SkillIndexService(
                context = context.applicationContext,
                skillsRoot = skillsRoot(context),
            ).also { sharedIndexService = it }
        }
    }

    fun createLoader(context: Context): SkillLoader =
        SkillLoader(skillsRoot(context))

    fun createPackageInstaller(context: Context): SkillPackageInstaller =
        SkillPackageInstaller(
            skillsRoot = skillsRoot(context),
            indexService = createIndexService(context),
        )

    fun createResourceReader(context: Context): SkillResourceReader =
        SkillResourceReader(skillsRoot(context))
}
