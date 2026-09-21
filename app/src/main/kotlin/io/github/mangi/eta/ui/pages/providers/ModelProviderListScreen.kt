package io.github.mangi.eta.ui.pages.providers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.ProviderSourceTypes
import io.github.mangi.eta.data.model.typeLabel
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffold
import io.github.mangi.eta.ui.layout.horizontalCutoutPadding
import io.github.mangi.eta.ui.navigation.AppRoute
import io.github.mangi.eta.ui.navigation.NewProviderType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
internal fun ModelProviderListScreen(
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
    currentProviderId: String? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val providers by ProviderRepository.providersFlow().collectAsState(initial = emptyList())
    val storedProviderId by RuntimeConfigRepository.selectedProviderIdFlow().collectAsState(initial = null)
    val selectedProviderId = currentProviderId?.takeIf { it.isNotBlank() } ?: storedProviderId
    var searchQuery by remember { mutableStateOf("") }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedProviderIds by remember { mutableStateOf(setOf<String>()) }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }
    var isDeleting by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        RuntimeConfigRepository.ensureDefaults(EtaApp.serviceInstance)
    }

    val filteredProviders = remember(providers, searchQuery) {
        val query = searchQuery.trim()
        providers.filter { provider ->
            query.isBlank() ||
                provider.name.contains(query, ignoreCase = true) ||
                provider.baseUrl.contains(query, ignoreCase = true) ||
                provider.typeLabel.contains(query, ignoreCase = true)
        }
    }
    val selectableProviders = remember(filteredProviders) {
        filteredProviders.filterNot(ProviderSetting::isBuiltIn)
    }

    LaunchedEffect(selectionMode, selectedProviderIds, providers) {
        if (!selectionMode) return@LaunchedEffect
        val existingIds = providers.filterNot { it.isBuiltIn }.mapTo(mutableSetOf()) { it.id }
        val pruned = selectedProviderIds.filter { it in existingIds }.toSet()
        if (pruned != selectedProviderIds) selectedProviderIds = pruned
        if (existingIds.isEmpty()) {
            selectionMode = false
            selectedProviderIds = emptySet()
        }
    }

    val selectionBackState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = selectionBackState,
        isBackEnabled = selectionMode,
        onBackCompleted = {
            selectionMode = false
            selectedProviderIds = emptySet()
        },
    )

    MiuixScaffold(
        title = stringResource(R.string.ui_model_provider_e8c7f5),
        onBack = {
            if (selectionMode) {
                selectionMode = false
                selectedProviderIds = emptySet()
            } else {
                onBack()
            }
        },
    ) { paddingValues, scrollBehavior, sidePadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .horizontalCutoutPadding()
                .padding(top = paddingValues.calculateTopPadding()),
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(
                    start = sidePadding,
                    end = sidePadding,
                ),
                overscrollEffect = null,
            ) {
                item(key = "search") {
                    InputField(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        onSearch = {},
                        expanded = false,
                        onExpandedChange = {},
                        label = stringResource(R.string.ui_search_provider_74e049),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(top = 12.dp, bottom = 8.dp),
                    )
                }

                item(key = "create_section") {
                    ProviderSection(title = stringResource(R.string.ui_add_new_provider_74df54)) {
                        ArrowPreference(
                            title = stringResource(R.string.ui_added_openai_compatible_6bd471),
                            summary = stringResource(R.string.ui_support_chatgpt_deepseek_kimi_glm_qwen_etc_b31d02),
                            startAction = {
                                ProviderBrandIcon(ProviderSourceTypes.OPENAI)
                            },
                            onClick = { onNavigate(AppRoute.ModelProviderAuthMethod(NewProviderType.OpenAiCompatible)) },
                        )

                        ArrowPreference(
                            title = stringResource(R.string.ui_new_anthropic_db6098),
                            summary = stringResource(R.string.ui_support_anthropic_claude_official_or_compatible_api_de3f80),
                            startAction = {
                                ProviderBrandIcon(ProviderSourceTypes.ANTHROPIC)
                            },
                            onClick = { onNavigate(AppRoute.ModelProviderNew(NewProviderType.Anthropic)) },
                        )

                        ArrowPreference(
                            title = stringResource(R.string.ui_new_doubao_speech),
                            summary = stringResource(R.string.ui_new_doubao_speech_summary),
                            startAction = {
                                ProviderBrandIcon(ProviderSourceTypes.DOUBAO_SPEECH)
                            },
                            onClick = { onNavigate(AppRoute.ModelProviderNew(NewProviderType.DoubaoSpeech)) },
                        )

                    }
                }

                item(key = "list_title") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            SmallTitle(
                                pluralStringResource(
                                    R.plurals.provider_configured_count,
                                    filteredProviders.size,
                                    filteredProviders.size,
                                ),
                            )
                        }
                        if (selectionMode && selectableProviders.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.page_select_all_3e44b2),
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clickable {
                                        selectedProviderIds = if (
                                            selectableProviders.isNotEmpty() &&
                                            selectedProviderIds.containsAll(selectableProviders.map { it.id })
                                        ) {
                                            emptySet()
                                        } else {
                                            selectableProviders.mapTo(mutableSetOf()) { it.id }
                                        }
                                    }
                                    .padding(end = 16.dp),
                            )
                        }
                    }
                }

                item(key = "list_section") {
                    ProviderSection(title = null) {
                        if (filteredProviders.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = if (searchQuery.isBlank()) {
                                        stringResource(R.string.provider_empty)
                                    } else {
                                        stringResource(R.string.provider_no_matches)
                                    },
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        } else {
                            filteredProviders.forEach { provider ->
                                ProviderListItem(
                                    provider = provider,
                                    isCurrent = provider.id == selectedProviderId,
                                    selectionMode = selectionMode,
                                    checked = provider.id in selectedProviderIds,
                                    onOpen = { onNavigate(AppRoute.ModelProviderDetail(provider.id)) },
                                    onToggleChecked = {
                                        if (!provider.isBuiltIn) {
                                            selectedProviderIds = if (provider.id in selectedProviderIds) {
                                                selectedProviderIds - provider.id
                                            } else {
                                                selectedProviderIds + provider.id
                                            }
                                        }
                                    },
                                    onEnterSelection = {
                                        if (!provider.isBuiltIn) {
                                            selectionMode = true
                                            selectedProviderIds = setOf(provider.id)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }

                item(key = "bottom_spacer") {
                    Spacer(
                        modifier = Modifier
                            .height(if (selectionMode) 88.dp else 24.dp)
                            .navigationBarsPadding(),
                    )
                }
            }

            AnimatedVisibility(
                visible = selectionMode,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                ProviderSelectionBar(
                    selectedCount = selectedProviderIds.size,
                    enabled = !isDeleting,
                    onDelete = { showBatchDeleteDialog = true },
                    onExit = {
                        selectionMode = false
                        selectedProviderIds = emptySet()
                    },
                )
            }
        }
    }

    if (showBatchDeleteDialog) {
        WindowDialog(
            show = true,
            onDismissRequest = { if (!isDeleting) showBatchDeleteDialog = false },
            title = stringResource(R.string.ui_remove_provider_9f848f),
            summary = pluralStringResource(
                R.plurals.provider_batch_delete_summary,
                selectedProviderIds.size,
                selectedProviderIds.size,
            ),
        ) {
            MiuixDialogActions(
                confirmText = if (isDeleting) {
                    context.getString(R.string.page_deleting_6f941d)
                } else {
                    context.getString(R.string.page_delete_3755f5)
                },
                cancelEnabled = !isDeleting,
                confirmEnabled = !isDeleting && selectedProviderIds.isNotEmpty(),
                destructive = true,
                onCancel = { showBatchDeleteDialog = false },
                onConfirm = {
                    scope.launch {
                        isDeleting = true
                        try {
                            ProviderRepository.deleteProviders(selectedProviderIds)
                            RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                            showBatchDeleteDialog = false
                            selectionMode = false
                            selectedProviderIds = emptySet()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } finally {
                            isDeleting = false
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun ProviderSelectionBar(
    selectedCount: Int,
    enabled: Boolean,
    onDelete: () -> Unit,
    onExit: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                text = stringResource(R.string.ui_delete_3755f5),
                enabled = selectedCount > 0 && enabled,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(
                    color = MiuixTheme.colorScheme.error,
                    textColor = MiuixTheme.colorScheme.onError,
                ),
                onClick = onDelete,
            )
            TextButton(
                text = stringResource(R.string.action_cancel),
                enabled = enabled,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
                onClick = onExit,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProviderListItem(
    provider: ProviderSetting,
    isCurrent: Boolean,
    selectionMode: Boolean,
    checked: Boolean,
    onOpen: () -> Unit,
    onToggleChecked: () -> Unit,
    onEnterSelection: () -> Unit,
) {
    val selectable = !provider.isBuiltIn
    val opacity = if (provider.isEnabled) 1f else 0.6f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    when {
                        selectionMode && selectable -> onToggleChecked()
                        selectionMode -> Unit
                        else -> onOpen()
                    }
                },
                onLongClick = {
                    if (selectable) {
                        if (selectionMode) onToggleChecked() else onEnterSelection()
                    }
                },
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .graphicsLayer { alpha = opacity },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProviderIcon(provider)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = provider.name,
                style = MiuixTheme.textStyles.headline1,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = provider.baseUrl,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                text = listOfNotNull(
                    provider.typeLabel,
                    pluralStringResource(R.plurals.provider_models_count, provider.models.size, provider.models.size),
                    stringResource(R.string.ui_built_in_09ceea).takeIf { provider.isBuiltIn },
                    stringResource(R.string.ui_current_25e74d).takeIf { isCurrent },
                ).joinToString(" · "),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (!provider.isEnabled) {
                Text(
                    text = stringResource(R.string.ui_disabled_0fe5a9),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (selectionMode && selectable) {
            Checkbox(
                state = if (checked) ToggleableState.On else ToggleableState.Off,
                onClick = onToggleChecked,
            )
        } else if (!selectionMode && isCurrent) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = stringResource(R.string.ui_current_25e74d),
                tint = MiuixTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
