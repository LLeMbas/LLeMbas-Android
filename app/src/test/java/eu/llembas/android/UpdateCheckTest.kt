package eu.llembas.android

import eu.llembas.android.api.AppsInfo
import eu.llembas.android.api.LembasJson
import eu.llembas.android.auth.Prefs
import eu.llembas.android.update.AppVersion
import eu.llembas.android.update.PublicRelease
import eu.llembas.android.update.ReleaseCheck
import eu.llembas.android.update.checksUpdates
import eu.llembas.android.update.offersBetas
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckTest {
    private fun v(text: String) = AppVersion.parse(text)!!

    @Test
    fun versionsSortAsTheirCodesDo() {
        assertTrue(v("1.0.0-beta.2") > v("1.0.0-beta.1"))
        assertTrue(v("1.0.0-beta.10") > v("1.0.0-beta.9"))
        assertTrue(v("1.0.0") > v("1.0.0-beta.99"))
        assertTrue(v("1.0.1-beta.1") > v("1.0.0"))
        assertEquals(v("v1.2.3"), v("1.2.3"))
        // A test build's suffix is not part of the version.
        assertEquals(v("1.0.0-beta.5"), v("1.0.0-beta.5-debug.44"))
        assertNull(AppVersion.parse("1.0.0-rc.1"))
        assertNull(AppVersion.parse("latest"))
    }

    // GitHub's answer, cut to what the check reads plus a field it does not.
    private val releases = LembasJson.decodeFromString(
        ListSerializer(PublicRelease.serializer()),
        """
        [
          {"tag_name": "v1.1.0-beta.1", "prerelease": true, "draft": false, "html_url": "https://example.org/b1", "assets": []},
          {"tag_name": "v1.0.1", "prerelease": false, "draft": false, "html_url": "https://example.org/101"},
          {"tag_name": "v1.2.0", "prerelease": false, "draft": true, "html_url": "https://example.org/draft"},
          {"tag_name": "v1.0.0", "prerelease": false, "html_url": "https://example.org/100"},
          {"tag_name": "nightly", "prerelease": true}
        ]
        """,
    )

    @Test
    fun aReleaseIsOfferedTheNewestItMayHave() {
        // A release without betas: the newest release, never the draft.
        val stable = ReleaseCheck.newest(releases, v("1.0.0"), betas = false)!!
        assertEquals(v("1.0.1"), stable.version)
        assertEquals("https://example.org/101", stable.page)
        // With betas: the beta above it.
        assertEquals(v("1.1.0-beta.1"), ReleaseCheck.newest(releases, v("1.0.0"), betas = true)!!.version)
        // Nothing newer: nothing offered.
        assertNull(ReleaseCheck.newest(releases, v("1.1.0-beta.1"), betas = true))
        assertNull(ReleaseCheck.newest(releases, v("1.0.1"), betas = false))
    }

    @Test
    fun theSettingsFollowTheInstalledChannelUntilChosen() {
        val unset = Prefs()
        assertTrue(unset.checksUpdates(installedIsBeta = true))
        assertFalse(unset.checksUpdates(installedIsBeta = false))
        assertTrue(unset.offersBetas(installedIsBeta = true))
        assertFalse(unset.offersBetas(installedIsBeta = false))
        assertFalse(Prefs(updateCheck = false).checksUpdates(installedIsBeta = true))
        assertTrue(Prefs(updateBetas = true).offersBetas(installedIsBeta = false))
    }

    @Test
    fun onceADayAtMost() {
        val day = 24L * 60 * 60 * 1000
        assertTrue(ReleaseCheck.due(0, 5))
        assertFalse(ReleaseCheck.due(1_000, 1_000 + day - 1))
        assertTrue(ReleaseCheck.due(1_000, 1_000 + day))
        // A clock set back is not a reason to wait a year.
        assertTrue(ReleaseCheck.due(10 * day, day))
    }

    @Test
    fun theServersRecommendationComesFirst() {
        fun read(name: String) = LembasJson.decodeFromString(
            AppsInfo.serializer(),
            requireNotNull(javaClass.classLoader!!.getResource("$name.json")).readText(),
        )
        val beta = read("apps")
        assertEquals("1.1.0-beta.2", beta.android!!.version)
        val offer = ReleaseCheck.recommended(beta, v("1.0.0-beta.5"), betas = true)!!
        assertEquals("1.1.0-beta.2", offer.version.toString())
        assertEquals("https://github.com/LLeMbas/LLeMbas-Android/releases/tag/v1.1.0-beta.2", offer.page)
        // A beta is not offered to someone who did not want betas, nor anything not newer.
        assertNull(ReleaseCheck.recommended(beta, v("1.0.0-beta.5"), betas = false))
        assertNull(ReleaseCheck.recommended(beta, v("1.1.0-beta.2"), betas = true))
        // A stable server with no Android pin recommends nothing, so nothing is offered.
        val stable = read("apps_stable")
        assertNull(stable.android)
        assertNull(ReleaseCheck.recommended(stable, v("1.0.0-beta.5"), betas = true))
    }
}
