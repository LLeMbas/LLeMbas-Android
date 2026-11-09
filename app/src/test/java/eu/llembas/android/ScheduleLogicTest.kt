package eu.llembas.android

import eu.llembas.android.api.LembasJson
import eu.llembas.android.api.ScheduleList
import eu.llembas.android.ui.scheduled.ScheduleLogic
import eu.llembas.android.ui.scheduled.Tone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

class ScheduleLogicTest {
    private val zone = ZoneId.of("Europe/Bratislava")

    @Test
    fun statusesHaveTones() {
        assertEquals(Tone.Good, ScheduleLogic.tone("ok"))
        assertEquals(Tone.Quiet, ScheduleLogic.tone("silent"))
        assertEquals(Tone.Bad, ScheduleLogic.tone("failed"))
        assertEquals(Tone.Bad, ScheduleLogic.tone("missed"))
        assertEquals(Tone.Waiting, ScheduleLogic.tone("waiting"))
        assertEquals(Tone.Neutral, ScheduleLogic.tone("something new"))
    }

    @Test
    fun aMomentIsShownWhereThePhoneIs() {
        assertEquals("Tue 10 Mar, 08:00", ScheduleLogic.moment("2026-03-10T07:00:00+00:00", zone, Locale.ENGLISH))
        assertNull(ScheduleLogic.moment("tomorrow", zone, Locale.ENGLISH))
        assertNull(ScheduleLogic.moment(null, zone, Locale.ENGLISH))
    }

    @Test
    fun upcomingKeepsFiveReadableRuns() {
        val runs = (1..7).map { "2026-03-1${it}T07:00:00+00:00" } + "nonsense"
        assertEquals(5, ScheduleLogic.upcoming(runs, zone = zone, locale = Locale.ENGLISH).size)
    }

    @Test
    fun aScheduleListThatSaysLittleStillParses() {
        val list = LembasJson.decodeFromString(ScheduleList.serializer(), """{"schedules":[{"id":"s1","enabled":false,"model":null,"later":1}]}""")
        val one = list.schedules.single()
        assertEquals("s1", one.id)
        assertTrue(one.paused)
        assertNull(one.nextRun)
        assertNull(one.model)
    }
}
