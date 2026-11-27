package io.github.mangi.eta.ui.app

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.DrawableRes
import androidx.annotation.MipmapRes
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceAppIcon

internal object AppIconManager {
    private const val ALIAS_OBSIDIAN = "io.github.mangi.eta.ui.MainActivityObsidian"
    private const val ALIAS_PEARL = "io.github.mangi.eta.ui.MainActivityPearl"
    private const val ALIAS_INDIGO = "io.github.mangi.eta.ui.MainActivityIndigo"

    fun aliasName(icon: AppearanceAppIcon): String = when (icon) {
        AppearanceAppIcon.OBSIDIAN -> ALIAS_OBSIDIAN
        AppearanceAppIcon.PEARL -> ALIAS_PEARL
        AppearanceAppIcon.INDIGO -> ALIAS_INDIGO
    }

    @MipmapRes
    fun mipmapRes(icon: AppearanceAppIcon): Int = when (icon) {
        AppearanceAppIcon.OBSIDIAN -> R.mipmap.ic_launcher
        AppearanceAppIcon.PEARL -> R.mipmap.ic_launcher_pearl
        AppearanceAppIcon.INDIGO -> R.mipmap.ic_launcher_indigo
    }

    @DrawableRes
    fun previewDrawableRes(icon: AppearanceAppIcon): Int = when (icon) {
        AppearanceAppIcon.OBSIDIAN -> R.drawable.ic_assistant_default
        AppearanceAppIcon.PEARL -> R.drawable.ic_assistant_default_pearl
        AppearanceAppIcon.INDIGO -> R.drawable.ic_assistant_default_indigo
    }

    fun currentIcon(context: Context): AppearanceAppIcon {
        val pm = context.packageManager
        val pkg = context.packageName
        for (candidate in AppearanceAppIcon.entries) {
            val state = runCatching {
                pm.getComponentEnabledSetting(ComponentName(pkg, aliasName(candidate)))
            }.getOrDefault(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
            if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                return candidate
            }
        }
        return AppearanceAppIcon.OBSIDIAN
    }

    fun apply(context: Context, icon: AppearanceAppIcon) {
        val appContext = context.applicationContext
        val pm = appContext.packageManager
        val pkg = appContext.packageName
        val targetComponent = ComponentName(pkg, aliasName(icon))

        val alreadyApplied = runCatching {
            val targetState = pm.getComponentEnabledSetting(targetComponent)
            val targetEnabled = targetState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                (icon == AppearanceAppIcon.OBSIDIAN && targetState == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
            if (!targetEnabled) return@runCatching false
            AppearanceAppIcon.entries.all { other ->
                if (other == icon) {
                    true
                } else {
                    val otherState = pm.getComponentEnabledSetting(ComponentName(pkg, aliasName(other)))
                    otherState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
                        (other != AppearanceAppIcon.OBSIDIAN && otherState == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
                }
            }
        }.getOrDefault(false)
        if (alreadyApplied) return

        // 先启用目标图标入口，再停用其余入口，避免桌面短时间失去启动项。
        runCatching {
            pm.setComponentEnabledSetting(
                targetComponent,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
        for (other in AppearanceAppIcon.entries) {
            if (other == icon) continue
            runCatching {
                pm.setComponentEnabledSetting(
                    ComponentName(pkg, aliasName(other)),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
        }
    }
}
