package io.github.mangi.eta.ui.pages.providers

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Person
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.ProviderAuthMode
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.PreferenceIcon
import io.github.mangi.eta.ui.navigation.AppRoute
import io.github.mangi.eta.ui.navigation.NewProviderType
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ProviderAuthMethodScreen(
    providerType: NewProviderType,
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    MiuixScaffoldPage(
        title = stringResource(R.string.provider_auth_method_title),
        onBack = onBack,
    ) {
        item(key = "methods") {
            ProviderSection(title = stringResource(R.string.provider_auth_method_choose)) {
                ArrowPreference(
                    title = stringResource(R.string.provider_auth_api_key),
                    summary = stringResource(R.string.provider_auth_api_key_summary),
                    startAction = {
                        PreferenceIcon(icon = Icons.Rounded.Key)
                    },
                    onClick = {
                        onNavigate(
                            AppRoute.ModelProviderNew(
                                providerType = providerType,
                                authMode = ProviderAuthMode.API_KEY,
                            ),
                        )
                    },
                )
                ArrowPreference(
                    title = stringResource(R.string.provider_auth_oauth),
                    summary = stringResource(R.string.provider_auth_oauth_summary),
                    startAction = {
                        PreferenceIcon(icon = Icons.Rounded.Person)
                    },
                    onClick = {
                        onNavigate(
                            AppRoute.ModelProviderNew(
                                providerType = providerType,
                                authMode = ProviderAuthMode.OAUTH,
                            ),
                        )
                    },
                )
            }
        }
        item(key = "note") {
            Text(
                text = stringResource(R.string.provider_auth_method_note),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }
    }
}
