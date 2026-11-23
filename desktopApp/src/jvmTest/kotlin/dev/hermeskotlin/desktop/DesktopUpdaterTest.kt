package dev.hermeskotlin.desktop

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DesktopUpdaterTest {

    @Test
    fun sanitizeUpdateFileNameStripsQueryFragmentSeparatorsAndTraversal() {
        assertEquals("Kyllene-1.2.3.msi", sanitizeUpdateFileName("https://example.com/releases/Kyllene-1.2.3.msi?sig=abc#top"))
        assertEquals("Kyllene-1.2.3.dmg", sanitizeUpdateFileName("https://example.com/Kyllene-1.2.3.dmg"))
        assertEquals("herald-update", sanitizeUpdateFileName("https://example.com/releases/.."))
        assertEquals("herald-update", sanitizeUpdateFileName("https://example.com/releases/..."))
        assertEquals("herald-update", sanitizeUpdateFileName("https://example.com/releases/"))
        assertEquals("evil.msi", sanitizeUpdateFileName("https://example.com/releases/..\\..\\evil.msi"))
        assertEquals("ab.msi", sanitizeUpdateFileName("https://example.com/a:b*?.msi".replace("?", "")))
        assertEquals("installer.msi", sanitizeUpdateFileName("https://example.com/in\u0000sta:ll<er>.msi"))
    }

    @Test
    fun createUpdateDirMakesUniqueDirectoryWithOwnerOnlyPermissionsOnPosix() {
        val first = createUpdateDir()
        val second = createUpdateDir()
        try {
            assertTrue(first.isDirectory)
            assertTrue(second.isDirectory)
            assertTrue(first.name.startsWith("herald-update-"))
            assertNotEquals(first.toPath().normalize(), second.toPath().normalize())
            assertNotEquals("herald-update", first.name)
            if ("posix" in FileSystems.getDefault().supportedFileAttributeViews()) {
                val perms = Files.getPosixFilePermissions(first.toPath())
                assertEquals("rwx------", PosixFilePermissions.toString(perms))
            }
        } finally {
            first.deleteRecursively()
            second.deleteRecursively()
        }
    }
}
