package eu.llembas.android.shots

import eu.llembas.android.api.LembasJson
import eu.llembas.android.api.Message
import eu.llembas.android.api.ModelList

/** Made-up data for the screenshots: the server's own contract files where they fit. */
object Fakes {
    private fun text(name: String): String = requireNotNull(javaClass.classLoader!!.getResource("$name.json")).readText()

    val models = LembasJson.decodeFromString(ModelList.serializer(), text("models")).models
    val question = LembasJson.decodeFromString(Message.serializer(), text("message_user"))
    val answer = LembasJson.decodeFromString(Message.serializer(), text("message_assistant"))
}
