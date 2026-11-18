package eu.llembas.android.ui.chat

import eu.llembas.android.api.DeviceFile
import eu.llembas.android.api.Mention

// The composer's `@`: what is being asked for is the `@word` the text ends with. The field's
// cursor is not followed -- a mention is offered for what is typed last, which is where typing
// on a phone is.

/** A file on a linked device, as the `@` list holds it beside the server's kinds: put into the text as `@path`, never attached. */
const val MENTION_DEVICE = "device"

/**
 * The `@` word [text] ends with, without the `@` (empty right after it), or null when it ends
 * with none: the `@` must open a word (so an e-mail address is not one), and the word holds no
 * space yet.
 */
fun mentionQuery(text: String): String? {
    val at = text.lastIndexOf('@')
    if (at < 0 || (at > 0 && !text[at - 1].isWhitespace())) return null
    return text.substring(at + 1).takeIf { word -> word.none { it.isWhitespace() } }
}

/** [text] without the `@word` it ends with: what stays when the mention became an attachment. */
fun withoutMention(text: String): String = if (mentionQuery(text) == null) text else text.substring(0, text.lastIndexOf('@'))

/** [text] with the `@word` it ends with made `@path` and a space: a device's file, which the device reads. */
fun withMention(text: String, path: String): String = if (mentionQuery(text) == null) text else text.substring(0, text.lastIndexOf('@')) + "@" + path + " "

/** A device's file as an entry of the `@` list: its name, and its path as the detail. */
fun DeviceFile.asMention(): Mention = Mention(kind = MENTION_DEVICE, path = path, name = name.ifBlank { path }, detail = if (kind == "dir") "$path/" else path)
