package eu.llembas.android.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import eu.llembas.android.api.ApiClient
import eu.llembas.android.api.LembasJson
import eu.llembas.android.wake.WakeAttempt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** One instance this phone is signed in to. The token is stored encrypted, never in the clear. */
@Serializable
data class Instance(val id: String, val base: String, val name: String, val email: String, val sealedToken: String)

/**
 * The token's lock: an AES key that lives in the Android Keystore and never leaves it. A copy
 * of the app's data -- a backup, a rooted read of the files -- carries ciphertext only.
 */
object TokenCipher {
    private const val ALIAS = "lembas-token-key"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    fun seal(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    fun open(sealed: String): String {
        val bytes = Base64.decode(sealed, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        }
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }
}

private val Context.store by preferencesDataStore(name = "instances")
private val INSTANCES = stringPreferencesKey("instances")
private val ACTIVE = stringPreferencesKey("active")
private val DEFAULT = stringPreferencesKey("default")
private val PREFS = stringPreferencesKey("prefs")

/** Choices kept per instance (assistant model, last assistant chat, account language). */
@Serializable
data class PerInstancePrefs(
    val assistantModel: String = "",
    val lastAssistChat: String = "",
    val lastAssistTitle: String = "",
    val lastAssistAt: Long = 0,
    val accountLanguage: String = "",
)

/**
 * Pure selection and mutation rules for [InstanceStore]: kept free of Android types so unit
 * tests exercise default fallback, multi-account ids and removal directly.
 */
object InstanceLogic {
    fun pickDefault(instances: List<Instance>, defaultId: String?): Instance? =
        instances.firstOrNull { it.id == defaultId } ?: instances.firstOrNull()

    fun pickActive(instances: List<Instance>, activeId: String?, defaultId: String?): Instance? =
        instances.firstOrNull { it.id == activeId } ?: pickDefault(instances, defaultId)

    /** Adding [added] replaces any existing entry with the same id, makes it active, and sets it
     *  as default only when no surviving default exists yet. */
    fun afterAdd(
        instances: List<Instance>,
        added: Instance,
        currentDefault: String?,
    ): Triple<List<Instance>, String, String> {
        val next = instances.filter { it.id != added.id } + added
        val keptDefault = next.firstOrNull { it.id == currentDefault }?.id ?: added.id
        return Triple(next, added.id, keptDefault)
    }

    /** Removing [removedId] promotes the surviving default (or first) for active, and the
     *  surviving active (or first) for default, or null when no instance remains. */
    fun afterRemove(
        instances: List<Instance>,
        removedId: String,
        currentActive: String?,
        currentDefault: String?,
    ): Triple<List<Instance>, String?, String?> {
        val remaining = instances.filter { it.id != removedId }
        val nextDefault = remaining.firstOrNull { it.id == currentDefault }?.id
            ?: remaining.firstOrNull { it.id == currentActive }?.id
            ?: remaining.firstOrNull()?.id
        val nextActive = remaining.firstOrNull { it.id == currentActive }?.id
            ?: nextDefault
        return Triple(remaining, nextActive, nextDefault)
    }
}

/**
 * What the person chose in Settings, for this phone. [language]: "" the account's language (the
 * default), "system" the phone's, or a language's code -- see [eu.llembas.android.AppLanguage].
 * [accountLanguage] is the account's language as the instance in use last said it.
 *
 * The assistant's: [voiceInput] `lembas` (the instance's speech-to-text) or `phone` (the phone's
 * recogniser); [assistantListens] whether it opens listening; [assistantModel] the model it
 * starts chats on ("" the account's default); and the last chat it made, which its sheet
 * offers to continue ([lastAssistChat], its first question, when).
 */
@Serializable
data class Prefs(
    val theme: String = "",
    val assistantTemporary: Boolean = false,
    val readAloudSpoken: Boolean = true,
    val language: String = "",
    val accountLanguage: String = "",
    val voiceInput: String = VOICE_LEMBAS,
    val assistantListens: Boolean = true,
    /** After a spoken answer is read aloud, listen again: a spoken conversation. */
    val assistantConversation: Boolean = true,
    /** Listen for a wake word on the phone (WakeWordService). Off until the person turns it on. */
    val wakeWord: Boolean = false,
    /** Which of WakePhrases.all, as `greeting_name`; an id that is no phrase any more reads as the default. */
    val wakePhrase: String = "hey_assistant",
    /** WakeSensitivity's id: low, normal or high. */
    val wakeSensitivity: String = "normal",
    /** What happened the last times the wake word was heard (WakeLog): shown in Settings, nothing of the account in it. */
    val wakeLog: List<WakeAttempt> = emptyList(),
    /** Listen only while the screen is on. */
    val wakeScreenOnly: Boolean = true,
    /** The assistant may read the notifications when asked (with Android's notification access). */
    val notificationSummaries: Boolean = false,
    /** Phone actions' navigation app: "" whichever is installed, "google", "osmand", or "ask". */
    val navApp: String = "",
    /** Ask OsmAnd for the phone's position and the route's destination (allowed inside OsmAnd first). */
    val osmandInfo: Boolean = false,
    val assistantModel: String = "",
    val lastAssistChat: String = "",
    val lastAssistTitle: String = "",
    val lastAssistAt: Long = 0,
    /** Settings › About: look for new versions (null: on in a beta, off in a release). */
    val updateCheck: Boolean? = null,
    /** ... and include betas (null: on in a beta, off in a release). */
    val updateBetas: Boolean? = null,
    val updateCheckedAt: Long = 0,
    /** The last version a notification announced, so it is announced once. */
    val updateNotified: String = "",
    /** Per-instance choices, keyed by [Instance.id]. */
    val perInstance: Map<String, PerInstancePrefs> = emptyMap(),
) {
    fun forInstance(id: String?): PerInstancePrefs =
        id?.let { perInstance[it] } ?: PerInstancePrefs(
            assistantModel = assistantModel,
            lastAssistChat = lastAssistChat,
            lastAssistTitle = lastAssistTitle,
            lastAssistAt = lastAssistAt,
            accountLanguage = accountLanguage,
        )

    fun withInstance(id: String?, change: (PerInstancePrefs) -> PerInstancePrefs): Prefs {
        val updated = change(forInstance(id))
        return if (id == null) {
            copy(
                assistantModel = updated.assistantModel,
                lastAssistChat = updated.lastAssistChat,
                lastAssistTitle = updated.lastAssistTitle,
                lastAssistAt = updated.lastAssistAt,
                accountLanguage = updated.accountLanguage,
            )
        } else {
            copy(perInstance = perInstance + (id to updated))
        }
    }

    fun withoutInstance(id: String): Prefs = if (id in perInstance) copy(perInstance = perInstance - id) else this

    companion object {
        const val VOICE_LEMBAS = "lembas"
        const val VOICE_PHONE = "phone"
    }
}

class InstanceStore(private val context: Context) {
    private val listSerializer = ListSerializer(Instance.serializer())

