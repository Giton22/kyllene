package dev.hermeskotlin.ui.chat

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MediaActionsSanitizationTest {

    @Test
    fun sanitizeShareFileNameStripsSeparatorsTraversalControlsAndLeadingDots() {
        assertEquals("report.pdf", sanitizeShareFileName("report.pdf"))
        assertEquals("my photo (1).png", sanitizeShareFileName("my photo (1).png"))
        assertEquals("_init_.py", sanitizeShareFileName("_init_.py"))

        assertEquals("passwd", sanitizeShareFileName("../../etc/passwd"))
        assertEquals("secret.txt", sanitizeShareFileName("..\\..\\secret.txt"))
        assertEquals("baz.png", sanitizeShareFileName("foo/bar/../baz.png"))
        assertEquals("abc.txt", sanitizeShareFileName("a\u0000b\r\nc.txt"))
        assertEquals("hidden", sanitizeShareFileName(".hidden"))
        assertEquals("file", sanitizeShareFileName(".."))
        assertEquals("file", sanitizeShareFileName("..."))
        assertEquals("file", sanitizeShareFileName("   "))
        assertEquals("file", sanitizeShareFileName(""))
        assertEquals("file", sanitizeShareFileName("///\\"))

        val ads = sanitizeShareFileName("notes.txt:evil.exe")
        assertFalse(':' in ads)
        assertFalse('/' in ads)
        assertFalse('\\' in ads)
        assertFalse(".." in ads)
    }

    @Test
    fun safeDesktopShareFileStaysWithinTargetDirAndDefangsExecutables() {
        val dir = Files.createTempDirectory("herald-share-test").toFile()
        try {
            val base = dir.toPath().toAbsolutePath().normalize()

            for (attempt in listOf("../../outside.txt", "..\\..\\outside.txt", "/etc/passwd", "C:\\Windows\\win.ini", "..", ".hidden.png")) {
                val resolved = assertNotNull(safeDesktopShareFile(dir, attempt), "expected safe file for $attempt")
                val path = resolved.toPath().toAbsolutePath().normalize()
                assertTrue(path.startsWith(base), "$path escaped $base")
                assertEquals(base, path.parent, "$path was not directly inside $base")
            }

            val dangerous = listOf(
                "run.exe", "RUN.EXE", "setup.bat", "script.cmd", "legacy.com", "install.msi",
                "macro.vbs", "macro.vbe", "payload.js", "payload.jse", "host.wsf", "host.wsh",
                "deploy.ps1", "saver.scr", "shortcut.lnk", "tool.jar", "start.sh", "bundle.app",
                "launch.command", "trailing.bat.",
            )
            for (name in dangerous) {
                val resolved = assertNotNull(safeDesktopShareFile(dir, name), "expected defanged file for $name")
                assertTrue(resolved.name.endsWith(".txt"), "$name was not defanged: ${resolved.name}")
                assertEquals(base, resolved.toPath().toAbsolutePath().normalize().parent)
            }

            for (safe in listOf("photo.png", "report.pdf", "notes.md", "data.csv", "config.json")) {
                val resolved = assertNotNull(safeDesktopShareFile(dir, safe))
                assertEquals(safe, resolved.name)
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
