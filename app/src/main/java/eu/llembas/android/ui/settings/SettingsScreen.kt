@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package eu.llembas.android.ui.settings

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.llembas.android.BuildConfig
import eu.llembas.android.R
import eu.llembas.android.auth.Prefs
import eu.llembas.android.lembas
import eu.llembas.android.push.Push
import eu.llembas.android.update.Updater
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onAddInstance: () -> Unit) {
    val context = LocalContext.current
    val store = context.lembas.instances
    val scope = rememberCoroutineScope()
    val instances by store.instances.collectAsState(initial = emptyList())
    val active by store.active.collectAsState(initial = null)
    val prefs by store.prefs.collectAsState(initial = Prefs())
    var pushNote by remember { mutableStateOf<String?>(null) }
    var updateNote by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section(stringResource(R.string.settings_instances))
            instances.forEach { instance ->
                Row(
                    Modifier.fillMaxWidth().clickable { scope.launch { store.use(instance.id) } },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = instance.id == active?.id, onClick = { scope.launch { store.use(instance.id) } })
                    Column(Modifier.weight(1f)) {
                        Text(instance.name.ifBlank { instance.base })
                        Text(
                            "${instance.email} · ${instance.base}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                active?.let { current ->
                    OutlinedButton(onClick = {
                        scope.launch {
                            Push.disable(context, current)
                            runCatching { store.clientFor(current).signOut() }
                            store.remove(current.id)
                        }
                    }) { Text(stringResource(R.string.sign_out)) }
                    OutlinedButton(onClick = onAddInstance) {
                        Text(stringResource(R.string.add_instance))
                    }
                }
            }

            HorizontalDivider()
            Section(stringResource(R.string.settings_theme))
            listOf("" to R.string.theme_system, "moria" to R.string.theme_moria, "shire" to R.string.theme_shire).forEach { (value, label) ->
                Row(
                    Modifier.fillMaxWidth().clickable { scope.launch { store.savePrefs { it.copy(theme = value) } } },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = prefs.theme == value, onClick = { scope.launch { store.savePrefs { it.copy(theme = value) } } })
                    Text(stringResource(label))
                }
            }

            HorizontalDivider()
            Section(stringResource(R.string.settings_notifications))
            val distributors = remember { Push.distributors(context) }
            Text(
                stringResource(if (distributors.isEmpty()) R.string.push_no_distributor else R.string.push_explained),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            active?.let { current ->
                Button(enabled = distributors.isNotEmpty(), onClick = {
                    (context as? Activity)?.let { activity ->
                        Push.enable(activity, current) { ok ->
                            pushNote = context.getString(if (ok) R.string.push_on else R.string.push_failed)
                        }
                    }
                }) { Text(stringResource(R.string.push_enable)) }
            }
            pushNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

            HorizontalDivider()
            Section(stringResource(R.string.settings_assistant))
            Text(stringResource(R.string.assistant_explained), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = {
                context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Text(stringResource(R.string.assistant_open_settings)) }
            Text(stringResource(R.string.assistant_samsung), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.assistant_temporary), modifier = Modifier.weight(1f))
                Switch(checked = prefs.assistantTemporary, onCheckedChange = { on -> scope.launch { store.savePrefs { it.copy(assistantTemporary = on) } } })
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.assistant_read_aloud), modifier = Modifier.weight(1f))
                Switch(checked = prefs.readAloudSpoken, onCheckedChange = { on -> scope.launch { store.savePrefs { it.copy(readAloudSpoken = on) } } })
            }

            HorizontalDivider()
            Section(stringResource(R.string.settings_about))
            Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME))
            if (BuildConfig.SELF_UPDATE) {
                OutlinedButton(onClick = {
                    scope.launch { updateNote = Updater(context).checkAndOffer() }
                }) { Text(stringResource(R.string.update_check)) }
                updateNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}
