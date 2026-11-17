package io.github.mangi.eta.ui.screens.assistants

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AssistantProfile
import io.github.mangi.eta.data.repository.AssistantRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.rememberAssistantAvatarBitmap
import io.github.mangi.eta.ui.navigation.AppRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 网格卡片之间的间距，与 Eta 角色库一致。 */
private val AssistantGridSpacing = 10.dp

@Composable
internal fun AssistantsScreen(
    picker: Boolean,
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
    selectedAssistantId: String? = null,
    onSelectAssistant: ((String) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val profiles by AssistantRepository.profiles.collectAsState()
    val repositoryActiveId by AssistantRepository.activeId.collectAsState()
    val activeId = selectedAssistantId ?: repositoryActiveId
    var searchQuery by remember { mutableStateOf("") }
    var actionProfile by remember { mutableStateOf<AssistantProfile?>(null) }
    var deleteProfile by remember { mutableStateOf<AssistantProfile?>(null) }

    val filtered = remember(profiles, searchQuery) {
        val query = searchQuery.trim()
        profiles.filter { profile ->
            query.isBlank() || profile.name.contains(query, ignoreCase = true)
        }
    }

    val createAssistant = {
        scope.launch {
            val created = withContext(Dispatchers.IO) {
                AssistantRepository.create()
            }
            onNavigate(AppRoute.AssistantEdit(created.id))
        }
    }

    MiuixScaffoldPage(
        title = stringResource(R.string.assistant_list_title),
        onBack = onBack,
        actions = {
            IconButton(onClick = createAssistant) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = stringResource(R.string.assistant_add),
                )
            }
        },
    ) {
        item(key = "search") {
            InputField(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                onSearch = {},
                expanded = false,
                onExpandedChange = {},
                label = stringResource(R.string.assistant_search),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(top = 12.dp, bottom = 10.dp),
            )
        }
        // 双列竖版肖像卡：末尾补一张「新建」占位卡（搜索时不补，避免挤掉结果）。
        val tiles: List<AssistantProfile?> = remember(filtered, searchQuery) {
            filtered + if (searchQuery.isBlank()) listOf(null as AssistantProfile?) else emptyList()
        }
        val rows = remember(tiles) { tiles.chunked(2) }
        items(rows, key = { row -> row.joinToString("|") { it?.id ?: "add" } }) { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = AssistantGridSpacing),
                horizontalArrangement = Arrangement.spacedBy(AssistantGridSpacing),
            ) {
                row.forEach { profile ->
                    if (profile == null) {
                        AssistantAddTile(
                            enabled = true,
                            onCreate = createAssistant,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        AssistantPortraitCard(
                            profile = profile,
                            bitmap = rememberAssistantAvatarBitmap(profile.id, profile.avatarFileName),
                            selected = profile.id == activeId,
                            onClick = {
                                if (picker) {
                                    if (onSelectAssistant != null) {
                                        onSelectAssistant(profile.id)
                                        onBack()
                                    } else {
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                AssistantRepository.select(profile.id)
                                                RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                                            }
                                            onBack()
                                        }
                                    }
                                } else {
                                    onNavigate(AppRoute.AssistantEdit(profile.id))
                                }
                            },
                            onLongClick = { actionProfile = profile },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        if (filtered.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = stringResource(R.string.assistant_empty),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
            }
        }
    }

    actionProfile?.let { profile ->
        AssistantActionsDialog(
            profile = profile,
            canDelete = profiles.size > 1,
            onCopy = {
                actionProfile = null
                scope.launch {
                    withContext(Dispatchers.IO) {
                        AssistantRepository.duplicate(profile.id)
                    }
                }
            },
            onEdit = {
                actionProfile = null
                onNavigate(AppRoute.AssistantEdit(profile.id))
            },
            onDelete = {
                actionProfile = null
                deleteProfile = profile
            },
            onDismiss = { actionProfile = null },
        )
    }

    deleteProfile?.let { profile ->
        AssistantDeleteDialog(
            profile = profile,
            canDelete = profiles.size > 1,
            onDismiss = { deleteProfile = null },
            onConfirm = {
                val target = profile
                deleteProfile = null
                scope.launch {
                    withContext(Dispatchers.IO) {
                        AssistantRepository.delete(target.id)
                        RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                    }
                }
            },
        )
    }
}
