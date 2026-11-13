package eu.llembas.android

import eu.llembas.android.api.LiveReply
import eu.llembas.android.api.ModelInfo
import eu.llembas.android.api.Step
import eu.llembas.android.api.Tail
import eu.llembas.android.assist.AssistActivity
import eu.llembas.android.assist.AssistController
import eu.llembas.android.assist.AssistHandOff
import eu.llembas.android.assist.AssistTurn
import eu.llembas.android.assist.AssistUiState
import eu.llembas.android.assist.Held
import eu.llembas.android.assist.LockedStart
import eu.llembas.android.assist.Opening
import eu.llembas.android.assist.Recorder
import eu.llembas.android.assist.SheetStop
import eu.llembas.android.assist.isStopPhrase
import eu.llembas.android.assist.lockedStart
import eu.llembas.android.assist.settle
import eu.llembas.android.assist.usePhoneRecognizer
import eu.llembas.android.auth.Prefs
import eu.llembas.android.notify.Notice
import eu.llembas.android.notify.NotificationReader
import eu.llembas.android.share.shareMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistTest {
    private val half = 1000f
    private val full = 1800f

    @Test
    fun aSlowReleaseGoesToTheNearestStop() {
        assertEquals(SheetStop.Half, settle(1100f, half, full, 0f))
        assertEquals(SheetStop.Full, settle(1500f, half, full, 0f))
        assertEquals(SheetStop.Half, settle(800f, half, full, 0f))
        // Pulled well below the half: it goes.
        assertEquals(SheetStop.Gone, settle(500f, half, full, 0f))
    }

    @Test
    fun aFlingDecidesByItsDirection() {
        assertEquals(SheetStop.Full, settle(1010f, half, full, -3000f))
        // Down from the full height: to the half, not away.
        assertEquals(SheetStop.Half, settle(1700f, half, full, 3000f))
        // Down from the half: away.
        assertEquals(SheetStop.Gone, settle(990f, half, full, 3000f))
    }

    @Test
    fun aTurnShowsWhatIsWrittenThenWhatWasWritten() {
        val asked = AssistTurn("Why?")
        assertFalse(asked.writing)
        val writing = asked.copy(reply = LiveReply("m1", tail = Tail(text = "Because")))
        assertTrue(writing.writing)
        assertEquals("Because", writing.steps.single().markdown)
        val earlier = AssistTurn("Why?", earlier = listOf(Step(kind = "text", markdown = "Because.")))
        assertFalse(earlier.writing)
        assertEquals("Because.", earlier.steps.single().markdown)
    }

    @Test
    fun theAssistantsModelIsTheOneChosenInSettings() {
        val models = listOf(ModelInfo("large"), ModelInfo("standard", default = true), ModelInfo("quick"))
        // The choice, whatever the account's and the instance's defaults are.
        assertEquals("quick", AssistController.assistantModelFor("quick", "large", models))
        // "Instance default": the account's default, as a new chat in the app starts.
        assertEquals("large", AssistController.assistantModelFor("", "large", models))
        // No account default (or one that is gone): the instance's, then its first.
        assertEquals("standard", AssistController.assistantModelFor("", null, models))
        assertEquals("standard", AssistController.assistantModelFor("", "retired", models))
        assertEquals("large", AssistController.assistantModelFor("", null, models.map { it.copy(default = false) }))
        // A chosen model the instance no longer has falls back the same way, and none is none.
        assertEquals("large", AssistController.assistantModelFor("retired", "large", models))
        assertEquals(null, AssistController.assistantModelFor("quick", null, emptyList()))
    }

    @Test
    fun overTheLockScreenAWayInThatMeansToSpeakStaysAndListens() {
        // The wake word with the screen off: nobody is there to unlock. It listens; sending waits.
        assertEquals(LockedStart.Listen, lockedStart(locked = true, Opening.Listen, question = false, micAllowed = true))
        // A question handed over (heard by the assistant's own sheet, or typed there) is held, not dropped.
        assertEquals(LockedStart.Hold, lockedStart(locked = true, Opening.Sheet, question = true, micAllowed = true))
        assertEquals(LockedStart.Hold, lockedStart(locked = true, Opening.Listen, question = true, micAllowed = false))
        // Anything else over the lock screen asks to unlock first, as before: the plain sheet,
        // the last chat (which is the account's), and the microphone's permission prompt.
        assertEquals(LockedStart.Unlock, lockedStart(locked = true, Opening.Sheet, question = false, micAllowed = true))
        assertEquals(LockedStart.Unlock, lockedStart(locked = true, Opening.Continue, question = false, micAllowed = true))
        assertEquals(LockedStart.Unlock, lockedStart(locked = true, Opening.Listen, question = false, micAllowed = false))
        // Unlocked, the way in decides alone.
        for (opening in Opening.entries) {
            assertEquals(LockedStart.Go, lockedStart(locked = false, opening, question = false, micAllowed = true))
            assertEquals(LockedStart.Go, lockedStart(locked = false, opening, question = true, micAllowed = false))
        }
    }

    @Test
    fun onlyTheAppsOwnIntentsAskForMoreThanTheSheet() {
        val voiceButton = "android.intent.action.VOICE_COMMAND"
        val handsFree = "android.speech.action.VOICE_SEARCH_HANDS_FREE"
        // The app's own: the widget's microphone, the last chat, the session's "listen".
        assertEquals(Opening.Listen, Opening.of(AssistActivity.ACTION_LISTEN, listenExtra = false, own = true))
        assertEquals(Opening.Continue, Opening.of(AssistActivity.ACTION_CONTINUE, listenExtra = false, own = true))
        assertEquals(Opening.Listen, Opening.of(null, listenExtra = true, own = true))
        // The same from any other app: just the sheet.
        assertEquals(Opening.Sheet, Opening.of(AssistActivity.ACTION_LISTEN, listenExtra = true, own = false))
        assertEquals(Opening.Sheet, Opening.of(AssistActivity.ACTION_CONTINUE, listenExtra = false, own = false))
        assertEquals(Opening.Sheet, Opening.of("android.intent.action.ASSIST", listenExtra = true, own = false))
        // Android's own actions for a headset's button are anybody's, and listen.
        assertEquals(Opening.Listen, Opening.of(voiceButton, listenExtra = false, own = false))
        assertEquals(Opening.Listen, Opening.of(handsFree, listenExtra = false, own = false))
        assertEquals(Opening.Sheet, Opening.of("android.intent.action.ASSIST", listenExtra = false, own = true))
    }

    @Test
    fun aHandedOverQuestionIsTakenOnceAndOnlyWithItsTicket() {
        val question = Held("How far is it to the next town?", null, spoken = true)
        val ticket = AssistHandOff.leave(question)
        // No ticket, a made-up one, one of another length: nothing.
        assertEquals(null, AssistHandOff.take(null))
        assertEquals(null, AssistHandOff.take("0".repeat(ticket.length)))
        assertEquals(null, AssistHandOff.take(ticket.dropLast(1)))
        // The ticket: the question, once.
        assertTrue(AssistHandOff.take(ticket) === question)
        assertEquals(null, AssistHandOff.take(ticket))
        // A newer question takes the older one's place, and the older ticket is worth nothing.
        val older = AssistHandOff.leave(question)
        val newer = AssistHandOff.leave(Held("", ByteArray(4), spoken = true))
        assertTrue(older != newer)
        assertEquals(null, AssistHandOff.take(older))
        assertTrue(AssistHandOff.take(newer)?.wav != null)
    }

    @Test
    fun aQuestionWaitsAsWordsOrAsARecording() {
        assertFalse(AssistUiState().held)
        assertTrue(AssistUiState(waiting = "Why?").held)
        assertTrue(AssistUiState(waitingRecording = true).held)
    }

    @Test
    fun aVoiceReadsOnlyTheWords() {
        val steps = listOf(
            Step(kind = "thinking", text = "Hmm."),
            Step(kind = "text", markdown = "**Two** days."),
            Step(kind = "tools"),
            Step(kind = "text", markdown = "See [this](https://example.org)."),
        )
        assertEquals("Two days. See this.", AssistController.spokenWords(steps))
    }

    @Test
    fun silenceIsQuietAndAToneIsLoud() {
        assertEquals(0f, Recorder.rms(ByteArray(3200), 3200), 0f)
        // A full-scale square wave: every sample at +-32767.
        val loud = ByteArray(3200) { i ->
            if (i % 2 == 0) {
                0xFF.toByte()
            } else if ((i / 2) % 2 == 0) {
                0x7F
            } else {
                0x80.toByte()
            }
        }
        assertTrue(Recorder.rms(loud, loud.size) > 0.9f)
    }

    @Test
    fun voiceInputChoosesTheSameRecogniserForTheAssistantAndTheChat() {
        // Chosen and present: the phone's.
        assertTrue(usePhoneRecognizer(Prefs.VOICE_PHONE, phoneHas = true, instanceStt = null))
        // Chosen but absent: the instance's, whatever it said.
        assertFalse(usePhoneRecognizer(Prefs.VOICE_PHONE, phoneHas = false, instanceStt = false))
        // LLeMbas chosen: the instance's, unless it said it has no speech-to-text.
        assertFalse(usePhoneRecognizer(Prefs.VOICE_LEMBAS, phoneHas = true, instanceStt = null))
        assertFalse(usePhoneRecognizer(Prefs.VOICE_LEMBAS, phoneHas = true, instanceStt = true))
        assertTrue(usePhoneRecognizer(Prefs.VOICE_LEMBAS, phoneHas = true, instanceStt = false))
    }

    @Test
    fun aShareSendsTheRequestBeforeWhatWasShared() {
        assertEquals("Summarise it.\n\n---\n\nhttps://example.org", shareMessage(" Summarise it. ", "https://example.org\n", "Have a look."))
        assertEquals("Have a look.\n\n---\n\nSome text", shareMessage("", "Some text", "Have a look."))
        // Files only: the request alone, the files go as attachments.
        assertEquals("Have a look.", shareMessage("  ", "", "Have a look."))
    }

    @Test
    fun stopEndsAConversationButAQuestionAboutStoppingDoesNot() {
        val phrases = "stop|that's all|goodbye".split("|")
        assertTrue(isStopPhrase("Stop.", phrases))
        assertTrue(isStopPhrase("OK, that’s all, thanks", phrases))
        assertTrue(isStopPhrase("stop please", phrases))
        assertFalse(isStopPhrase("How do I stop the kettle from whistling in the morning?", phrases))
        assertFalse(isStopPhrase("", phrases))
        assertTrue(isStopPhrase("Ďakujem, to je všetko", listOf("ďakujem to je všetko")))
    }

    @Test
    fun notificationsGoAsShortBlocksAndOnlyWhenAskedFor() {
        val now = 10 * 3_600_000L
        val text = NotificationReader.asText(
            listOf(
                Notice("Mail", "Bilbo", "The party is on Thursday.", now - 5 * 60_000),
                Notice("Weather", "Rain", "", now - 3 * 3_600_000),
            ),
            now,
        )
        assertEquals("[Mail · 5m ago] Bilbo\nThe party is on Thursday.\n\n[Weather · 3h ago] Rain", text)
        val phrases = listOf("my notifications")
        assertTrue(NotificationReader.asksAbout("Anything urgent in My Notifications?", phrases))
        assertFalse(NotificationReader.asksAbout("How do notifications work on Android?", phrases))
    }
}