    val instances: Flow<List<Instance>> = context.store.data.map { prefs ->
        prefs[INSTANCES]?.let { runCatching { LembasJson.decodeFromString(listSerializer, it) }.getOrNull() } ?: emptyList()
    }

    val defaultInstance: Flow<Instance?> = context.store.data.map { prefs ->
        val all = prefs[INSTANCES]?.let { runCatching { LembasJson.decodeFromString(listSerializer, it) }.getOrNull() } ?: emptyList()
        InstanceLogic.pickDefault(all, prefs[DEFAULT])
    }

    val active: Flow<Instance?> = context.store.data.map { prefs ->
        val all = prefs[INSTANCES]?.let { runCatching { LembasJson.decodeFromString(listSerializer, it) }.getOrNull() } ?: emptyList()
        InstanceLogic.pickActive(all, prefs[ACTIVE], prefs[DEFAULT])
    }

    val prefs: Flow<Prefs> = context.store.data.map { p ->
        p[PREFS]?.let { runCatching { LembasJson.decodeFromString(Prefs.serializer(), it) }.getOrNull() } ?: Prefs()
    }

    suspend fun current(): Instance? = active.first()

    suspend fun default(): Instance? = defaultInstance.first()

    suspend fun client(): ApiClient? = current()?.let { clientFor(it) }

    suspend fun defaultClient(): ApiClient? = default()?.let { clientFor(it) }

    fun clientFor(instance: Instance): ApiClient = ApiClient(instance.base, TokenCipher.open(instance.sealedToken))

    suspend fun add(base: String, name: String, email: String, token: String): Instance {
        val instance = Instance(
            id = "$email@$base",
            base = base,
            name = name,
            email = email,
            sealedToken = TokenCipher.seal(token),
        )
        context.store.edit { prefs ->
            val all = prefs[INSTANCES]?.let { runCatching { LembasJson.decodeFromString(listSerializer, it) }.getOrNull() } ?: emptyList()
            val (next, activeId, defaultId) = InstanceLogic.afterAdd(all, instance, prefs[DEFAULT])
            prefs[INSTANCES] = LembasJson.encodeToString(listSerializer, next)
            prefs[ACTIVE] = activeId
            prefs[DEFAULT] = defaultId
        }
        return instance
    }

    suspend fun use(id: String) {
        context.store.edit { it[ACTIVE] = id }
    }

    suspend fun setDefault(id: String) {
        context.store.edit { prefs ->
            val all = prefs[INSTANCES]?.let { runCatching { LembasJson.decodeFromString(listSerializer, it) }.getOrNull() } ?: emptyList()
            if (all.any { it.id == id }) prefs[DEFAULT] = id
        }
    }

    /** On a cold start with no notification target, switch active back to the default instance. */
    suspend fun resetToDefault() {
        context.store.edit { prefs ->
            val all = prefs[INSTANCES]?.let { runCatching { LembasJson.decodeFromString(listSerializer, it) }.getOrNull() } ?: emptyList()
            InstanceLogic.pickDefault(all, prefs[DEFAULT])?.let { prefs[ACTIVE] = it.id }
        }
    }

    suspend fun remove(id: String) {
        context.store.edit { prefs ->
            val all = prefs[INSTANCES]?.let { runCatching { LembasJson.decodeFromString(listSerializer, it) }.getOrNull() } ?: emptyList()
            val (remaining, nextActive, nextDefault) = InstanceLogic.afterRemove(all, id, prefs[ACTIVE], prefs[DEFAULT])
            prefs[INSTANCES] = LembasJson.encodeToString(listSerializer, remaining)
            if (nextActive != null) prefs[ACTIVE] = nextActive else prefs.remove(ACTIVE)
            if (nextDefault != null) prefs[DEFAULT] = nextDefault else prefs.remove(DEFAULT)
            prefs[PREFS]?.let { raw ->
                runCatching { LembasJson.decodeFromString(Prefs.serializer(), raw) }.getOrNull()?.let { current ->
                    prefs[PREFS] = LembasJson.encodeToString(Prefs.serializer(), current.withoutInstance(id))
                }
            }
        }
    }

    suspend fun savePrefs(change: (Prefs) -> Prefs) {
        context.store.edit { p ->
            val now = p[PREFS]?.let { runCatching { LembasJson.decodeFromString(Prefs.serializer(), it) }.getOrNull() } ?: Prefs()
            p[PREFS] = LembasJson.encodeToString(Prefs.serializer(), change(now))
        }
    }
}
