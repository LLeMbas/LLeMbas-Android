package eu.llembas.android.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import eu.llembas.android.ui.theme.LocalPalette
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin

/** Schemes a link from a model may open. Anything else -- `javascript:`, `intent:`, `file:` -- is inert. */
private val OPENABLE = setOf("http", "https", "mailto")

/**
 * Markdown from a model, drawn natively. Model output is untrusted here exactly as on the web
 * page, so the rules are the server's: no images are loaded at all (an image URL can carry what
 * the model read out of a page to somebody else's server), raw HTML is shown as text (there is
 * no HTML plugin), and a link opens outside the app, in the browser, and only for the schemes
 * above.
 */
fun markwon(context: Context, codeBg: Int, link: Int): Markwon = Markwon.builder(context)
    .usePlugin(StrikethroughPlugin.create())
    .usePlugin(TablePlugin.create(context))
    .usePlugin(
        object : AbstractMarkwonPlugin() {
            override fun configureTheme(builder: MarkwonTheme.Builder) {
                builder.codeBackgroundColor(codeBg).codeBlockBackgroundColor(codeBg).linkColor(link)
            }

            override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                builder.linkResolver { view, url ->
                    val uri = Uri.parse(url)
                    if (uri.scheme?.lowercase() in OPENABLE) {
                        try {
                            view.context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (_: ActivityNotFoundException) {
                        }
                    }
                }
            }
        },
    )
    .build()

/**
 * [markdown] drawn by Markwon in a TextView. With [onLongPress] the text is not selectable: a
 * long press is the message's menu instead (which offers Select text); without it, as in the
 * activity panel's room, the text selects as usual.
 */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier, color: Color? = null, onLongPress: (() -> Unit)? = null) {
    val context = LocalContext.current
    val palette = LocalPalette.current
    val ink = (color ?: palette.ink).toArgb()
    val engine = remember(palette) { markwon(context, palette.codeBg.toArgb(), palette.accent.toArgb()) }
    val longPress = rememberUpdatedState(onLongPress)
    val pressable = onLongPress != null
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextView(ctx).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setLineSpacing(0f, 1.2f)
                if (pressable) {
                    setOnLongClickListener {
                        longPress.value?.invoke()
                        true
                    }
                } else {
                    setTextIsSelectable(true)
                }
                movementMethod = LinkMovementMethod.getInstance()
            }
        },
        update = { view ->
            view.setTextColor(ink)
            engine.setMarkdown(view, markdown)
        },
    )
}
