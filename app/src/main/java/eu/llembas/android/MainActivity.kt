package eu.llembas.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import eu.llembas.android.auth.Instance
import eu.llembas.android.auth.Prefs
import eu.llembas.android.push.Notifications
import eu.llembas.android.ui.chat.ChatScreen
import eu.llembas.android.ui.chats.ChatsScreen
import eu.llembas.android.ui.settings.SettingsScreen
import eu.llembas.android.ui.signin.SignInScreen
import eu.llembas.android.ui.theme.LembasTheme
import kotlinx.coroutines.flow.map

private data class Loaded(val instance: Instance?)

class MainActivity : ComponentActivity() {
    /** A chat a notification, the assistant or a share asked to open; consumed once. */
    private val opening = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        take(intent)
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        take(intent)
    }

    private fun take(intent: Intent?) {
        intent?.getStringExtra(Notifications.EXTRA_CHAT)?.let { opening.value = it }
    }

    @Composable
    private fun Root(open: String?, opened: () -> Unit) {
        // null while the store is read; then the instance in use, which may be none.
        val loaded by remember { lembas.instances.active.map { Loaded(it) } }.collectAsState(initial = null)
        val instance = loaded?.instance
        when {
            loaded == null -> Box(Modifier.fillMaxSize())

            instance == null -> SignInScreen()

            else -> key(instance.id) {
                val nav = rememberNavController()
                LaunchedEffect(open) {
                    open?.let {
                        nav.navigate("chat/$it")
                        opened()
                    }
                }
                NavHost(nav, startDestination = "chats") {
                    composable("chats") {
                        ChatsScreen(
                            onOpen = { nav.navigate("chat/$it") },
                            onNew = { nav.navigate("new") },
                            onSettings = { nav.navigate("settings") },
                        )
                    }
                    composable("new") {
                        ChatScreen(
                            chatId = null,
                            onBack = { nav.popBackStack() },
                            onOpened = { id ->
                                nav.navigate("chat/$id") { popUpTo("new") { inclusive = true } }
                            },
                        )
                    }
                    composable("chat/{id}") { entry ->
                        ChatScreen(chatId = entry.arguments?.getString("id"), onBack = { nav.popBackStack() })
                    }
                    composable("settings") {
                        SettingsScreen(onBack = { nav.popBackStack() }, onAddInstance = { nav.navigate("add") })
                    }
                    // Signing in to one more instance makes it the one in use, which starts a
                    // fresh navigation for it (the `key` above).
                    composable("add") { SignInScreen(onDone = { nav.popBackStack("chats", inclusive = false) }) }
                }
            }
        }
    }
}
