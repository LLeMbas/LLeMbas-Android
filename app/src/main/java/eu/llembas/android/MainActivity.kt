package eu.llembas.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import eu.llembas.android.api.Chat
import eu.llembas.android.auth.Instance
import eu.llembas.android.auth.Prefs
import eu.llembas.android.push.Notifications
import eu.llembas.android.push.Push
import eu.llembas.android.ui.chat.ChatScreen
import eu.llembas.android.ui.files.FilesScreen
import eu.llembas.android.ui.home.ArchivedScreen
import eu.llembas.android.ui.home.ChatsViewModel
import eu.llembas.android.ui.home.DrawerActions
import eu.llembas.android.ui.home.DrawerContent
import eu.llembas.android.ui.scheduled.RunScreen
import eu.llembas.android.ui.scheduled.ScheduleEditScreen
import eu.llembas.android.ui.scheduled.ScheduleScreen
import eu.llembas.android.ui.scheduled.ScheduledScreen
import eu.llembas.android.ui.scheduled.SchedulingModelsScreen
import eu.llembas.android.ui.settings.SettingsScreen
import eu.llembas.android.ui.signin.SignInScreen
import eu.llembas.android.ui.theme.LembasTheme
import eu.llembas.android.update.ReleaseCheck
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private data class Loaded(val instance: Instance?)

/** Where a notification (or the assistant, or a share) asked the app to open. */
private data class Opening(val chat: String?, val schedule: String?, val run: String? = null, val instance: String? = null)

