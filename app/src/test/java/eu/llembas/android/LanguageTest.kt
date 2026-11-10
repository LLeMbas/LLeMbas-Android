package eu.llembas.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class LanguageTest {
    @Test
    fun theAccountsLanguageIsTheDefault() {
        assertEquals("en", AppLanguage.effective(AppLanguage.ACCOUNT, "en"))
        assertEquals("sk", AppLanguage.effective(AppLanguage.ACCOUNT, "sk"))
        // A server language written with a region still names one the app has.
        assertEquals("cs", AppLanguage.effective(AppLanguage.ACCOUNT, "cs_CZ"))
    }

    @Test
    fun anUnknownOrUnreadAccountLanguageFollowsThePhone() {
        assertNull(AppLanguage.effective(AppLanguage.ACCOUNT, ""))
        assertNull(AppLanguage.effective(AppLanguage.ACCOUNT, "ja"))
    }

    @Test
    fun aChoiceBeatsTheAccount() {
        assertNull(AppLanguage.effective(AppLanguage.PHONE, "en"))
        assertEquals("de", AppLanguage.effective("de", "en"))
        // A code the app no longer has (a newer build's choice, say) follows the phone.
        assertNull(AppLanguage.effective("ja", "en"))
    }

    @Test
    fun everyLanguageIsNamedInItself() {
        assertEquals("Slovenčina", AppLanguage.name("sk"))
        assertEquals("English", AppLanguage.name("en-GB"))
        assertNull(AppLanguage.name("ja"))
    }

    /** The list Settings offers, the strings gen-strings.py writes, and the locale config agree. */
    @Test
    fun theLanguagesAreTheOnesTheAppHasStringsFor() {
        val res = File("src/main/res")
        val folders = res.listFiles().orEmpty()
            .map { it.name }
            .filter { it.startsWith("values-") && File(res, "$it/strings.xml").exists() }
            .map { it.removePrefix("values-") }
            .toSet()
        val offered = AppLanguage.LANGUAGES.map { it.first }.toSet()
        assertEquals(offered - "en", folders)
        val config = File(res, "xml/locales_config.xml").readText()
        val listed = Regex("""android:name="([a-z]+)"""").findAll(config).map { it.groupValues[1] }.toSet()
        assertEquals(offered, listed)
    }
}
