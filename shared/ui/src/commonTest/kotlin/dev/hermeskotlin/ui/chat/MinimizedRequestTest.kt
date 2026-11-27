package dev.hermeskotlin.ui.chat

import dev.hermeskotlin.core.chat.ApprovalChoice
import dev.hermeskotlin.core.chat.ClarifyQuestion
import dev.hermeskotlin.core.chat.InputRequest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** When the approval or question card shows as a pill (#559). */
class MinimizedRequestTest {

    private val approval = InputRequest.Approval("r1", "make clean", "Clear the build", "terminal", listOf(ApprovalChoice.Once, ApprovalChoice.Deny))
    private val question = InputRequest.Clarify("r2", listOf(ClarifyQuestion(null, "Which branch?")))
    private val sudo = InputRequest.Secret("r3", InputRequest.Secret.Kind.Sudo, "Password", envVar = null, command = "apt update")

    @Test
    fun theCardShowsUntilItIsMinimized() {
        assertFalse(isMinimized(null, listOf(approval)))
        assertTrue(isMinimized(setOf("r1"), listOf(approval)))
    }

    @Test
    fun aRequestThatCameAfterwardsOpensTheCardAgain() {
        assertFalse(isMinimized(setOf("r1"), listOf(approval, question)))
    }

    @Test
    fun oneAnsweredElsewhereLeavesItAPill() {
        // The approval answered on another client; the question queued behind it was already open.
        assertTrue(isMinimized(setOf("r1", "r2"), listOf(question)))
    }

    @Test
    fun aPasswordInFrontIsNeverAPill() {
        assertFalse(isMinimized(setOf("r1", "r3"), listOf(sudo)))
        assertFalse(sudo.minimizable)
        assertTrue(approval.minimizable)
        assertTrue(question.minimizable)
    }

    @Test
    fun noRequestNoPill() {
        assertFalse(isMinimized(setOf("r1"), emptyList()))
    }
}
