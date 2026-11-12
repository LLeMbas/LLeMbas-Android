@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package eu.llembas.android.ui.settings

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Assistant
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.ScreenLockPortrait
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import eu.llembas.android.AppLanguage
import eu.llembas.android.BuildConfig
import eu.llembas.android.R
import eu.llembas.android.actions.NavApp
import eu.llembas.android.assist.PhoneRecognizer
import eu.llembas.android.auth.Instance
import eu.llembas.android.auth.Prefs
import eu.llembas.android.lembas
import eu.llembas.android.notify.NotificationReader
import eu.llembas.android.push.Push
import eu.llembas.android.ui.theme.LocalPalette
import eu.llembas.android.update.AppVersion
import eu.llembas.android.update.ReleaseCheck
import eu.llembas.android.update.UpdateOffer
import eu.llembas.android.update.UpdateResult
import eu.llembas.android.update.Updater
import eu.llembas.android.update.checksUpdates
import eu.llembas.android.update.offersBetas
import eu.llembas.android.wake.WakeEngine
import eu.llembas.android.wake.WakeFailure
import eu.llembas.android.wake.WakePause
import eu.llembas.android.wake.WakePhrase
import eu.llembas.android.wake.WakePhrases
import eu.llembas.android.wake.WakeSensitivity
import eu.llembas.android.wake.WakeState
import eu.llembas.android.wake.WakeStatus
import eu.llembas.android.wake.WakeWordService
import eu.llembas.android.wake.WakeWords
import eu.llembas.android.wake.wakeTitle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the settings page does; every action has a do-nothing default for the screenshots. */
interface SettingsActions {
    fun use(instance: Instance) {}
    fun add() {}
    fun signOut(instance: Instance) {}
    fun theme(value: String) {}
    fun language(value: String) {}
    fun enablePush(instance: Instance) {}
    fun notificationSettings() {}
    fun assistantSettings() {}
    fun assistantTemporary(on: Boolean) {}
    fun voiceInput(value: String) {}
    fun assistantListens(on: Boolean) {}
    fun assistantConversation(on: Boolean) {}
    fun wakeWord(on: Boolean) {}
    fun wakePhrase(id: String) {}
    fun wakeSensitivity(id: String) {}
    fun wakeTest() {}
    fun wakeScreenOnly(on: Boolean) {}
    fun batterySettings() {}
    fun notificationSummaries(on: Boolean) {}
    fun notificationAccess() {}
    fun navApp(id: String) {}
    fun osmandInfo(on: Boolean) {}
    fun readAloud(on: Boolean) {}
    fun checkUpdate() {}
    fun openRelease() {}
    fun installUpdate() {}
    fun updateCheck(on: Boolean) {}
    fun updateBetas(on: Boolean) {}
}

/** "Test the wake word" as the row shows it: not started, [running], or [done] -- with how often it was [heard]. */
data class WakeTestShown(val running: Boolean = false, val done: Boolean = false, val heard: Int = 0)

/** What the settings page shows. */
data class SettingsState(
    val instances: List<Instance> = emptyList(),
    val active: Instance? = null,
    val prefs: Prefs = Prefs(),
    val hasDistributor: Boolean = false,
    val pushNote: String? = null,
    val updateNote: String? = null,
    /** A newer release the last check found: the row opens its page, the full build can install it. */
    val updateFound: String? = null,
    val version: String = "",
    val selfUpdate: Boolean = false,
    /** Whether the app itself is a beta: the update settings' defaults follow it. */
    val beta: Boolean = false,
    /** Whether the phone has a speech recogniser of its own (Settings › Voice input › Phone). */
    val phoneRecognizer: Boolean = false,
    /** Whether this build has a wake-word engine (the F-Droid build has none). */
    val wakeAvailable: Boolean = false,
    /** What the wake word is doing right now (WakeStatus), said under its switch while it is on. */
    val wakeState: WakeState = WakeState.Off,
    /** "Test the wake word": whether its ten seconds are running, and how often the phrase landed. */
    val wakeTest: WakeTestShown = WakeTestShown(),
    /** Whether Android's notification access is given to LLeMbas. */
    val notificationAccess: Boolean = false,
    /** The navigation apps installed: NavApp ids. */
    val navApps: List<String> = emptyList(),
)

