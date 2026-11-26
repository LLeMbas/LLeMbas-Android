package eu.llembas.android

import eu.llembas.android.auth.Instance
import eu.llembas.android.auth.InstanceLogic
import eu.llembas.android.auth.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InstanceStoreTest {
    private val home = Instance(id = "alice@https://home.example", base = "https://home.example", name = "Home", email = "alice", sealedToken = "t1")
    private val work = Instance(id = "bob@https://work.example", base = "https://work.example", name = "Work", email = "bob", sealedToken = "t2")
    private val homeAlt = Instance(id = "carol@https://home.example", base = "https://home.example", name = "Home", email = "carol", sealedToken = "t3")

    @Test
    fun firstAddedBecomesDefaultAndSecondKeepsIt() {
        val (list1, active1, default1) = InstanceLogic.afterAdd(emptyList(), home, null)
        assertEquals(listOf(home), list1)
        assertEquals(home.id, active1)
        assertEquals(home.id, default1)

        val (list2, active2, default2) = InstanceLogic.afterAdd(list1, work, default1)
        assertEquals(listOf(home, work), list2)
        assertEquals(work.id, active2)
        assertEquals(home.id, default2)

        // Two accounts on the same server keep distinct ids.
        val (list3, active3, default3) = InstanceLogic.afterAdd(list2, homeAlt, default2)
        assertEquals(listOf(home, work, homeAlt), list3)
        assertEquals(homeAlt.id, active3)
        assertEquals(home.id, default3)
    }

    @Test
    fun activeFallsBackToDefaultThenFirst() {
        val all = listOf(home, work)
        assertEquals(work, InstanceLogic.pickActive(all, work.id, home.id))
        assertEquals(work, InstanceLogic.pickActive(all, null, work.id))
        assertEquals(home, InstanceLogic.pickActive(all, "gone", null))
        assertNull(InstanceLogic.pickActive(emptyList(), home.id, home.id))
    }

    @Test
    fun removingDefaultOrActivePromotesSurvivingInstance() {
        val all = listOf(home, work)
        val (rem1, active1, default1) = InstanceLogic.afterRemove(all, home.id, home.id, home.id)
        assertEquals(listOf(work), rem1)
        assertEquals(work.id, active1)
        assertEquals(work.id, default1)

        val (rem2, active2, default2) = InstanceLogic.afterRemove(all, work.id, work.id, home.id)
        assertEquals(listOf(home), rem2)
        assertEquals(home.id, active2)
        assertEquals(home.id, default2)

        val (rem3, active3, default3) = InstanceLogic.afterRemove(listOf(home), home.id, home.id, home.id)
        assertEquals(emptyList<Instance>(), rem3)
        assertNull(active3)
        assertNull(default3)
    }

    @Test
    fun perInstancePrefsStayIsolated() {
        val base = Prefs(assistantModel = "legacy-model", lastAssistChat = "legacy-chat")
        // Unknown instance falls back to legacy flat fields.
        assertEquals("legacy-model", base.forInstance(home.id).assistantModel)

        val updated = base
            .withInstance(home.id) { it.copy(assistantModel = "fast-home", lastAssistChat = "c-home") }
            .withInstance(work.id) { it.copy(assistantModel = "fast-work", lastAssistChat = "c-work") }
        assertEquals("fast-home", updated.forInstance(home.id).assistantModel)
        assertEquals("c-home", updated.forInstance(home.id).lastAssistChat)
        assertEquals("fast-work", updated.forInstance(work.id).assistantModel)
        assertEquals("c-work", updated.forInstance(work.id).lastAssistChat)

        val cleaned = updated.withoutInstance(home.id)
        assertEquals("fast-work", cleaned.forInstance(work.id).assistantModel)
        assertEquals("legacy-model", cleaned.forInstance(home.id).assistantModel)
    }
}
