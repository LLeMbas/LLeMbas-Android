package eu.llembas.android

import eu.llembas.android.api.ActivityLogic
import eu.llembas.android.api.LembasJson
import eu.llembas.android.api.SubagentTree
import eu.llembas.android.api.TreeLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The subagent tree (harness spec 3.4.0, `subagents.md` §12) and the reduced list's rows. */
class TreeTest {
    /** §12's own example, with a sub-manager and one under it added. */
    private val tree = LembasJson.decodeFromString(
        SubagentTree.serializer(),
        """
        { "min": 1, "max": 4, "size": 3, "open_slots": 1,
          "agents": [
            { "id": "lead", "name": "lead", "position": "the main agent", "parent": null, "state": "working",
              "model": "qwen-27b", "mode": "edit", "request": [], "manager": true,
              "context": { "used": 41200, "window": 131072 }, "progress": { "phase": "thinking", "since": 1762790400000 } },
            { "id": "s1", "name": "parser", "position": "reviews the parser", "parent": "lead", "state": "working",
              "model": "qwen-27b", "mode": "manual", "request": ["write"], "manager": true, "task": "…",
              "context": { "used": 9100, "window": 131072 },
              "progress": { "phase": "running_tool", "since": 1762790401000, "tool": "read", "what": "src/parse.ts" },
              "findings": 2, "messages": 3 },
            { "id": "s3", "name": "lexer", "position": "the lexer's tests", "parent": "parser", "state": "paused",
              "model": "qwen-27b", "mode": "manual", "request": [], "manager": false, "task": "…", "findings": 0, "messages": 0 }
          ],
          "ended": [ { "id": "s0", "name": "scout", "position": "…", "parent": "lead", "state": "done", "ended": 1762790300000, "findings": 1 } ] }
        """,
    )

    @Test
    fun theTreeReads() {
        assertEquals(1, tree.openSlots)
        val parser = tree.agents[1]
        assertEquals("s1", parser.id)
        assertEquals("running_tool", parser.progress?.phase)
        assertEquals(listOf("write"), parser.request)
        assertTrue(tree.agents[0].isMain)
        assertFalse(parser.isMain)
    }

    @Test
    fun contextIsFlooredAndNeedsAWindow() {
        assertEquals(31, tree.agents[0].contextPercent) // 41200 × 100 / 131072 = 31.43
        assertEquals(6, tree.agents[1].contextPercent)
        assertNull(tree.agents[2].contextPercent)
    }

    @Test
    fun rowsInTreeOrderThenTheEnded() {
        val rows = TreeLogic.rows(tree)
        assertEquals(listOf("lead", "parser", "lexer", "scout"), rows.map { it.agent.name })
        assertEquals(listOf(0, 1, 2, 1), rows.map { it.depth })
        assertEquals(listOf(false, false, false, true), rows.map { it.ended })
    }

    @Test
    fun aParentTheTreeDoesNotHoldIsTheMainAgents() {
        val moved = tree.copy(agents = tree.agents.map { if (it.name == "lexer") it.copy(parent = "gone") else it })
        assertEquals(1, TreeLogic.rows(moved).single { it.agent.name == "lexer" }.depth)
    }

    @Test
    fun theMainAgentAloneIsNoList() {
        assertFalse(TreeLogic.hasSubagents(null))
        assertFalse(TreeLogic.hasSubagents(tree.copy(agents = tree.agents.take(1), ended = emptyList(), openSlots = 0)))
        assertTrue(TreeLogic.hasSubagents(tree.copy(agents = tree.agents.take(1), ended = emptyList(), openSlots = 2)))
        assertTrue(TreeLogic.hasSubagents(tree))
    }

    @Test
    fun aSubagentOpensAsAMember() {
        val agent = ActivityLogic.ofTreeAgent(tree.agents[1])
        assertEquals("member", agent.kind)
        assertEquals("parser", agent.id)
        assertTrue(agent.transcript)
    }
}