@Composable
fun SettingsScreen(onBack: () -> Unit, onAddInstance: () -> Unit) {
    val context = LocalContext.current
    val store = context.lembas.instances
    val scope = rememberCoroutineScope()
    val instances by store.instances.collectAsState(initial = emptyList())
    val active by store.active.collectAsState(initial = null)
    val prefs by store.prefs.collectAsState(initial = Prefs())
    val wakeState by WakeStatus.state.collectAsState()
    // The test's ten seconds: its deadline is WakeStatus's, the clock that watches it is here.
    val trial by WakeStatus.trial.collectAsState()
    var clock by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    var tested by remember { mutableStateOf(false) }
    LaunchedEffect(trial.until) {
        while (SystemClock.elapsedRealtime() < trial.until) {
            clock = SystemClock.elapsedRealtime()
            delay(200)
        }
        clock = SystemClock.elapsedRealtime()
    }
    var pushNote by remember { mutableStateOf<String?>(null) }
    var updateNote by remember { mutableStateOf<String?>(null) }
    var updateFound by remember { mutableStateOf<UpdateOffer?>(null) }
    val beta = remember { AppVersion.parse(BuildConfig.VERSION_NAME)?.isBeta ?: false }
    val distributors = remember { Push.distributors(context) }
    // The wake word needs the microphone before it can be turned on.
    val micForWake = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            scope.launch {
                store.savePrefs { it.copy(wakeWord = true) }
                WakeWordService.start(context)
            }
        }
    }
    val actions = remember {
        object : SettingsActions {
            override fun use(instance: Instance) {
                scope.launch { store.use(instance.id) }
            }

            override fun add() = onAddInstance()

            override fun signOut(instance: Instance) {
                scope.launch {
                    Push.disable(context, instance)
                    runCatching { store.clientFor(instance).signOut() }
                    store.remove(instance.id)
                }
            }

            override fun theme(value: String) {
                scope.launch { store.savePrefs { it.copy(theme = value) } }
            }

            // MainActivity follows the choice: it puts the app in the language at once.
            override fun language(value: String) {
                scope.launch { store.savePrefs { it.copy(language = value) } }
            }

            override fun enablePush(instance: Instance) {
                (context as? Activity)?.let { activity ->
                    Push.enable(activity, instance) { ok -> pushNote = context.getString(if (ok) R.string.push_on else R.string.push_failed) }
                }
            }

            override fun notificationSettings() {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }

            override fun assistantSettings() {
                context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }

            override fun assistantTemporary(on: Boolean) {
                scope.launch { store.savePrefs { it.copy(assistantTemporary = on) } }
            }

            override fun voiceInput(value: String) {
                scope.launch { store.savePrefs { it.copy(voiceInput = value) } }
            }

            override fun assistantListens(on: Boolean) {
                scope.launch { store.savePrefs { it.copy(assistantListens = on) } }
            }

            override fun assistantConversation(on: Boolean) {
                scope.launch { store.savePrefs { it.copy(assistantConversation = on) } }
            }

            override fun wakeWord(on: Boolean) {
                val mic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                when {
                    on && !mic -> micForWake.launch(Manifest.permission.RECORD_AUDIO)

                    on -> scope.launch {
                        store.savePrefs { it.copy(wakeWord = true) }
                        WakeWordService.start(context)
                    }

                    else -> scope.launch {
                        store.savePrefs { it.copy(wakeWord = false) }
                        WakeWordService.stop(context)
                    }
                }
            }

            override fun wakePhrase(id: String) {
                scope.launch { store.savePrefs { it.copy(wakePhrase = id) } }
            }

            override fun wakeSensitivity(id: String) {
                scope.launch { store.savePrefs { it.copy(wakeSensitivity = id) } }
            }

            override fun wakeTest() {
                tested = true
                WakeStatus.startTrial(SystemClock.elapsedRealtime())
            }

            override fun wakeScreenOnly(on: Boolean) {
                scope.launch { store.savePrefs { it.copy(wakeScreenOnly = on) } }
            }

            // The list of apps the phone's battery optimisation leaves alone; no permission needed to open it.
            override fun batterySettings() {
                runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }

            // On: Android's own switch is the person's to flip, so its page opens if it is off.
            override fun notificationSummaries(on: Boolean) {
                scope.launch { store.savePrefs { it.copy(notificationSummaries = on) } }
                if (on && !NotificationReader.granted(context)) notificationAccess()
            }

            override fun notificationAccess() {
                runCatching { context.startActivity(NotificationReader.settingsIntent(context)) }
            }

            override fun navApp(id: String) {
                scope.launch { store.savePrefs { it.copy(navApp = id) } }
            }

            override fun osmandInfo(on: Boolean) {
                scope.launch { store.savePrefs { it.copy(osmandInfo = on) } }
            }

            override fun readAloud(on: Boolean) {
                scope.launch { store.savePrefs { it.copy(readAloudSpoken = on) } }
            }

            override fun checkUpdate() {
                updateNote = context.getString(R.string.update_checking)
                scope.launch {
                    when (val found = ReleaseCheck(context).check()) {
                        is UpdateResult.Newer -> {
                            updateFound = found.offer
                            updateNote = context.getString(R.string.update_available, found.offer.version.toString())
                        }

                        UpdateResult.Current -> {
                            updateFound = null
                            updateNote = context.getString(R.string.update_none)
                        }

                        UpdateResult.Unreachable -> updateNote = context.getString(R.string.update_unreachable)
                    }
                }
            }

            override fun openRelease() {
                updateFound?.let { offer -> runCatching { context.startActivity(ReleaseCheck.open(offer.page)) } }
            }

            // The full build's own installer, for a release (llembas.eu names its APK and hash).
            override fun installUpdate() {
                scope.launch { updateNote = Updater(context).checkAndOffer() }
            }

            override fun updateCheck(on: Boolean) {
                scope.launch { store.savePrefs { it.copy(updateCheck = on) } }
            }

            override fun updateBetas(on: Boolean) {
                scope.launch { store.savePrefs { it.copy(updateBetas = on) } }
            }
        }
    }
    SettingsContent(
        SettingsState(
            instances = instances,
            active = active,
            prefs = prefs,
            hasDistributor = distributors.isNotEmpty(),
            pushNote = pushNote,
            updateNote = updateNote,
            updateFound = updateFound?.version?.toString(),
            version = BuildConfig.VERSION_NAME,
            selfUpdate = BuildConfig.SELF_UPDATE,
            beta = beta,
            phoneRecognizer = remember { PhoneRecognizer.available(context) },
            wakeAvailable = WakeEngine.AVAILABLE,
            wakeState = wakeState,
            wakeTest = WakeTestShown(running = clock < trial.until, done = tested && clock >= trial.until, heard = if (tested) trial.heard else 0),
            // Read again each time Settings is composed: the person comes back from the system's page.
            notificationAccess = NotificationReader.granted(context),
            navApps = remember { NavApp.installedApps(context).map { it.id } },
        ),
        actions,
        onBack,
    )
}

