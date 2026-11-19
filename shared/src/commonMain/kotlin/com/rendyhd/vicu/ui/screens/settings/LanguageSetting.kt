package com.rendyhd.vicu.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.resources.Res
import com.rendyhd.vicu.resources.language_name
import com.rendyhd.vicu.resources.settings_language
import com.rendyhd.vicu.resources.settings_language_device
import com.rendyhd.vicu.resources.settings_language_per_app
import org.jetbrains.compose.resources.stringResource

/**
 * Opens the system's language setting for this app, or null where the system has none (Android 12
 * and older): there the app follows the device's language.
 *
 * The app has no language setting of its own. Android 13 and later and iOS keep a language per
 * app, which the widgets, the notifications and the system's own dialogs follow too; the languages
 * offered there are the catalogs in `composeResources/` (`./gradlew syncLanguages` lists them for
 * both systems).
 */
@Composable
expect fun rememberOpenLanguageSettings(): (() -> Unit)?

/**
 * "Language" (Settings, General, Preferences; docs/translating.md): the language in use, by the
 * name its own catalog gives it, and the way to the system setting that changes it.
 */
@Composable
internal fun LanguageSetting(modifier: Modifier = Modifier) {
    val open = rememberOpenLanguageSettings()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (open != null) Modifier.clickable(role = Role.Button, onClick = open) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = stringResource(Res.string.settings_language), style = MaterialTheme.typography.bodyLarge)
            // The catalog in use names its own language, so this is right whatever the device prefers.
            Text(
                text = stringResource(Res.string.language_name),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(if (open != null) Res.string.settings_language_per_app else Res.string.settings_language_device),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
