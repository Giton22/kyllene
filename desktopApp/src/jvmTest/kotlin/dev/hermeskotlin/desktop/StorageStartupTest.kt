package dev.hermeskotlin.desktop

import dev.hermeskotlin.core.storage.SecureStorageException
import javax.swing.SwingUtilities
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StorageStartupTest {
    @Test fun everyFailurePromptFitsA360PixelDisplay() {
        SwingUtilities.invokeAndWait {
            for (reason in SecureStorageException.Reason.entries) {
                val pane = storageUnlockPane(reason.guidance)
                assertTrue(pane.preferredSize.width <= 360, "${reason.name}: ${pane.preferredSize.width}px")
            }
        }
    }

    @Test fun successfulUnlockNeedsNoPrompt() {
        var calls = 0
        assertTrue(unlockDesktopStorage(prepare = { calls++ }, retry = { error("No prompt is needed") }))
        assertEquals(1, calls)
    }

    @Test fun retryStartsOnlyAfterTheFailedRequestHasClosed() {
        var calls = 0
        var active = false
        var prompts = 0
        assertTrue(unlockDesktopStorage(prepare = {
            active = true
            try {
                if (calls++ == 0) throw SecureStorageException(SecureStorageException.Reason.Unavailable)
            } finally { active = false }
        }, retry = { guidance ->
            assertFalse(active)
            assertContains(guidance, "keyring")
            prompts++
            true
        }))
        assertEquals(2, calls)
        assertEquals(1, prompts)
    }

    @Test fun quitDoesNotStartAnotherUnlockAttempt() {
        var calls = 0
        assertFalse(unlockDesktopStorage(prepare = {
            calls++
            throw SecureStorageException(SecureStorageException.Reason.Cancelled)
        }, retry = { false }))
        assertEquals(1, calls)
    }

    @Test fun unlockNeverRunsOnTheAwtEventThread() {
        var calls = 0
        SwingUtilities.invokeAndWait {
            val failure = assertFailsWith<IllegalStateException> {
                unlockDesktopStorage(prepare = { calls++ }, retry = { error("No prompt") })
            }
            assertContains(failure.message.orEmpty(), "event thread")
        }
        assertEquals(0, calls)
    }

    @Test fun cancellationDoesNotBecomeARetryPrompt() {
        assertFailsWith<CancellationException> {
            unlockDesktopStorage(prepare = { throw CancellationException("synthetic cancellation") },
                retry = { error("Cancellation must escape") })
        }
    }
}
