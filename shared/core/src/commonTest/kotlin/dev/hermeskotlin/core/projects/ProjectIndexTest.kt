package dev.hermeskotlin.core.projects

import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.sessions.SessionSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProjectIndexTest {

    private val herald = Project("p1", "herald", "/srv/herald", sessionCount = 2, sessionIds = setOf("a", "old"))
    private val site = Project("/srv/site", "site", "/srv/site", sessionCount = 1, isAuto = true, sessionIds = setOf("b"))
    private val home = Project(ProjectsApi.NO_PROJECT_ID, "Home", null, sessionCount = 1, isNoProject = true, sessionIds = setOf("h"))
    private val index = ProjectIndex(listOf(herald, site, home))

    @Test
    fun aChatIsFoundInTheProjectThatHoldsIt() {
        assertEquals("herald", index.labelFor(SessionSummary("a")))
        assertEquals("site", index.labelFor(SessionSummary("b")))
        assertNull(index.labelFor(SessionSummary("z")))
    }

    @Test
    fun aCompressedChatIsFoundByAnEarlierIdOfItsLineage() {
        val tip = SessionSummary("new-tip", lineageIds = listOf("old", "new-tip"))
        assertEquals(herald, index.projectOf(tip))
    }

    @Test
    fun homeIsNeverNamed() {
        assertNull(index.projectOf(SessionSummary("h")))
    }

    @Test
    fun noLabelWhileTheListShowsOnlyThatProject() {
        assertNull(index.labelFor(SessionSummary("a"), shown = herald))
        assertEquals("herald", index.labelFor(SessionSummary("a"), shown = site))
    }

    @Test
    fun theTreesSessionIdsAreKept() {
        val tree = HermesJson.parseToJsonElement(
            """[{"id":"/srv/real","label":"real","path":"/srv/real","isAuto":true,"sessionCount":40,"sessionIds":["x","y",3,null]}]""",
        )
        val project = ProjectsApi.parseProjects(tree).single()
        assertEquals(setOf("x", "y"), project.sessionIds)
        // The chip counts the chats it can open, not the entries that name none.
        assertEquals(2, project.sessionCount)
    }

    @Test
    fun aTreeOfOnlyBadIdsHasNoChats() {
        val tree = HermesJson.parseToJsonElement(
            """[{"id":"/srv/junk","label":"junk","path":"/srv/junk","isAuto":true,"sessionIds":[3,null,""]}]""",
        )
        assertEquals(emptyList(), ProjectsApi.parseProjects(tree))
    }

    @Test
    fun aRowsLineageIdsAreRead() {
        val row = HermesJson.decodeFromString(SessionSummary.serializer(), """{"id":"c","_lineage_ids":["a","b","c"]}""")
        assertEquals(listOf("a", "b", "c"), row.lineageIds)
    }
}
