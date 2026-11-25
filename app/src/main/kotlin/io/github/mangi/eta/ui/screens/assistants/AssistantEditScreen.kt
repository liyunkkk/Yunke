package io.github.mangi.eta.ui.screens.assistants

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.coui.CouiTopAppBarTextButton
import io.github.mangi.eta.agent.skill.SkillIndexEntry
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.AgentMemoryStore
import io.github.mangi.eta.data.repository.AssistantRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.components.EditorScrollIsolation
import io.github.mangi.eta.ui.components.isolateEditorScroll
import io.github.mangi.eta.ui.components.rememberAssistantAvatarBitmap
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.model.SkillItemUi
import io.github.mangi.eta.ui.screens.skills.SkillSwitchRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.mangi.eta.ui.components.Card
import io.github.mangi.eta.ui.components.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import io.github.mangi.eta.ui.components.TextField
import io.github.mangi.eta.ui.components.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AssistantEditScreen(
    assistantId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val activeId by AssistantRepository.activeId.collectAsState()
    val editorScrollIsolation = remember(assistantId) { EditorScrollIsolation() }
    val original = remember(assistantId) { AssistantRepository.profile(assistantId) }
    if (original == null) {
        LaunchedEffect(assistantId) { onBack() }
        return
    }
    var name by remember(assistantId) { mutableStateOf(original.name) }
    var prompt by remember(assistantId) { mutableStateOf(original.prompt) }
    var avatarFileName by remember(assistantId) { mutableStateOf(original.avatarFileName) }
    var memoryEnabled by remember(assistantId) { mutableStateOf(original.memoryEnabled) }
    var persistedMemoryEnabled by remember(assistantId) { mutableStateOf(original.memoryEnabled) }
    var enabledSkillIds by remember(assistantId) { mutableStateOf(original.enabledSkillIds.toSet()) }
    var persistedSkillIds by remember(assistantId) { mutableStateOf(original.enabledSkillIds.toSet()) }
    var memoryDraft by remember(assistantId) { mutableStateOf("") }
    var memoryRevision by remember(assistantId) { mutableStateOf("") }
    var memorySaved by remember(assistantId) { mutableStateOf("") }
    var installedSkills by remember(assistantId) { mutableStateOf<List<SkillIndexEntry>>(emptyList()) }
    var cropBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var saving by remember { mutableStateOf(false) }
    val avatarBitmap = rememberAssistantAvatarBitmap(assistantId, avatarFileName)
    val memoryBytes = remember(memoryDraft) { memoryDraft.toByteArray(Charsets.UTF_8).size }
    val memoryOverLimit = memoryBytes > AgentMemoryStore.MAX_FILE_BYTES
    val dirty = name.trim() != original.name ||
        prompt != original.prompt ||
        avatarFileName != original.avatarFileName ||
        memoryEnabled != persistedMemoryEnabled ||
        enabledSkillIds != persistedSkillIds ||
        memoryDraft != memorySaved

    LaunchedEffect(assistantId) {
        val loaded = withContext(Dispatchers.IO) {
            val snapshot = runCatching { AgentMemoryRepository.snapshot(assistantId) }.getOrNull()
            val skills = runCatching {
                SkillRuntime.createIndexService(context.applicationContext)
                    .listSkillsForManagement()
                    .filter { it.installed }
            }.getOrDefault(emptyList())
            snapshot to skills
        }
        memoryDraft = loaded.first?.content.orEmpty()
        memorySaved = loaded.first?.content.orEmpty()
        memoryRevision = loaded.first?.revision.orEmpty()
        installedSkills = loaded.second
    }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        cropBitmap = decodeAvatarBitmap(context, uri)
    }

    fun persistAssistantToggles(nextMemory: Boolean = memoryEnabled, nextSkills: Set<String> = enabledSkillIds) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                val current = requireNotNull(AssistantRepository.currentProfile(assistantId)) { "助手不存在" }
                AssistantRepository.update(current.copy(memoryEnabled = nextMemory, enabledSkillIds = nextSkills.toList()))
            } }
            if (result.isSuccess) {
                persistedMemoryEnabled = nextMemory
                persistedSkillIds = nextSkills
            } else {
                android.widget.Toast.makeText(context, result.exceptionOrNull()?.message ?: "技能目录发布失败，请重试", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    fun save() {
        if (!dirty || saving || memoryOverLimit || memoryRevision.isBlank()) return
        val targetMemory = memoryDraft
        val baseRevision = memoryRevision
        val targetProfile = original.copy(name = name, prompt = prompt, avatarFileName = avatarFileName,
            memoryEnabled = memoryEnabled, enabledSkillIds = enabledSkillIds.toList())
        val memoryChanged = targetMemory != memorySaved
        saving = true
        scope.launch {
            var savedSnapshot: io.github.mangi.eta.data.repository.AgentMemorySnapshot? = null
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (memoryChanged) savedSnapshot = AgentMemoryRepository.replaceAll(targetMemory, assistantId, baseRevision)
                    AssistantRepository.update(targetProfile)
                    RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                }
            }
            savedSnapshot?.let { snapshot ->
                memoryRevision = snapshot.revision
                memorySaved = snapshot.content
            }
            saving = false
            if (result.isSuccess) onBack()
            else android.widget.Toast.makeText(context, result.exceptionOrNull()?.message ?: "保存失败，草稿已保留", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    MiuixScaffoldPage(
        title = stringResource(R.string.assistant_edit_title),
        editorScrollIsolation = editorScrollIsolation,
        onBack = onBack,
        actions = {
            if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
                CouiTopAppBarTextButton(
                    text = stringResource(R.string.action_save),
                    enabled = dirty && !saving && !memoryOverLimit,
                    onClick = ::save,
                    modifier = Modifier.padding(end = 8.dp),
                )
            } else {
                Text(
                    text = stringResource(R.string.action_save),
                    color = if (dirty && !saving && !memoryOverLimit) {
                        MiuixTheme.colorScheme.primary
                    } else {
                        MiuixTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    },
                    modifier = Modifier
                        .padding(end = 12.dp)
                        .clickable(enabled = dirty && !saving && !memoryOverLimit, onClick = ::save),
                )
            }
        },
    ) {
        item(key = "hero") {
            AssistantHero(
                profile = original.copy(name = name.trim().ifBlank { original.name }),
                bitmap = avatarBitmap,
                isCurrent = activeId == assistantId,
                busy = saving,
                onChangeAvatar = {
                    picker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                onSetCurrent = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            AssistantRepository.select(assistantId)
                            RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                        }
                    }
                },
            )
        }
        item(key = "identity_title") {
            SmallTitle(stringResource(R.string.assistant_identity))
        }
        item(key = "identity") {
            TextField(
                value = name,
                onValueChange = { name = it },
                label = stringResource(R.string.assistant_name),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
                singleLine = true,
            )
        }
        item(key = "prompt_title") {
            SmallTitle(stringResource(R.string.assistant_prompt_title))
        }
        item(key = "prompt") {
            TextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = "",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp)
                    .height(240.dp)
                    .isolateEditorScroll(editorScrollIsolation),
                singleLine = false,
            )
        }
        item(key = "memory_title") {
            SmallTitle(stringResource(R.string.ui_memory_b55ff5))
        }
        item(key = "memory_switch") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 10.dp),
            ) {
                SwitchPreference(
                    title = stringResource(R.string.ui_enable_memory_4b69b7),
                    summary = stringResource(R.string.assistant_memory_summary),
                    checked = memoryEnabled,
                    onCheckedChange = {
                        memoryEnabled = it
                        persistAssistantToggles(nextMemory = it)
                    },
                )
            }
        }
        item(key = "memory") {
            Column(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                TextField(
                    value = memoryDraft,
                    onValueChange = { memoryDraft = it },
                    label = "MEMORY.md",
                    useLabelAsPlaceholder = true,
                    singleLine = false,
                    minLines = 6,
                    maxLines = 12,
                    textStyle = MiuixTheme.textStyles.body2.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().isolateEditorScroll(editorScrollIsolation),
                )
                if (memoryOverLimit) {
                    Text(
                        text = stringResource(R.string.memory_over_limit),
                        color = MiuixTheme.colorScheme.error,
                        style = MiuixTheme.textStyles.footnote1,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
        item(key = "skills_title") {
            SmallTitle(stringResource(R.string.route_skills))
        }
        item(key = "skills") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                Text(
                    text = stringResource(R.string.assistant_skills_summary),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                if (installedSkills.isEmpty()) {
                    Text(
                        text = stringResource(R.string.ui_no_skills_installed_yet_4e960f),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    )
                } else {
                    installedSkills.forEach { skill ->
                        SkillSwitchRow(
                            skill = skill.toSwitcherUi(enabledSkillIds.contains(skill.id)),
                            enabled = !saving,
                            showMenu = false,
                            onToggle = { enabled ->
                                val next = if (enabled) {
                                    enabledSkillIds + skill.id
                                } else {
                                    enabledSkillIds - skill.id
                                }
                                enabledSkillIds = next
                                persistAssistantToggles(nextSkills = next)
                            },
                        )
                    }
                }
            }
        }
    }

    cropBitmap?.let { bitmap ->
        AvatarCropDialog(
            bitmap = bitmap,
            onCancel = { cropBitmap = null },
            onConfirm = { cropped ->
                cropBitmap = null
                scope.launch {
                    val updated = withContext(Dispatchers.IO) {
                        AssistantRepository.saveAvatar(assistantId, cropped)
                    }
                    avatarFileName = updated.avatarFileName
                    if (cropped !== bitmap) cropped.recycle()
                    bitmap.recycle()
                }
            },
        )
    }
}

private fun SkillIndexEntry.toSwitcherUi(enabled: Boolean): SkillItemUi = SkillItemUi(
    id = id,
    name = name,
    description = description,
    source = source,
    enabled = enabled,
    installed = installed,
    capabilities = buildList {
        if (hasScripts) add("scripts")
        if (hasReferences) add("references")
        if (hasAssets) add("assets")
        if (hasEvals) add("evals")
    },
)