/**
 * Settings as Material 3 grouped lists: each group a header and one rounded surface of rows,
 * every row at least 56 dp with its icon, its words (which wrap) and its control (which keeps
 * its size).
 */
@Composable
fun SettingsContent(state: SettingsState, actions: SettingsActions, onBack: () -> Unit = {}) {
    val palette = LocalPalette.current
    var choosingLanguage by remember { mutableStateOf(false) }
    var choosingWake by remember { mutableStateOf<WakeChoice?>(null) }
    choosingWake?.let { which ->
        ModalBottomSheet(onDismissRequest = { choosingWake = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            WakeChoices(which, state.prefs, onPick = { pick ->
                choosingWake = null
                pick(actions)
            })
        }
    }
    if (choosingLanguage) {
        ModalBottomSheet(onDismissRequest = { choosingLanguage = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            LanguageChoices(state.prefs, onPick = {
                choosingLanguage = false
                actions.language(it)
            })
        }
    }
    Scaffold(
        containerColor = palette.bg,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = palette.bg, scrolledContainerColor = palette.bg),
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
            item {
                Group(stringResource(R.string.settings_instances)) {
                    state.instances.forEachIndexed { i, instance ->
                        if (i > 0) Rule()
                        val inUse = instance.id == state.active?.id
                        Row(
                            Modifier.fillMaxWidth().clickable { actions.use(instance) }.heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Initial(instance.name.ifBlank { instance.base })
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    instance.name.ifBlank {
                                        instance.base
                                    },
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    instance.email,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = palette.inkMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    instance.base,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = palette.inkFaint,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (inUse) {
                                Spacer(Modifier.width(12.dp))
                                Icon(Icons.Filled.CheckCircle, contentDescription = stringResource(R.string.instance_in_use), tint = palette.accent)
                            }
                        }
                    }
                    if (state.instances.isNotEmpty()) Rule()
                    SettingRow(Icons.Filled.Add, stringResource(R.string.add_instance), onClick = actions::add)
                    state.active?.let { current ->
                        Rule()
                        SettingRow(Icons.AutoMirrored.Filled.Logout, stringResource(R.string.sign_out), tint = palette.danger, onClick = {
                            actions.signOut(current)
                        })
                    }
                }
            }
            item {
                Group(stringResource(R.string.settings_language)) {
                    SettingRow(
                        Icons.Filled.Translate,
                        languageLabel(state.prefs),
                        subtitle = stringResource(R.string.language_hint),
                        onClick = { choosingLanguage = true },
                    )
                }
            }
            item {
                Group(stringResource(R.string.settings_theme)) {
                    listOf("" to R.string.theme_system, "moria" to R.string.theme_moria, "shire" to R.string.theme_shire).forEachIndexed { i, (value, label) ->
                        if (i > 0) Rule()
                        SettingRow(
                            Icons.Filled.Palette,
                            stringResource(label),
                            onClick = { actions.theme(value) },
                            trailing = { RadioButton(selected = state.prefs.theme == value, onClick = { actions.theme(value) }) },
                            showIcon = i == 0,
                        )
                    }
                }
            }
            item {
                Group(stringResource(R.string.settings_notifications)) {
                    SettingRow(
                        Icons.Filled.NotificationsActive,
                        stringResource(R.string.push_enable),
                        subtitle = state.pushNote ?: stringResource(if (state.hasDistributor) R.string.push_explained else R.string.push_no_distributor),
                        enabled = state.hasDistributor && state.active != null,
                        onClick = { state.active?.let(actions::enablePush) },
                    )
                    Rule()
                    SettingRow(
                        Icons.Filled.Notifications,
                        stringResource(R.string.notification_settings),
                        subtitle = stringResource(R.string.notification_settings_hint),
                        onClick = actions::notificationSettings,
                    )
                }
            }
            item {
                Group(stringResource(R.string.settings_assistant)) {
                    SettingRow(
                        Icons.Filled.Assistant,
                        stringResource(R.string.assistant_open_settings),
                        subtitle = stringResource(R.string.assistant_explained),
                        onClick = actions::assistantSettings,
                    )
                    Rule()
                    SettingRow(
                        Icons.Filled.Timer,
                        stringResource(R.string.assistant_temporary),
                        onClick = { actions.assistantTemporary(!state.prefs.assistantTemporary) },
                        trailing = { Switch(checked = state.prefs.assistantTemporary, onCheckedChange = actions::assistantTemporary) },
                    )
                    Rule()
                    SettingRow(
                        Icons.Filled.Mic,
                        stringResource(R.string.assistant_listens),
                        onClick = { actions.assistantListens(!state.prefs.assistantListens) },
                        trailing = { Switch(checked = state.prefs.assistantListens, onCheckedChange = actions::assistantListens) },
                    )
                    Rule()
                    SettingRow(
                        Icons.Filled.RecordVoiceOver,
                        stringResource(R.string.assistant_read_aloud),
                        onClick = { actions.readAloud(!state.prefs.readAloudSpoken) },
                        trailing = { Switch(checked = state.prefs.readAloudSpoken, onCheckedChange = actions::readAloud) },
                    )
                    Rule()
                    SettingRow(
                        Icons.Filled.Forum,
                        stringResource(R.string.assistant_conversation),
                        subtitle = stringResource(R.string.assistant_conversation_line),
                        enabled = state.prefs.readAloudSpoken,
                        onClick = { actions.assistantConversation(!state.prefs.assistantConversation) },
                        trailing = {
                            Switch(
                                checked = state.prefs.assistantConversation && state.prefs.readAloudSpoken,
                                enabled = state.prefs.readAloudSpoken,
                                onCheckedChange = actions::assistantConversation,
                            )
                        },
                    )
                }
                Text(
                    stringResource(R.string.assistant_samsung),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.inkFaint,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item { WakeWordGroup(state, actions, onChoose = { choosingWake = it }) }
            item { NotificationSummaryGroup(state, actions) }
            item { PhoneActionsGroup(state, actions) }
            item {
                // Which recogniser turns speech into words: the instance's or the phone's -- what
                // each one is, and where the recording goes, said in the row itself.
                Group(stringResource(R.string.voice_input)) {
                    SettingRow(
                        Icons.Filled.Dns,
                        stringResource(R.string.voice_input_lembas),
                        subtitle = stringResource(R.string.voice_input_lembas_line),
                        onClick = { actions.voiceInput(Prefs.VOICE_LEMBAS) },
                        trailing = {
                            RadioButton(selected = state.prefs.voiceInput != Prefs.VOICE_PHONE, onClick = { actions.voiceInput(Prefs.VOICE_LEMBAS) })
                        },
                    )
                    Rule()
                    SettingRow(
                        Icons.Filled.PhoneAndroid,
                        stringResource(R.string.voice_input_phone),
                        subtitle = stringResource(if (state.phoneRecognizer) R.string.voice_input_phone_line else R.string.voice_input_phone_none),
                        enabled = state.phoneRecognizer,
                        onClick = { actions.voiceInput(Prefs.VOICE_PHONE) },
                        trailing = {
                            RadioButton(
                                selected = state.prefs.voiceInput == Prefs.VOICE_PHONE,
                                enabled = state.phoneRecognizer,
                                onClick = { actions.voiceInput(Prefs.VOICE_PHONE) },
                            )
                        },
                    )
                }
                Text(
                    stringResource(R.string.voice_input_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.inkFaint,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item {
                Group(stringResource(R.string.settings_about)) {
                    SettingRow(Icons.Filled.Info, stringResource(R.string.about_version, state.version), onClick = {})
                    Rule()
                    // A found release: the row opens its page; until then it checks.
                    SettingRow(
                        Icons.Filled.SystemUpdate,
                        stringResource(R.string.update_check),
                        subtitle = state.updateNote,
                        onClick = if (state.updateFound != null) actions::openRelease else actions::checkUpdate,
                    )
                    // The full build installs a release itself; a beta is installed from its page.
                    val found = state.updateFound
                    if (state.selfUpdate && found != null && !found.contains("-")) {
                        Rule()
                        SettingRow(
                            Icons.Filled.Download,
                            stringResource(R.string.update_install, found),
                            onClick = actions::installUpdate,
                        )
                    }
                    Rule()
                    val checks = state.prefs.checksUpdates(state.beta)
                    SettingRow(
                        Icons.Filled.Update,
                        stringResource(R.string.update_auto),
                        subtitle = stringResource(R.string.update_auto_line),
                        onClick = { actions.updateCheck(!checks) },
                        trailing = { Switch(checked = checks, onCheckedChange = actions::updateCheck) },
                    )
                    Rule()
                    val betas = state.prefs.offersBetas(state.beta)
                    SettingRow(
                        Icons.Filled.Science,
                        stringResource(R.string.update_betas),
                        subtitle = stringResource(R.string.update_betas_line),
                        onClick = { actions.updateBetas(!betas) },
                        trailing = { Switch(checked = betas, onCheckedChange = actions::updateBetas) },
                    )
                }
            }
        }
    }
}

/** The three things about the wake word that are chosen from a list, each in a sheet. */
enum class WakeChoice { Greeting, Name, Sensitivity }

/**
 * The wake word: off until turned on; the phrase as a greeting and a name (English, words the
 * engine's model knows); how easily it is taken as heard; only while the screen is on; and what
 * it costs and why a phone may stop it, said before it is turned on.
 */
@Composable
private fun WakeWordGroup(state: SettingsState, actions: SettingsActions, onChoose: (WakeChoice) -> Unit) {
    val palette = LocalPalette.current
    val on = state.prefs.wakeWord && state.wakeAvailable
    Group(stringResource(R.string.wake_word)) {
        SettingRow(
            Icons.Filled.Hearing,
            stringResource(R.string.wake_word_listen),
            subtitle = stringResource(if (state.wakeAvailable) R.string.wake_word_line else R.string.wake_word_unavailable),
            enabled = state.wakeAvailable,
            onClick = { actions.wakeWord(!on) },
            trailing = { Switch(checked = on, enabled = state.wakeAvailable, onCheckedChange = actions::wakeWord) },
        )
        if (on) {
            // The truth, not the setting: listening, paused and why, or failed and why. A tap
            // tries again (and asks for the microphone when that is what is missing).
            val wake = state.wakeState
            val trouble = wake is WakeState.Failed || wake == WakeState.Off || wake == WakeState.Paused(WakePause.NoMicrophone)
            Rule()
            SettingRow(
                if (trouble) Icons.Filled.ErrorOutline else Icons.Filled.GraphicEq,
                wakeTitle(LocalContext.current, wake),
                subtitle = when {
                    wake is WakeState.Failed && wake.why != WakeFailure.NoEngine ->
                        listOf(stringResource(R.string.wake_failed_line), wake.detail).filter { it.isNotBlank() }.joinToString("\n")

                    wake == WakeState.Off || wake == WakeState.Paused(WakePause.NoMicrophone) -> stringResource(R.string.wake_tap_to_start)

                    else -> null
                },
                tint = if (trouble) palette.warning else null,
                onClick = { actions.wakeWord(true) },
            )
        }
        if (state.wakeAvailable) {
            val phrase = WakePhrases.byId(state.prefs.wakePhrase)
            Rule()
            SettingRow(
                Icons.Filled.RecordVoiceOver,
                "“${phrase.words}”",
                subtitle = stringResource(R.string.wake_phrase_line),
                onClick = { onChoose(WakeChoice.Name) },
            )
            Rule()
            SettingRow(
                Icons.Filled.RecordVoiceOver,
                stringResource(R.string.wake_greeting),
                subtitle = phrase.greeting.words,
                showIcon = false,
                onClick = { onChoose(WakeChoice.Greeting) },
            )
            Rule()
            SettingRow(
                Icons.Filled.RecordVoiceOver,
                stringResource(R.string.wake_name),
                subtitle = if (phrase.name.id == GOOGLE) phrase.name.words + " · " + stringResource(R.string.wake_name_google_line) else phrase.name.words,
                showIcon = false,
                onClick = { onChoose(WakeChoice.Name) },
            )
            Rule()
            SettingRow(
                Icons.Filled.Tune,
                stringResource(R.string.wake_sensitivity),
                subtitle = stringResource(sensitivityLabel(WakeSensitivity.byId(state.prefs.wakeSensitivity))),
                onClick = { onChoose(WakeChoice.Sensitivity) },
            )
            Rule()
            WakeTestRow(state, on, phrase.words, actions)
            Rule()
            SettingRow(
                Icons.Filled.ScreenLockPortrait,
                stringResource(R.string.wake_screen_only),
                subtitle = stringResource(R.string.wake_screen_only_line),
                onClick = { actions.wakeScreenOnly(!state.prefs.wakeScreenOnly) },
                trailing = { Switch(checked = state.prefs.wakeScreenOnly, onCheckedChange = actions::wakeScreenOnly) },
            )
            Rule()
            SettingRow(
                Icons.Filled.BatteryAlert,
                stringResource(R.string.wake_battery),
                subtitle = stringResource(R.string.wake_battery_line),
                onClick = actions::batterySettings,
            )
        }
    }
    Text(
        stringResource(R.string.wake_word_privacy) + "\n\n" + stringResource(R.string.wake_word_cost),
        style = MaterialTheme.typography.bodySmall,
        color = palette.inkFaint,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/**
 * Phone actions: which navigation app a route goes to (whichever is installed by default; with
 * both, the card asks), and whether OsmAnd may be asked where the phone is and where the running
 * route goes -- which the person allows inside OsmAnd first.
 */
@Composable
private fun PhoneActionsGroup(state: SettingsState, actions: SettingsActions) {
    val palette = LocalPalette.current
    val choices = listOf(
        "" to stringResource(R.string.nav_app_auto),
        NavApp.GoogleMaps.id to stringResource(R.string.nav_app_google),
        NavApp.OsmAnd.id to stringResource(R.string.nav_app_osmand),
        NavApp.PREF_ASK to stringResource(R.string.nav_app_ask),
    )
    Group(stringResource(R.string.nav_app)) {
        choices.forEachIndexed { i, (id, label) ->
            if (i > 0) Rule()
            val installed = id == "" || id == NavApp.PREF_ASK || id in state.navApps
            SettingRow(
                Icons.Filled.Navigation,
                label,
                subtitle = if (installed) null else stringResource(R.string.nav_app_not_installed),
                showIcon = i == 0,
                enabled = installed,
                onClick = { actions.navApp(id) },
                trailing = { RadioButton(selected = state.prefs.navApp == id, enabled = installed, onClick = { actions.navApp(id) }) },
            )
        }
        if (NavApp.OsmAnd.id in state.navApps) {
            Rule()
            SettingRow(
                Icons.Filled.MyLocation,
                stringResource(R.string.osmand_info),
                subtitle = stringResource(R.string.osmand_info_line),
                onClick = { actions.osmandInfo(!state.prefs.osmandInfo) },
                trailing = { Switch(checked = state.prefs.osmandInfo, onCheckedChange = actions::osmandInfo) },
            )
        }
    }
    Text(
        stringResource(R.string.phone_actions_line),
        style = MaterialTheme.typography.bodySmall,
        color = palette.inkFaint,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** Notification summaries: off by default; on needs Android's notification access too, which this explains. */
@Composable
private fun NotificationSummaryGroup(state: SettingsState, actions: SettingsActions) {
    val palette = LocalPalette.current
    val on = state.prefs.notificationSummaries
    Group(stringResource(R.string.notification_summaries)) {
        SettingRow(
            Icons.Filled.Notifications,
            stringResource(R.string.notification_summaries_switch),
            subtitle = stringResource(R.string.notification_summaries_line),
            onClick = { actions.notificationSummaries(!on) },
            trailing = { Switch(checked = on, onCheckedChange = actions::notificationSummaries) },
        )
        if (on) {
            Rule()
            SettingRow(
                Icons.Filled.Security,
                stringResource(R.string.notification_access),
                subtitle = stringResource(if (state.notificationAccess) R.string.notification_access_on else R.string.notification_access_off),
                tint = if (state.notificationAccess) null else palette.warning,
                onClick = actions::notificationAccess,
            )
        }
    }
    Text(
        stringResource(R.string.notification_summaries_privacy),
        style = MaterialTheme.typography.bodySmall,
        color = palette.inkFaint,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    val palette = LocalPalette.current
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = palette.accent,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
    )
    Surface(shape = RoundedCornerShape(20.dp), color = palette.surfaceRaised, contentColor = palette.ink, modifier = Modifier.fillMaxWidth()) {
        Column { content() }
    }
}

@Composable
private fun Rule() = HorizontalDivider(color = LocalPalette.current.border, modifier = Modifier.padding(start = 56.dp))

/**
 * A settings row: an icon in a fixed 24 dp column (or its empty place, so a group's words start
 * on one line), the words that give, and the control that keeps its size.
 */
@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    tint: Color? = null,
    enabled: Boolean = true,
    showIcon: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    val palette = LocalPalette.current
    val ink = if (!enabled) palette.inkFaint else tint ?: palette.ink
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (showIcon) Icon(icon, contentDescription = null, tint = tint ?: palette.inkMuted)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = ink)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = palette.inkMuted) }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/** An instance's initial in a circle, where a picture would be. */
@Composable
private fun Initial(name: String) {
    val palette = LocalPalette.current
    Box(Modifier.size(40.dp).clip(CircleShape).background(palette.accentSoft), contentAlignment = Alignment.Center) {
        Text(name.trim().firstOrNull()?.uppercase() ?: "?", style = MaterialTheme.typography.titleMedium, color = palette.accent)
    }
}

/** The phone's own language, named in itself: what "Phone language" would show. */
private fun phoneLanguage(): String {
    val locale = android.content.res.Resources.getSystem().configuration.locales[0]
    return locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }
}

/** The language row's words: what is chosen, and for the account's, which language that is. */
@Composable
private fun languageLabel(prefs: Prefs): String = when (prefs.language) {
    AppLanguage.ACCOUNT -> AppLanguage.name(prefs.accountLanguage)?.let { stringResource(R.string.language_account_is, it) }
        ?: stringResource(R.string.language_account)

    AppLanguage.PHONE -> stringResource(R.string.language_phone_is, phoneLanguage())

    else -> AppLanguage.name(prefs.language) ?: stringResource(R.string.language_account)
}

/**
 * "Test the wake word": for ten seconds the row says what the wake word is doing and whether the
 * phrase landed ("Heard it"), while the assistant stays shut -- so the phrase, the sensitivity and
 * the place can be tried without a sheet opening each time.
 */
@Composable
private fun WakeTestRow(state: SettingsState, on: Boolean, phrase: String, actions: SettingsActions) {
    val palette = LocalPalette.current
    val test = state.wakeTest
    val heard = stringResource(R.string.wake_test_heard) + if (test.heard > 1) " ×${test.heard}" else ""
    val say = stringResource(R.string.wake_test_say, phrase)
    SettingRow(
        if (test.heard > 0) Icons.Filled.CheckCircle else Icons.Filled.Mic,
        stringResource(R.string.wake_test),
        subtitle = when {
            !on -> stringResource(R.string.wake_test_off)
            test.running && test.heard > 0 -> heard + "\n" + say
            test.running -> say + "\n" + wakeTitle(LocalContext.current, state.wakeState)
            test.done && test.heard > 0 -> heard
            test.done -> stringResource(R.string.wake_test_missed)
            else -> stringResource(R.string.wake_test_line)
        },
        tint = when {
            !on -> null
            test.heard > 0 -> palette.accent
            test.done -> palette.warning
            else -> null
        },
        enabled = on,
        onClick = actions::wakeTest,
    )
}

private const val GOOGLE = "google"

private fun sensitivityLabel(sensitivity: WakeSensitivity): Int = when (sensitivity) {
    WakeSensitivity.Low -> R.string.wake_sensitivity_low
    WakeSensitivity.Normal -> R.string.wake_sensitivity_normal
    WakeSensitivity.High -> R.string.wake_sensitivity_high
}

/**
 * One of the wake word's lists, as a sheet: the greetings, the names (Google's says that a Google
 * device nearby answers to it too), or the sensitivity with what each step trades. A pick is
 * handed back as what to do with the actions.
 */
@Composable
fun WakeChoices(which: WakeChoice, prefs: Prefs, onPick: ((SettingsActions) -> Unit) -> Unit) {
    val phrase = WakePhrases.byId(prefs.wakePhrase)
    val google = stringResource(R.string.wake_name_google_line)
    val title = stringResource(
        when (which) {
            WakeChoice.Greeting -> R.string.wake_greeting
            WakeChoice.Name -> R.string.wake_name
            WakeChoice.Sensitivity -> R.string.wake_sensitivity
        },
    )
    // id, words, a line under them, whether it is the one chosen.
    val choices: List<Choice> = when (which) {
        WakeChoice.Greeting -> WakeWords.greetings.map { Choice(WakePhrase(it, phrase.name).id, it.words, null, it.id == phrase.greeting.id) }

        WakeChoice.Name -> WakeWords.names.map {
            Choice(WakePhrase(phrase.greeting, it).id, it.words, if (it.id == GOOGLE) google else null, it.id == phrase.name.id)
        }

        WakeChoice.Sensitivity -> WakeSensitivity.entries.map {
            val line = when (it) {
                WakeSensitivity.Low -> R.string.wake_sensitivity_low_line
                WakeSensitivity.Normal -> R.string.wake_sensitivity_normal_line
                WakeSensitivity.High -> R.string.wake_sensitivity_high_line
            }
            Choice(it.id, stringResource(sensitivityLabel(it)), stringResource(line), it.id == WakeSensitivity.byId(prefs.wakeSensitivity).id)
        }
    }
    ChoiceList(title, choices) { id ->
        onPick { actions -> if (which == WakeChoice.Sensitivity) actions.wakeSensitivity(id) else actions.wakePhrase(id) }
    }
}

private data class Choice(val id: String, val title: String, val subtitle: String?, val chosen: Boolean)

/** A sheet's list of choices: a title, then rows of words that give and a radio button that keeps its size. */
@Composable
private fun ChoiceList(title: String, choices: List<Choice>, onPick: (String) -> Unit) {
    val palette = LocalPalette.current
    LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp))
        }
        choices.forEach { choice ->
            item(key = choice.id) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(choice.id) }
                        .heightIn(min = 56.dp)
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(choice.title, style = MaterialTheme.typography.bodyLarge)
                        choice.subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = palette.inkMuted) }
                    }
                    Spacer(Modifier.width(12.dp))
                    RadioButton(selected = choice.chosen, onClick = { onPick(choice.id) })
                }
            }
        }
    }
}

