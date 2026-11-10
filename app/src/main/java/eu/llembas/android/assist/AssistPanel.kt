package eu.llembas.android.assist

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

/**
 * The assistant's sheet with its [controller] behind it: [AssistSheetFrame] over the current app,
 * [AssistSheetContent] in it. The session and [AssistActivity] differ only in what the four
 * callbacks do: over the lock screen nothing is sent before Android unlocks ([onUnlockAndAsk]);
 * the microphone's permission prompt needs an activity ([onNeedMic]).
 */
@Composable
fun AssistPanel(
    controller: AssistController,
    locked: Boolean,
    onUnlockAndAsk: (String) -> Unit,
    onOpenApp: () -> Unit,
    onNeedMic: () -> Unit,
    onDismiss: () -> Unit,
) {
    val state by controller.state.collectAsState()
    AssistSheetFrame(onDismiss = onDismiss) { handle, _ ->
        AssistSheetContent(
            state = state,
            actions = controller,
            locked = locked,
            handle = handle,
            onOpenApp = onOpenApp,
            onSend = { text -> if (locked) onUnlockAndAsk(text) else controller.ask(text) },
            onMic = {
                when {
                    locked -> onUnlockAndAsk("")
                    controller.micAllowed() -> controller.listen()
                    else -> onNeedMic()
                }
            },
        )
    }
}