class MainActivity : ComponentActivity() {
    /** A chat or a schedule a notification, the assistant or a share asked to open; consumed once. */
    private val opening = mutableStateOf<Opening?>(null)

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLanguage.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        take(intent)
        followLanguage()
        // At most once a day, and only while Settings › About says so (update/ReleaseCheck.kt).
        lifecycleScope.launch { runCatching { ReleaseCheck(this@MainActivity).daily() } }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent {
            val prefs by lembas.instances.prefs.collectAsState(initial = Prefs())
            LembasTheme(prefs.theme) {
                Surface(Modifier.fillMaxSize()) { Root(opening.value) { opening.value = null } }
            }
        }
    }

    /**
     * Keep the app in the language Settings › Language says: the account's by default. A
     * language chosen for the app in the system's own settings (Android 13+) since the app last
     * set one is the person's choice, and is kept as such.
     */
    private fun followLanguage() {
        lifecycleScope.launch {
            val store = lembas.instances
            val system = AppLanguage.systemChoice(this@MainActivity)
            if (system != null && system != AppLanguage.applied(this@MainActivity).orEmpty()) {
                store.savePrefs { it.copy(language = system.ifEmpty { AppLanguage.PHONE }) }
            }
            store.prefs
                .map { AppLanguage.effective(it.language, it.accountLanguage) }
                .distinctUntilChanged()
                .collect { AppLanguage.apply(applicationContext, it, this@MainActivity) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        take(intent)
    }

    private fun take(intent: Intent?) {
        val chat = intent?.getStringExtra(Notifications.EXTRA_CHAT)
        val schedule = intent?.getStringExtra(Notifications.EXTRA_SCHEDULE)
        val run = intent?.getStringExtra(Notifications.EXTRA_RUN)
        val instance = intent?.getStringExtra(Notifications.EXTRA_INSTANCE)
        if (chat == null && schedule == null) return
        opening.value = Opening(chat, schedule, run, instance)
        // A notification from another of the person's instances: that one comes into use, and
        // its pages (keyed by the instance) open what was asked.
        if (instance != null) {
            lifecycleScope.launch {
                if (lembas.instances.active.first()?.id != instance && Push.instance(this@MainActivity, instance) != null) {
                    lembas.instances.use(instance)
                }
            }
        }
    }

    @Composable
    private fun Root(open: Opening?, opened: () -> Unit) {
        // null while the store is read; then the instance in use, which may be none.
        val loaded by remember { lembas.instances.active.map { Loaded(it) } }.collectAsState(initial = null)
        val instance = loaded?.instance
        when {
            loaded == null -> Box(Modifier.fillMaxSize())
            instance == null -> SignInScreen()
            else -> key(instance.id) { Home(instance, open, opened) }
        }
    }

    /**
     * The signed-in app: a new chat first, as the AI apps open, with every chat, Files,
     * Scheduled and Settings in the navigation drawer. A chat or a new chat is a top-level
     * page (opening one replaces the last; Back leaves the app); Files, Scheduled and
     * Settings open over it and Back returns.
     */
    @Composable
    private fun Home(instance: Instance, open: Opening?, opened: () -> Unit) {
        val nav = rememberNavController()
        val drawer = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        val chats: ChatsViewModel = viewModel()
        val list by chats.state.collectAsStateWithLifecycle()
        val entry by nav.currentBackStackEntryAsState()
        val route = entry?.destination?.route
        val current = entry?.arguments?.getString("id")?.takeIf { route == "chat/{id}" }
        val top: (String) -> Unit = { target ->
            scope.launch { drawer.close() }
            nav.navigate(target) {
                popUpTo(nav.graph.id) { inclusive = true }
                launchSingleTop = true
            }
        }
        val over: (String) -> Unit = { target ->
            scope.launch { drawer.close() }
            nav.navigate(target)
        }
        // The account's language, which the app follows unless Settings › Language says
        // otherwise. Read once per instance in use; kept, so the next start has it at once.
        LaunchedEffect(instance.id) {
            runCatching { lembas.instances.clientFor(instance).me() }.onSuccess { me ->
                me.accountLanguage?.let { language -> lembas.instances.savePrefs { it.copy(accountLanguage = language) } }
            }
        }
        // The list is read again each time the drawer opens, so a new title or a finished
        // reply's dot is there without a pull.
        LaunchedEffect(drawer.isOpen) { if (drawer.isOpen) chats.load() }
        LaunchedEffect(open) {
            open?.let {
                // Meant for another instance: left for that one's pages, once it is in use.
                if (it.instance != null && it.instance != instance.id) return@let
                when {
                    it.chat != null -> top("chat/${it.chat}")

                    it.schedule != null -> {
                        over("schedule/${it.schedule}")
                        it.run?.let { run -> over("schedule/${it.schedule}/run/$run") }
                    }
                }
                opened()
            }
        }
        val actions = remember {
            object : DrawerActions {
                override fun newChat() = top("new")

                override fun newAgentChat() = top("new-agent")

                override fun open(chatId: String) = top("chat/$chatId")

                override fun archive(chat: Chat) = chats.archive(chat)

                override fun files() = over("files")

                override fun scheduled() = over("scheduled")

                override fun archived() = over("archived")

                override fun settings() = over("settings")

                override fun more() = chats.more()

                override fun retry() = chats.load()
            }
        }
        val menu: () -> Unit = { scope.launch { drawer.open() } }
        ModalNavigationDrawer(
            drawerState = drawer,
            // The drawer opens by a swipe only from a chat: elsewhere the swipe is Back's.
            gesturesEnabled = drawer.isOpen || route == "new" || route == "new-agent" || route == "chat/{id}",
            drawerContent = {
                DrawerContent(list, current, listOf(instance.name, instance.email).filter { it.isNotBlank() }.joinToString(" · "), actions)
            },
        ) {
            NavHost(nav, startDestination = "new") {
                composable("new") {
                    ChatScreen(
                        chatId = null,
                        onKind = { agent -> if (agent) top("new-agent") },
                        onMenu = menu,
                        onNewChat = { top("new") },
                        onLeave = { top("new") },
                        onOpened = { id -> top("chat/$id") },
                    )
                }
                composable("new-agent") {
                    ChatScreen(
                        chatId = null,
                        agent = true,
                        onKind = { agent -> if (!agent) top("new") },
                        onMenu = menu,
                        onNewChat = { top("new") },
                        onLeave = { top("new") },
                        onOpened = { id -> top("chat/$id") },
                    )
                }
                composable("chat/{id}") { e ->
                    ChatScreen(
                        chatId = e.arguments?.getString("id"),
                        onMenu = menu,
                        onNewChat = { top("new") },
                        onLeave = { top("new") },
                    )
                }
                composable("files") { FilesScreen(onBack = { nav.popBackStack() }) }
                composable("archived") {
                    ArchivedScreen(onBack = { nav.popBackStack() }, onOpen = { top("chat/$it") }, onRestored = { chats.load() })
                }
                composable("scheduled") {
                    ScheduledScreen(
                        onBack = { nav.popBackStack() },
                        onOpen = { over("schedule/$it") },
                        onNew = { over("schedule-new") },
                        onModels = { over("scheduled-models") },
                    )
                }
                composable("scheduled-models") {
                    SchedulingModelsScreen(onBack = { nav.popBackStack() })
                }
                composable("schedule/{id}") { e ->
                    val id = e.arguments?.getString("id").orEmpty()
                    ScheduleScreen(
                        id,
                        onBack = { nav.popBackStack() },
                        onEdit = { over("schedule-edit/$it") },
                        onOpenRun = { run -> over("schedule/$id/run/$run") },
                    )
                }
                composable("schedule/{id}/run/{run}") { e ->
                    RunScreen(
                        e.arguments?.getString("id").orEmpty(),
                        e.arguments?.getString("run").orEmpty(),
                        onBack = { nav.popBackStack() },
                        onOpenChat = { top("chat/$it") },
                    )
                }
                composable("schedule-new") {
                    ScheduleEditScreen(null, onBack = { nav.popBackStack() }, onSaved = { id ->
                        nav.navigate("schedule/$id") { popUpTo("schedule-new") { inclusive = true } }
                    })
                }
                composable("schedule-edit/{id}") { e ->
                    ScheduleEditScreen(e.arguments?.getString("id"), onBack = { nav.popBackStack() }, onSaved = { nav.popBackStack() })
                }
                composable("settings") {
                    SettingsScreen(onBack = { nav.popBackStack() }, onAddInstance = { over("add") })
                }
                // Signing in to one more instance makes it the one in use, which starts a
                // fresh navigation for it (the `key` above).
                composable("add") { SignInScreen(onDone = { nav.popBackStack() }) }
            }
        }
    }
}
