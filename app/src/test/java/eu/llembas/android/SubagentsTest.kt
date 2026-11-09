package eu.llembas.android

import eu.llembas.android.api.Activity
import eu.llembas.android.api.ActivityLogic
import eu.llembas.android.api.AgentsSetting
import eu.llembas.android.api.Chat
import eu.llembas.android.api.ContextUse
import eu.llembas.android.api.LembasJson
import eu.llembas.android.api.ModelInfo
import eu.llembas.android.api.ModelList
import eu.llembas.android.api.SubagentsOffer
import eu.llembas.android.ui.chat.ChatState
import eu.llembas.android.ui.chat.Draft
import eu.llembas.android.ui.chat.offersTeam
import eu.llembas.android.ui.chat.subagentsCap
import eu.llembas.android.ui.chat.subagentsNamedOnly
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Subagents setting by the server's word (harness spec 3.3.0, `subagents.md` §4): shown
 * when it says subagents are offered, with its note when only a named model could serve, up to
 * the instance's cap -- and, from a server that says none of it, the app's old guess.
 */
class SubagentsTest {
    private val serving = ModelInfo(id = "qwen", tools = true, parallel = 4)

    @Test
    fun theServersWordWinsOverTheGuess() {
        // The guess would offer them (tools, four at once); the server says no.
        val refused = serving.copy(subagents = SubagentsOffer(offered = false))
        assertFalse(refused.mayHaveTeam)
        // The guess would not (one at a time); the server offers them for a named model.
        val oneAtATime = serving.copy(parallel = 1, subagents = SubagentsOffer(offered = true, namedOnly = true))
        assertTrue(oneAtATime.mayHaveTeam)
        // An older server: the guess.
        assertTrue(serving.mayHaveTeam)
        assertFalse(serving.copy(parallel = 1).mayHaveTeam)
    }

    @Test
    fun aNewChatAsksItsModel() {
        val state = ChatState(models = listOf(serving.copy(parallel = 1, subagents = SubagentsOffer(offered = true, namedOnly = true))), modelsCap = 8)
        assertTrue(state.offersTeam(agent = false))
        assertFalse(state.offersTeam(agent = true))
        assertTrue(state.subagentsNamedOnly)
        assertEquals(8, state.subagentsCap)
    }

    @Test
    fun anOpenChatAsksItself() {
        val chat = Chat(id = "c1", model = "qwen", subagents = SubagentsOffer(offered = false, cap = 6))
        // The activity's older word says yes; the chat's own says no, and wins.
        val state = ChatState(chat = chat, models = listOf(serving), teamOffered = true, modelsCap = 8)
        assertFalse(state.offersTeam(agent = false))
        assertEquals(6, state.subagentsCap)
        assertFalse(state.subagentsNamedOnly)
        // A chat from an older server: the activity's word, and the spec's cap.
        val older = ChatState(chat = chat.copy(subagents = null), teamOffered = true, draft = Draft())
        assertTrue(older.offersTeam(agent = false))
        assertEquals(AgentsSetting.CAP, older.subagentsCap)
    }

    @Test
    fun theNewFieldsParseAndTheOldOnesStillDo() {
        val list = LembasJson.decodeFromString(
            ModelList.serializer(),
            """{"models": [{"id": "qwen", "subagents": {"offered": true, "named_only": false}}], "subagents_cap": 12}""",
        )
        assertEquals(12, list.subagentsCap)
        assertEquals(SubagentsOffer(offered = true), list.models.single().subagents)
        val bare = LembasJson.decodeFromString(ModelList.serializer(), """{"models": [{"id": "qwen"}]}""")
        assertNull(bare.subagentsCap)
        assertNull(bare.models.single().subagents)

        val activity = LembasJson.decodeFromString(
            Activity.serializer(),
            """{"team_offered": true, "subagents_offered": false,
               "context": {"used": 1000, "limit": 10000, "threshold": 95, "auto_compact": true, "until_compact": 8000, "compactable": false}}""",
        )
        assertEquals(false, activity.subagentsOffered)
        assertEquals(8000, ActivityLogic.untilAutoCompact(activity.context!!))
        assertEquals(false, activity.context!!.compactable)
    }

    @Test
    fun autoCompactOffIsOffWhateverTheThreshold() {
        val context = ContextUse(used = 1000, limit = 10000, threshold = 95, autoCompact = false, untilCompact = 8500)
        assertNull(ActivityLogic.untilAutoCompact(context))
        // Without the server's facts: worked out from the threshold.
        assertEquals(8500, ActivityLogic.untilAutoCompact(ContextUse(used = 1000, limit = 10000, threshold = 95)))
    }
}
