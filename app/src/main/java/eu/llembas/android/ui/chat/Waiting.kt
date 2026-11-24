package eu.llembas.android.ui.chat

import eu.llembas.android.api.ApiException
import eu.llembas.android.api.ChatDetail
import eu.llembas.android.api.Message
import eu.llembas.android.api.Turn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * A waiting message was sent now ([turn], the instance's answer): its row is the message as it
 * is after -- no longer waiting -- where it stood, and the reply started for it is the last row.
 * No row is there twice: the list's rows are keyed by id.
 */
fun ChatState.sentNow(turn: Turn): ChatState {
    val sent = turn.message
    val reply = turn.reply
    val rows = messages.filterNot { it.id == reply?.id }.map { if (it.id == sent?.id) sent else it }
    return copy(messages = rows + listOfNotNull(reply), lastSent = sent?.id ?: lastSent)
}

/** A waiting message was discarded: its row is gone, and nothing else moved. */
fun ChatState.discarded(id: String): ChatState = copy(messages = messages.filterNot { it.id == id })

/**
 * Whether a refusal says the screen showed a message as waiting that is not so any more, or
 * could not be acted on as shown: 409 (a reply is being written; the message no longer waits)
 * and 404 (it is not there). The chat is then read again. Anything else -- no network, a server
 * error -- says nothing about the message, and the row stays as it is.
 */
fun staleWaiting(e: Throwable): Boolean = e is ApiException && (e.status == 409 || e.status == 404)

/** A reply to follow, and whether the person asked for it from this screen. */
data class Followed(val reply: String, val own: Boolean)

/**
 * Which reply is followed once a waiting message was sent now: the one the send started
 * ([reply]), as a reply the person asked for from this screen -- unless the chat, [read] after
 * the send, no longer holds it as being written. That happens by design: a linked computer
 * that still knew the message does not run it a second time, and the reply started for it is
 * removed. Then whatever else the read says is being written is followed, as nobody's.
 */
fun followedAfterSend(reply: String?, read: ChatDetail?): Followed? {
    if (reply != null && (read == null || read.live == reply || read.messages.any { it.id == reply && !it.complete })) {
        return Followed(reply, own = true)
    }
    return read?.live?.let { Followed(it, own = false) }
}

/**
 * The two things a person does with a message that waits, as the chat's screen does them. The
 * instance is asked through each action's own request; what the screen shows is [state].
 *
 * Sent: the row stops waiting at once, the chat is read ([read]; a chat on a linked computer
 * sorts the message to where it was sent), and the reply started for it is followed ([follow])
 * exactly as one the person asked for by typing. Discarded: the row is gone. Refused with a
 * 409 or a 404: the instance's sentence is said ([say]) and the chat is read again
 * ([catchUp]), because what the screen showed as waiting is not so any more. While one of the
 * two is under way for a message, [ChatState.acting] names it and its buttons rest.
 */
class WaitingActions(
    private val state: MutableStateFlow<ChatState>,
    private val read: suspend () -> ChatDetail?,
    private val follow: (Followed) -> Unit,
    private val say: (Throwable) -> Unit,
    private val catchUp: suspend () -> Unit,
) {
    suspend fun sendNow(message: Message, send: suspend () -> Turn) = act(message) {
        val turn = send()
        state.update { it.sentNow(turn) }
        followedAfterSend(turn.reply?.id, read())?.let(follow)
    }

    suspend fun discard(message: Message, withdraw: suspend () -> Unit) = act(message) {
        withdraw()
        state.update { it.discarded(message.id) }
    }

    /** One action at a time: a second tap while the first is asked is not a second request. */
    private suspend fun act(message: Message, action: suspend () -> Unit) {
        if (state.value.acting != null) return
        state.update { it.copy(acting = message.id) }
        var stale = false
        try {
            action()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            say(e)
            stale = staleWaiting(e)
        } finally {
            state.update { if (it.acting == message.id) it.copy(acting = null) else it }
        }
        // After the buttons are free again: the read may wait for a reply being written here.
        if (stale) catchUp()
    }
}
