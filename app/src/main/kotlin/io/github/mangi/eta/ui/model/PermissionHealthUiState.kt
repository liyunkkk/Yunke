package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable

@Immutable
data class PermissionHealthUiState(
    val items: List<PermissionHealthItemUi>,
)

@Immutable
data class PermissionHealthItemUi(
    val id: String,
    val title: String,
    val summary: String,
    val status: PermissionStatusUi,
    val primaryActionLabel: String?,
    /**
     * 开关行才有值：null = 普通导航行（ArrowPreference，点击进系统设置）；
     * 非 null = 开关行（SwitchPreference，checked = 该值，点击不导航）。
     * 默认 null，现有 9 个构造点不用改，向后兼容。
     */
    val toggleChecked: Boolean? = null,
)

@Immutable
enum class PermissionStatusUi {
    Available,
    Missing,
    Warning,
    Disabled,
}
