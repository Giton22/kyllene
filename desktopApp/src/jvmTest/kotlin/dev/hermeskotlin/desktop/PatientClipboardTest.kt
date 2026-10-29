package dev.hermeskotlin.desktop

import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import dev.hermeskotlin.designsystem.components.plainTextClipEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class PatientClipboardTest {

    /** A clipboard another app holds open for its first [busy] tries, as Windows' throws then. */
    private class HeldClipboard(var busy: Int) : Clipboard {
        var tries = 0
        var entry: ClipEntry? = null

        private fun open() {
            tries++
            if (busy-- > 0) throw IllegalStateException("cannot open system clipboard")
        }

        override suspend fun getClipEntry(): ClipEntry? = entry.also { open() }

        override suspend fun setClipEntry(clipEntry: ClipEntry?) {
            open()
            entry = clipEntry
        }
    }

    @Test
    fun aCopyWaitsForTheClipboardToBeLetGo() = runBlocking {
        val system = HeldClipboard(busy = 2)
        val copied = plainTextClipEntry("text")
        PatientClipboard(system).setClipEntry(copied)
        assertSame(copied, system.entry)
        assertEquals(3, system.tries)
    }

    @Test
    fun aClipboardHeldTooLongDropsTheCopyWithoutAnError() = runBlocking {
        val system = HeldClipboard(busy = Int.MAX_VALUE)
        PatientClipboard(system).setClipEntry(plainTextClipEntry("text"))
        assertNull(system.entry)
        assertEquals(5, system.tries)
    }

    @Test
    fun aCancelledCopyIsNotTriedAgain() = runBlocking {
        var tries = 0
        val cancelled = object : Clipboard {
            override suspend fun getClipEntry(): ClipEntry? = null

            override suspend fun setClipEntry(clipEntry: ClipEntry?) {
                tries++
                throw CancellationException("left")
            }
        }
        assertFailsWith<CancellationException> { PatientClipboard(cancelled).setClipEntry(plainTextClipEntry("text")) }
        assertEquals(1, tries)
    }

    @Test
    fun aPasteFromAHeldClipboardFindsNothing() = runBlocking {
        val system = HeldClipboard(busy = Int.MAX_VALUE).apply { entry = plainTextClipEntry("text") }
        assertNull(PatientClipboard(system).getClipEntry())
    }
}
