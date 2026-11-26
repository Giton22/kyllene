package dev.hermeskotlin.core.voice

import dev.onvoid.webrtc.media.audio.AudioLayer
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopAudioBackendTest {
    @Test fun eachOperatingSystemGetsItsOwnDeviceModuleBackend() {
        assertEquals(AudioLayer.kWindowsCoreAudio, desktopAudioLayer("Windows 10"))
        assertEquals(AudioLayer.kWindowsCoreAudio, desktopAudioLayer("Windows 11"))
        assertEquals(AudioLayer.kLinuxPulseAudio, desktopAudioLayer("Linux"))
        assertEquals(AudioLayer.kPlatformDefaultAudio, desktopAudioLayer("Mac OS X"))
    }

    @Test fun linuxTakesTheRuntimesFixedPathFirstAndWindowsTakesThePath() = inTemporaryDirectory { dir ->
        val onPath = File(dir, "bin").apply { mkdirs() }
        val pathFfmpeg = executable(File(onPath, "ffmpeg"))
        val pathExe = executable(File(onPath, "ffmpeg.exe"))
        val fixedFfmpeg = executable(File(dir, "usr/bin/ffmpeg"))
        val absent = File(dir, "absent/ffmpeg")
        // Windows allows an entry to be quoted, and the quotes are not part of the folder's name.
        val quoted = "\"${onPath.absolutePath}\""

        assertEquals(fixedFfmpeg.absolutePath, desktopFfmpeg("Linux", quoted, fixedFfmpeg)?.absolutePath)
        assertEquals(pathFfmpeg.absolutePath, desktopFfmpeg("Linux", quoted, absent)?.absolutePath)
        assertEquals(pathExe.absolutePath, desktopFfmpeg("Windows 10", quoted, fixedFfmpeg)?.absolutePath)
        // A Windows install has no fixed path to fall back on.
        assertNull(desktopFfmpeg("Windows 10", "", fixedFfmpeg))
    }

    @Test fun relativeAndNonExecutableEntriesOnThePathAreSkipped() = inTemporaryDirectory { dir ->
        val bin = File(dir, "bin").apply { mkdirs() }
        executable(File(dir, "ffmpeg")) // Would be run if "." were followed.
        File(bin, "ffmpeg").mkdirs() // A folder that is named like the binary.
        val absent = File(dir, "absent/ffmpeg")

        assertNull(desktopFfmpeg("Linux", ".", absent))
        assertNull(desktopFfmpeg("Linux", bin.absolutePath, absent))
    }
}

/** Writes a file that the platform will agree is executable, creating its folder. */
private fun executable(file: File): File = file.apply {
    parentFile?.mkdirs()
    writeText("#!/bin/sh\n")
    setExecutable(true)
}

private fun inTemporaryDirectory(test: (File) -> Unit) {
    val dir = createTempDirectory("ffmpeg").toFile()
    try {
        test(dir)
    } finally {
        dir.deleteRecursively()
    }
}
