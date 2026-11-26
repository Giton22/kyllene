package dev.hermeskotlin.core.voice

import dev.onvoid.webrtc.media.audio.AudioLayer
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopAudioBackendTest {
    @Test fun eachOperatingSystemGetsItsOwnDeviceModuleBackend() {
        assertEquals(AudioLayer.kWindowsCoreAudio, desktopAudioLayer("Windows 10"))
        assertEquals(AudioLayer.kWindowsCoreAudio, desktopAudioLayer("Windows 11"))
        assertEquals(AudioLayer.kLinuxPulseAudio, desktopAudioLayer("Linux"))
        assertEquals(AudioLayer.kPlatformDefaultAudio, desktopAudioLayer("Mac OS X"))
    }

    @Test fun ffmpegIsSearchedOnThePathBeforeTheLinuxRuntimesFixedPath() {
        val dir = createTempDirectory("ffmpeg").toFile()
        val fake = File(dir, "ffmpeg").apply {
            writeText("#!/bin/sh\n")
            setExecutable(true)
        }
        val fakeExe = File(dir, "ffmpeg.exe").apply {
            writeText("MZ\n")
            setExecutable(true)
        }
        assertEquals(fake.absolutePath, desktopFfmpeg("Linux", dir.absolutePath)?.absolutePath)
        assertEquals(fakeExe.absolutePath, desktopFfmpeg("Windows 10", dir.absolutePath)?.absolutePath)
        // Nothing on the search path falls back to the Linux runtime's fixed path, which a host may not have.
        assertEquals(File("/usr/bin/ffmpeg").canExecute(), desktopFfmpeg("Linux", "") != null)
    }
}
