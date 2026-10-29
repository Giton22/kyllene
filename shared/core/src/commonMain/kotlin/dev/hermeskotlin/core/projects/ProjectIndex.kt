package dev.hermeskotlin.core.projects

import dev.hermeskotlin.core.sessions.SessionSummary

/**
 * Which project each chat belongs to, from the projects' [Project.sessionIds]. Home is left out: a chat there
 * has no project worth naming. A chat compressed since the tree was read is found by an earlier id of its lineage.
 */
class ProjectIndex(projects: List<Project>) {

    private val byChat: Map<String, Project> = buildMap {
        projects.filterNot { it.isNoProject }.forEach { project ->
            project.sessionIds.forEach { id -> if (id !in this) put(id, project) }
        }
    }

    /** The project [session] is in, or null when it is in none (or only in Home). */
    fun projectOf(session: SessionSummary): Project? =
        byChat[session.id] ?: session.lineageIds?.firstNotNullOfOrNull { byChat[it] }

    /** [session]'s project name for a row, unless the list already shows only that project ([shown]). */
    fun labelFor(session: SessionSummary, shown: Project? = null): String? =
        projectOf(session)?.takeIf { it.id != shown?.id }?.label

    companion object {
        val Empty = ProjectIndex(emptyList())
    }
}
