package eu.llembas.android.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * A chat's subagent tree (harness spec 3.4.0, `subagents.md` §12): the main agent at the root,
 * every subagent under its parent, open slots, and the subagents that ended. [agents] are in
 * tree order. Android shows it as a reduced list (§12): name, position, state, the progress
 * line's text and context use, and opens a subagent's conversation read-only.
 */
@Serializable
data class SubagentTree(
    val min: Int = 1,
    val max: Int = 1,
    val size: Int = 0,
    @SerialName("open_slots") val openSlots: Int = 0,
    val agents: List<TreeAgent> = emptyList(),
    val ended: List<TreeAgent> = emptyList(),
)

/** One agent of the tree. [position], [task] and a progress `what` are a model's words: text only. */
@Serializable
data class TreeAgent(
    val id: String = "",
    val name: String = "",
    val position: String = "",
    val parent: String? = null,
    val state: String = "",
    val model: String = "",
    val mode: String = "",
    val request: List<String> = emptyList(),
    val manager: Boolean = false,
    val task: String = "",
    val context: TreeContext? = null,
    val progress: Progress? = null,
    val findings: Int = 0,
    /** How many of the room's messages are to or from it (a count; a newer server may say more). */
    val messages: JsonElement? = null,
    /** When it ended, Unix milliseconds; null while it lives. */
    val ended: Long? = null,
) {
    val isMain: Boolean get() = parent == null

    /** `floor(used × 100 / window)`, as the server's `team_read` writes it; null without a window. */
    val contextPercent: Int? get() = context?.takeIf { it.window > 0 }?.let { (it.used.toLong() * 100 / it.window).toInt() }
}

@Serializable
data class TreeContext(val used: Int = 0, val window: Int = 0)

/** One row of the reduced list: an agent and how deep it sits under the main agent. */
data class TreeRow(val agent: TreeAgent, val depth: Int, val ended: Boolean)

object TreeLogic {
    /** The states a subagent ends in (`subagents.md` §9). */
    val ENDED = setOf("done", "out_of_context", "stopped")

    /**
     * The rows to draw: the live agents in tree order, each as deep as its parent chain (the main
     * agent 0), then the ended ones, flat. A parent the tree does not hold (moved, ended) counts
     * as the main agent, so no row is lost or drawn deeper than it can be.
     */
    fun rows(tree: SubagentTree): List<TreeRow> {
        val byName = tree.agents.associateBy { it.name }
        fun depth(agent: TreeAgent, seen: Set<String> = emptySet()): Int {
            val parent = agent.parent?.let { byName[it] } ?: return if (agent.parent == null) 0 else 1
            if (parent.name in seen) return 1
            return depth(parent, seen + agent.name) + 1
        }
        return tree.agents.map { TreeRow(it, depth(it), ended = false) } + tree.ended.map { TreeRow(it, 1, ended = true) }
    }

    /** Whether there is anything to show beyond the main agent alone. */
    fun hasSubagents(tree: SubagentTree?): Boolean = tree != null && (tree.agents.any { !it.isMain } || tree.ended.isNotEmpty() || tree.openSlots > 0)
}
