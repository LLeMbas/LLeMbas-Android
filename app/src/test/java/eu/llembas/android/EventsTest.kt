package eu.llembas.android

import eu.llembas.android.api.EventsHub
import eu.llembas.android.api.Frame
import eu.llembas.android.api.reopenAtOnce
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The person's events route: one connection for all who watch it, and no tight loop on a route that only closes. */
class EventsTest {
    @Test
    fun twoWatchersShareOneConnectionAndALateOneIsToldTheLastFrame() = runTest {
        var opened = 0
        val one = Frame("reports", """{"unread":1}""")
        val two = Frame("reports", """{"unread":2}""")
        // The server says the state at once to a new connection: the first frame is there to be read.
        val route = MutableSharedFlow<Frame>(replay = 1)
        route.emit(one)
        val hub = EventsHub(backgroundScope)
        val open = {
            flow {
                opened++
                emitAll(route)
            }
        }
        val drawer = Channel<Frame>(Channel.UNLIMITED)
        val list = Channel<Frame>(Channel.UNLIMITED)
        val first = launch { hub.frames("instance", open).collect { drawer.send(it) } }
        assertEquals(one, drawer.receive())
        // A second watcher joins the connection already open, and is told what it missed.
        val second = launch { hub.frames("instance", open).collect { list.send(it) } }
        assertEquals(one, list.receive())
        route.emit(two)
        assertEquals(two, drawer.receive())
        assertEquals(two, list.receive())
        assertEquals(1, opened)
        first.cancel()
        second.cancel()
    }

    @Test
    fun aRouteThatOnlyClosesIsNotAskedInATightLoop() {
        // Closed after it said something: opened again at once, as the activity route means it.
        assertTrue(reopenAtOnce(closed = true, said = true))
        // Closed having said nothing, or dropped: a pause first.
        assertFalse(reopenAtOnce(closed = true, said = false))
        assertFalse(reopenAtOnce(closed = false, said = true))
        assertFalse(reopenAtOnce(closed = false, said = false))
    }
}
