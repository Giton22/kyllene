package dev.hermeskotlin.desktop.web

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AgentCardDraftsTest {
    @Test
    fun oversizedDraftReplacesTheEarlierSnapshotUntilAnotherEditFits() {
        val drafts = AgentCardDrafts()
        val key = "chat/owner"
        val messages = listOf("""{"createSurface":{"surfaceId":"pick"}}""")
        val newerMessages = messages + """{"updateDataModel":{"surfaceId":"pick","path":"/status","value":"new"}}"""
        val fingerprint = messageFingerprint(messages)
        val original = buildJsonObject { put("name", "earlier edit") }
        val shorter = buildJsonObject { put("name", "shorter") }
        drafts.save(key, messages.size, fingerprint, original)
        assertEquals(original, drafts.restore(key, newerMessages)?.models)

        drafts.save(key, messages.size, fingerprint, null)
        assertNull(assertNotNull(drafts.restore(key, newerMessages)).models)
        assertNull(drafts.restore(key, listOf("""{"createSurface":{"surfaceId":"other"}}""")))
        assertNull(drafts.restore("other/owner", newerMessages))

        drafts.save(key, messages.size, fingerprint, shorter)
        assertEquals(shorter, drafts.restore(key, newerMessages)?.models)
    }
}