/**
 * Settings › Language, as a sheet: the account's language (the default), the phone's, then every
 * language the app has, each named in itself so a person who cannot read the current one still
 * finds theirs.
 */
@Composable
fun LanguageChoices(prefs: Prefs, onPick: (String) -> Unit) {
    val palette = LocalPalette.current
    val choices = buildList {
        add(
            Triple(
                AppLanguage.ACCOUNT,
                stringResource(R.string.language_account),
                AppLanguage.name(prefs.accountLanguage) ?: stringResource(R.string.language_account_unknown),
            ),
        )
        add(Triple(AppLanguage.PHONE, stringResource(R.string.language_phone), phoneLanguage()))
        AppLanguage.LANGUAGES.forEach { (code, name) -> add(Triple(code, name, null)) }
    }
    LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Text(
                stringResource(R.string.settings_language),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
            )
        }
        choices.forEachIndexed { i, (value, title, subtitle) ->
            item(key = "lang-$value-$i") {
                if (i == 2) HorizontalDivider(color = palette.border, modifier = Modifier.padding(vertical = 4.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(value) }
                        .heightIn(min = 56.dp)
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.bodyLarge)
                        subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = palette.inkMuted) }
                    }
                    Spacer(Modifier.width(12.dp))
                    RadioButton(selected = prefs.language == value, onClick = { onPick(value) })
                }
            }
        }
    }
}
