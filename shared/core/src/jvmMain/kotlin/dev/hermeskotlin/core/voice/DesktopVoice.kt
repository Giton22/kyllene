package dev.hermeskotlin.core.voice

import dev.onvoid.webrtc.media.audio.AudioLayer
import java.io.File

private val NATIVE_AUDIO_ARCHES = setOf("amd64", "x86_64", "aarch64")

/**
 * Linux and Windows on these CPUs have the native WebRTC audio backend: recording, playback and live calls.
 * macOS has no native audio in this build yet.
 */
fun nativeDesktopAudio(
    osName: String = System.getProperty("os.name").orEmpty(),
    osArch: String = System.getProperty("os.arch").orEmpty(),
): Boolean =
    (osName == "Linux" || osName.startsWith("Windows")) && osArch in NATIVE_AUDIO_ARCHES

/** The device module's backend per OS; WebRTC's own default where this build knows no better. */
internal fun desktopAudioLayer(
    osName: String = System.getProperty("os.name").orEmpty(),
): AudioLayer = when {
    osName.startsWith("Windows") -> AudioLayer.kWindowsCoreAudio
    osName == "Linux" -> AudioLayer.kLinuxPulseAudio
    else -> AudioLayer.kPlatformDefaultAudio
}

/**
 * ffmpeg for spoken replies: on Linux the runtime's fixed path comes first, because the Flatpak runtime ships it and a
 * host install may not; anything else goes to the first absolute entry on the PATH that holds one. Windows has no
 * fixed path, so there `ffmpeg.exe` has to be on the PATH.
 *
 * Relative and quoted entries are skipped or unquoted first: a `ffmpeg` in the working directory, or a folder that
 * happens to be called `ffmpeg`, is not something to run.
 */
fun desktopFfmpeg(
    osName: String = System.getProperty("os.name").orEmpty(),
    searchPath: String? = System.getenv("PATH"),
    fixedPath: File = File("/usr/bin/ffmpeg"),
): File? {
    val name = if (osName.startsWith("Windows")) "ffmpeg.exe" else "ffmpeg"
    val onPath = searchPath.orEmpty()
        .split(File.pathSeparatorChar)
        .asSequence()
        .map { it.trim().removeSurrounding("\"") }
        .filter { it.isNotEmpty() && File(it).isAbsolute }
        .map { File(it, name) }
        .firstOrNull { it.isFile && it.canExecute() }
    val fixed = fixedPath.takeIf { it.isFile && it.canExecute() }
    return if (osName.startsWith("Windows")) onPath else fixed ?: onPath
}

/** Spoken replies decode with ffmpeg; without one a voice chat can't answer aloud and Read aloud stays silent. */
fun desktopSpeechDecoder(ffmpeg: File? = desktopFfmpeg()): Boolean =
    ffmpeg?.let { it.isFile && it.canExecute() } == true

/** The command [FfmpegSpeechDecoder] runs: the resolved ffmpeg, else the bare name for the OS. */
internal fun desktopFfmpegCommand(
    osName: String = System.getProperty("os.name").orEmpty(),
    ffmpeg: File? = desktopFfmpeg(osName),
): String = ffmpeg?.absolutePath ?: if (osName.startsWith("Windows")) "ffmpeg.exe" else "ffmpeg"

/** No audio backend for this host: voice input says so instead of recording. */
object NoVoiceRecorder : VoiceRecorder {
    override suspend fun record(activity: VoiceActivity, onLevel: (Float) -> Unit, onSpeech: () -> Unit): Recording =
        throw UnsupportedOperationException("Voice input isn't available on this desktop.")

    override fun finish() = Unit
}

/** No audio backend for this host: replies aren't read aloud. */
object NoSpeechPlayer : SpeechPlayer {
    override suspend fun play(audio: SpokenAudio) = Unit
}
