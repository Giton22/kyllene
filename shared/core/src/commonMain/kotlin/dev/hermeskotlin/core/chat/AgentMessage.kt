package dev.hermeskotlin.core.chat

/**
 * A message one bot sent another. Bot Mode delivers it as a user turn of the recipient's chat (the turn
 * runs on it, so no other role fits), prefixed with who sent it: `Message from 🤖 Scribe (@scribe): …`,
 * or the legacy `[Message from agent 'scribe'] …`. Shown as the sender's note, not as the user's prompt.
 */
data class AgentMessage(val sender: String, val handle: String?, val body: String) {
    companion object {
        // Desktop's AGENT_MESSAGE_RE (apps/desktop/src/components/assistant-ui/thread/user-message.tsx).
        private val PATTERN = Regex(
            """^(?:Message from (?:(🤖)\s*)?([^:\n(]{1,64}?)(?:\s*\(@([a-z0-9][a-z0-9_-]{0,63})(?:@[a-zA-Z0-9][a-zA-Z0-9_-]{0,63})?\))?:\s*|\[Message from agent '([^']{1,64})']\s*)([\s\S]*)$""",
        )

        /** The sender and text of an agent-to-agent delivery. Internal rows require the full modern or legacy envelope. */
        fun parse(text: String, requireEnvelope: Boolean = false): AgentMessage? {
            val match = PATTERN.find(text.trim()) ?: return null
            val (marker, name, handle, legacyName, body) = match.destructured
            if (requireEnvelope && legacyName.isEmpty() && (marker.isEmpty() || handle.isEmpty())) return null
            val sender = name.ifEmpty { legacyName }.trim().ifEmpty { return null }
            return AgentMessage(sender = sender, handle = handle.ifEmpty { null }, body = body.trim())
        }
    }
}
