@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package eu.llembas.android.ui.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import eu.llembas.android.R
import eu.llembas.android.api.ApiException
import eu.llembas.android.api.McpServer
import eu.llembas.android.api.signInAddress
import eu.llembas.android.lembas
import eu.llembas.android.ui.files.filesSay
import eu.llembas.android.ui.scheduled.ScheduleLogic
import eu.llembas.android.ui.theme.LocalPalette
import eu.llembas.android.ui.viewer.openOutside
import kotlinx.coroutines.CancellationException

/** The instance's MCP servers as the page draws them: on their way, refused, or the list. */
data class McpState(val servers: List<McpServer> = emptyList(), val loading: Boolean = true, val error: String? = null)

/**
 * Settings › MCP servers: the servers whose tools the person's chats may be offered, and how
 * each is signed in to. One that waits for a sign-in has a button that opens the instance's own
 * sign-in page **in the browser** -- the sign-in happens there, against the instance, with the
 * person signed in to the instance by its cookie. The app starts and finishes nothing, and sees,
 * sends and stores no token of a server; its own token is in no address it opens. The list is
 * read again whenever the page comes back to the front, which is when the browser was left.
 */
@Composable
fun McpScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var state by remember { mutableStateOf(McpState()) }
    var reads by remember { mutableIntStateOf(0) }
    var base by remember { mutableStateOf("") }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { reads++ }
    LaunchedEffect(reads) {
        state = state.copy(loading = true, error = null)
        state = try {
            val api = context.lembas.instances.client() ?: throw ApiException(401, context.getString(R.string.assist_signed_out))
            base = api.base
            McpState(api.mcpServers(), loading = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state.copy(loading = false, error = context.filesSay(e))
        }
    }
    McpContent(
        state,
        onBack = onBack,
        onReload = { reads++ },
        // Only a page of the instance the app is signed in to is ever opened (signInAddress).
        onSignIn = { server -> signInAddress(base, server.auth.signinUrl)?.let { openOutside(context, it) } },
    )
}

/** The words for how a server is signed in to; a state this app does not know is said as the instance says it. */
@Composable
internal fun mcpState(server: McpServer): String = when (server.auth.state) {
    "ready" -> stringResource(R.string.mcp_ready)
    "signed_in" -> stringResource(R.string.mcp_signed_in)
    "signed_out" -> stringResource(R.string.mcp_signed_out)
    "needs_signin" -> stringResource(R.string.mcp_needs_signin)
    else -> server.auth.state
}

/** The page, from [state] alone. Each server is a card: its words stacked, and under them the one button, the card's whole width. */
@Composable
fun McpContent(state: McpState, onBack: () -> Unit = {}, onReload: () -> Unit = {}, onSignIn: (McpServer) -> Unit = {}) {
    val palette = LocalPalette.current
    Scaffold(
        containerColor = palette.bg,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = palette.bg, scrolledContainerColor = palette.bg),
                title = { Text(stringResource(R.string.mcp_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
                },
                actions = { IconButton(onClick = onReload) { Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.reload)) } },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.error != null -> Text(state.error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))

                state.loading && state.servers.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                state.servers.isEmpty() -> Text(
                    stringResource(R.string.mcp_none),
                    color = palette.inkMuted,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )

                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item { Text(stringResource(R.string.mcp_intro), style = MaterialTheme.typography.bodyMedium, color = palette.inkMuted) }
                    items(state.servers, key = { it.id }) { server -> McpCard(server, onSignIn) }
                }
            }
        }
    }
}

@Composable
private fun McpCard(server: McpServer, onSignIn: (McpServer) -> Unit) {
    val palette = LocalPalette.current
    val auth = server.auth
    Column(
        Modifier.fillMaxWidth().border(1.dp, palette.border, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(server.label, style = MaterialTheme.typography.titleMedium, color = palette.ink)
        Text(
            listOf(mcpState(server), stringResource(R.string.mcp_tools, server.tools.toString())).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = if (auth.needsSignIn) palette.danger else palette.inkMuted,
        )
        // Who the provider says signed in: a stranger's text, drawn as plain text.
        if (auth.account.isNotBlank()) {
            Text(stringResource(R.string.mcp_account, auth.account), style = MaterialTheme.typography.bodySmall, color = palette.inkMuted)
        }
        if (!auth.needsSignIn) {
            ScheduleLogic.moment(auth.expiresAt)?.let {
                Text(stringResource(R.string.mcp_until, it), style = MaterialTheme.typography.bodySmall, color = palette.inkMuted)
            }
        }
        if (auth.needsSignIn) {
            if (auth.signinUrl.isNullOrBlank()) {
                // One sign-in for everyone, which only an administrator makes: nothing for this person to do.
                Text(stringResource(R.string.mcp_shared_note), style = MaterialTheme.typography.bodySmall, color = palette.inkMuted)
            } else {
                Text(stringResource(R.string.mcp_browser_note), style = MaterialTheme.typography.bodySmall, color = palette.inkMuted)
                Button(
                    onClick = { onSignIn(server) },
                    colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.accentInk),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.mcp_sign_in)) }
            }
        }
    }
}
