package dev.hermeskotlin.ui.settings

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Settings › Support Kyllene against `.github/FUNDING.yml` (GitHub's Sponsor button) and the README, so a changed
 * account in one place can't leave the app sending people to the old one.
 */
class SupportLinksTest {

    private val funding = File("../../.github/FUNDING.yml").readLines()
        .mapNotNull { line -> line.split(':', limit = 2).takeIf { it.size == 2 }?.let { (k, v) -> k.trim() to v.trim() } }
        .toMap()

    @Test
    fun theLinksAreTheFundingAccounts() {
        assertEquals("https://ko-fi.com/${funding.getValue("ko_fi")}", KOFI_URL)
        assertEquals("https://github.com/sponsors/${funding.getValue("github")}", SPONSORS_URL)
    }

    @Test
    fun theReadmeLinksTheSameAccounts() {
        val readme = File("../../README.md").readText()
        listOf(REPO_URL, KOFI_URL, SPONSORS_URL).forEach { assertTrue(it in readme, "README.md doesn't link $it") }
    }

    @Test
    fun theReadmeNamesEveryoneTheAppDoes() {
        val readme = File("../../README.md").readText()
        val missing = (Credits.maintainers + Credits.contributors + Credits.supporters).filter { "(${Credits.profile(it)})" !in readme }
        assertTrue(missing.isEmpty(), "README.md doesn't name $missing")
    }

    @Test
    fun everyoneIsNamedOnce() {
        val everyone = Credits.maintainers + Credits.contributors + Credits.supporters
        // A supporter can also have code in; nobody is listed twice as the same kind.
        listOf(Credits.maintainers, Credits.contributors, Credits.supporters).forEach { group ->
            assertEquals(group.distinctBy { it.lowercase() }, group)
        }
        assertTrue(Credits.contributors.none { it in Credits.maintainers }, "a maintainer is also under Contributors")
        assertTrue(everyone.all { Regex("[A-Za-z0-9-]+").matches(it) }, "not a GitHub login: ${everyone.filterNot { Regex("[A-Za-z0-9-]+").matches(it) }}")
    }
}
