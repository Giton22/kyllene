package dev.hermeskotlin.core.voice

import dev.hermeskotlin.core.chat.extractReplyMedia
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.io.writeIntLe
import kotlinx.io.writeShortLe

/**
 * How a recording decides you're done talking. The timings are Desktop's (tools.voice_mode); the
 * level is lower, since a phone's speech-recognition mic is unboosted where the browser's isn't.
 */
data class VoiceActivity(
    /** The least normalized level (0..1) that counts as speech; noise around it raises the bar. */
    val speechLevel: Float = 0.025f,
    /** Quiet this long after speech ends the recording. */
    val silenceMs: Long = 1_250,
    /** With no speech at all, give up after this long; 0 waits for [VoiceRecorder.finish]. */
    val idleMs: Long = 12_000,
    /** Hard cap on one recording. */
    val maxMs: Long = 60_000,
)

/**
 * Decides, frame by frame, when someone has finished talking. Desktop's fixed level assumes a quiet
 * room; a phone is often somewhere noisier, so the bar also rises with the measured background
 * noise: speech must stand clearly above it, and quiet is anything back near it.
 */
class EndOfSpeech(private val activity: VoiceActivity) {
    var heardSpeech = false
        private set

    /** The measured background level, for diagnostics; negative until the first frame. */
    var noise = -1f
        private set
    private var silenceSince = -1L
    /** Since when the level has stood above quiet, in a pause after speech; -1 when it hasn't. */
    private var loudSince = -1L
    /** Where the last frame ended, which is where this one starts. */
    private var lastFrameEnd = 0L

    /** Feeds one frame's [level] at [elapsedMs] into the recording; true once the recording should end. */
    fun onFrame(level: Float, elapsedMs: Long): Boolean {
        val frameStart = lastFrameEnd
        lastFrameEnd = elapsedMs
        // The opening moment only measures the room; nobody starts talking that fast.
        if (elapsedMs <= CALIBRATION_MS) {
            noise = if (noise < 0) level else noise + (level - noise) * 0.2f
            return false
        }
        val speechBar = maxOf(activity.speechLevel, noise * SPEECH_OVER_NOISE)
        val quietBar = maxOf(activity.speechLevel * QUIET_FRACTION, noise * QUIET_OVER_NOISE)
        val speech = level >= speechBar
        if (speech) {
            heardSpeech = true
            // A steady loud room lifts the bar too, slowly enough that a long sentence doesn't.
            noise += (level - noise) * 0.002f
        } else {
            // The background noise follows the quiet frames: down quickly, up slowly.
            noise = when {
                noise < 0 -> level
                level < noise -> noise + (level - noise) * 0.3f
                else -> noise + (level - noise) * 0.02f
            }
        }
        if (heardSpeech && level < quietBar) {
            loudSince = -1
            if (silenceSince < 0) silenceSince = elapsedMs
            if (elapsedMs - silenceSince >= activity.silenceMs) return true
        } else if (heardSpeech) {
            // Road noise, wind or a fan poke above the quiet bar for a moment; only a run of it is someone
            // talking again, so a blip doesn't start the pause over (#185). A run counts from its first frame's
            // start. Speech needs only a syllable's worth, so clipped words still hold; a level short of speech,
            // which noise reaches far more often, needs longer.
            if (loudSince < 0) loudSince = frameStart
            if (elapsedMs - loudSince >= if (speech) SPEECH_RUN_MS else MURMUR_RUN_MS) silenceSince = -1
        }
        if (!heardSpeech && activity.idleMs > 0 && elapsedMs >= activity.idleMs) return true
        return elapsedMs >= activity.maxMs
    }

    private companion object {
        const val CALIBRATION_MS = 300L
        const val SPEECH_OVER_NOISE = 2.5f
        const val QUIET_OVER_NOISE = 1.6f
        const val QUIET_FRACTION = 0.8f
        /** How long the level has to stay at speech, in a pause, to count as talking again: a syllable's worth. */
        const val SPEECH_RUN_MS = 60L
        /** The same for a level between quiet and speech, which noise reaches more often. */
        const val MURMUR_RUN_MS = 200L
    }
}

/** One finished recording. [heardSpeech] is false when it never rose above the speech level. */
class Recording(val bytes: ByteArray, val mimeType: String, val heardSpeech: Boolean)

/** The device microphone. One recording at a time. */
interface VoiceRecorder {
    /**
     * Records until [activity] says the speaker is done, [finish] is called, or the caller is
     * cancelled (which discards it). [onLevel] gets the live input level, 0..1; [onSpeech] fires
     * once, when speech is first heard.
     */
    suspend fun record(activity: VoiceActivity, onLevel: (Float) -> Unit = {}, onSpeech: () -> Unit = {}): Recording

    /** Ends the recording in progress now and keeps what was said. */
    fun finish()

    /**
     * Listens while the agent works and speaks, for someone talking over it ([bargeIn] decides), with the echo
     * cancelling a call uses. [playing] says whether the voice is sounding right now; [onTrip] fires the moment
     * someone cuts in, and [onLevel] gets the input level from then on. [onListening] fires once the microphone is
     * actually listening. Returns what they said, from its first syllable, once they pause; null (or a throw) when the
     * microphone couldn't listen or stopped sending audio. Cancelling stops it. Its [Recording.heardSpeech] says whether they went on talking once the
     * voice held ([BargeIn.spoke]), which its own echo doesn't.
     *
     * A recorder that can't listen over the voice never returns, so nobody ever cuts in.
     */
    suspend fun listenOver(
        bargeIn: BargeIn,
        playing: () -> Boolean,
        onTrip: () -> Unit,
        onLevel: (Float) -> Unit = {},
        onListening: () -> Unit = {},
    ): Recording? = awaitCancellation()
}

/**
 * The device's own speech recognizer, for dictation that never goes to the gateway: it writes words
 * down as they are said and ends on its own when the speaker stops.
 */
interface DeviceDictation {
    /** Whether this device has a recognizer to use. */
    fun available(): Boolean

    /**
     * Listens until the speaker is done, [finish] is called, or the caller is cancelled (which discards
     * it). [onPartial] gets the words so far, [onLevel] the input level, 0..1. Returns what was said,
     * blank when nothing was heard; throws [DeviceDictationUnavailable] when no recognizer could start,
     * and otherwise with a readable message when the recognizer fails.
     */
    suspend fun listen(onPartial: (String) -> Unit = {}, onLevel: (Float) -> Unit = {}): String

    /** Ends the listening in progress now and keeps what was said. */
    fun finish()
}

/** No recognizer on the device could start listening; the gateway transcribes instead. */
class DeviceDictationUnavailable : Exception("No speech recognizer on this phone could start.")

/** A device without a speech recognizer: dictation always goes to the gateway. */
object NoDeviceDictation : DeviceDictation {
    override fun available() = false
    override suspend fun listen(onPartial: (String) -> Unit, onLevel: (Float) -> Unit): String = throw DeviceDictationUnavailable()
    override fun finish() = Unit
}

/** Plays synthesized speech through the device speaker. */
interface SpeechPlayer {
    /**
     * Sets up chained chat audio; the returned action restores it. Best effort: neither throws, and a chat goes on
     * without it. Only a chat with talk-over on calls this, before its first listen; other voice modes don't.
     */
    fun chainedChatAudio(): () -> Unit = {}

    /** Plays the local connection cue once; cancelling stops it. Unsupported devices stay silent. */
    suspend fun playRingback() = Unit

    /** Plays [audio] to the end; cancelling stops it. */
    suspend fun play(audio: SpokenAudio)

    /**
     * Holds the voice where it is, and what starts meanwhile, until it's let go, when it goes on from there.
     * False for a player that can't, whose voice goes on regardless.
     */
    fun hold(on: Boolean): Boolean = false

    /**
     * Plays 16-bit little-endian PCM at [sampleRate], [channels] interleaved, as [chunks] arrive, until the
     * channel is closed and all of it has played; cancelling stops it at once. [onSound] says when sound starts
     * coming out, and when it has stopped for a moment, as it does while the agent works between sentences.
     *
     * A player that can't stream plays it all at once when the channel closes.
     */
    suspend fun playPcm(sampleRate: Int, channels: Int, chunks: ReceiveChannel<ByteArray>, onSound: (Boolean) -> Unit = {}) {
        val pcm = Buffer()
        for (chunk in chunks) pcm.write(chunk)
        if (pcm.size == 0L) return
        onSound(true)
        try {
            play(SpokenAudio(wav(pcm.readByteArray(), sampleRate, channels), "audio/wav"))
        } finally {
            onSound(false)
        }
    }
}

/** [pcm] (16-bit, [channels] interleaved) as a WAV file. */
fun wav(pcm: ByteArray, sampleRate: Int, channels: Int = 1): ByteArray {
    val header = Buffer().apply {
        write("RIFF".encodeToByteArray())
        writeIntLe(36 + pcm.size)
        write("WAVEfmt ".encodeToByteArray())
        writeIntLe(16)
        writeShortLe(1.toShort()) // PCM
        writeShortLe(channels.toShort())
        writeIntLe(sampleRate)
        writeIntLe(sampleRate * channels * 2)
        writeShortLe((channels * 2).toShort())
        writeShortLe(16.toShort())
        write("data".encodeToByteArray())
        writeIntLe(pcm.size)
    }
    return header.readByteArray() + pcm
}

private val FENCED_CODE = Regex("```[\\s\\S]*?(?:```|$)")
private val INLINE_CODE = Regex("`([^`]+)`")
private val MARKDOWN_LINK = Regex("""\[([^\]]+)\]\(([^)]+)\)""")
private val URL = Regex("""\b(?:https?|file)://\S+""", RegexOption.IGNORE_CASE)
private val MEDIA_PATH = Regex("""[ \t]*MEDIA:\S+?(?=[.,;:!?)\]]*(?:\s|$))""")
private val LINE_FINAL_COLON = Regex(""":\s*$""", RegexOption.MULTILINE)
private val HEADING = Regex("""^#{1,6}\s+""", RegexOption.MULTILINE)
private val BULLET = Regex("""^\s*[-+*]\s+""", RegexOption.MULTILINE)
private val TABLE_RULE = Regex("""^\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)*\|?\s*$""", RegexOption.MULTILINE)
private val TABLE_ROW = Regex("""^\s*\|.*\|\s*$""", RegexOption.MULTILINE)
private val EMOJI = Regex("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}\\x{200D}]+")
private val MARKUP = Regex("[*_~>#|]")
private val WHITESPACE = Regex("""\s+""")

/**
 * A reply as it should sound (Desktop's sanitizeTextForSpeech, trimmed): code, links, file chips,
 * tables and emoji are silence rather than read out symbol by symbol.
 */
fun speakableText(markdown: String): String = markdown
    .replace(FENCED_CODE, "")
    .replace(TABLE_RULE, "")
    .replace(TABLE_ROW, "")
    .replace(LINE_FINAL_COLON, ".")
    .replace(MARKDOWN_LINK, "$1")
    .replace(INLINE_CODE, "$1")
    .replace(URL, "")
    .replace(MEDIA_PATH, "")
    .replace(EMOJI, " ")
    .replace(HEADING, "")
    .replace(BULLET, "")
    .replace(MARKUP, "")
    .replace(WHITESPACE, " ")
    .trim()

/**
 * A finished reply as its Read aloud action says it: [speakableText] once the pictures and files it delivered
 * are taken out, since they show as themselves. Empty when there's nothing to say: a reply of only code, say,
 * or only punctuation, which the gateway would have no words for.
 */
fun readAloudText(reply: String): String =
    speakableText(extractReplyMedia(reply).first).takeIf(::hasWordsToSay).orEmpty()

/**
 * Whether [text] has anything for a voice to say: a letter or a digit. A character past U+FFFF (`𝐃`, `𠀀`) is a pair
 * of surrogates, neither of which is a letter on its own, so it counts when it sits in a block of letters: the
 * scripts of the first supplementary plane, the mathematical letters and digits, and the CJK planes. Symbols out
 * there (music, emoji the strip missed, the invisible tags of a flag like England's) don't.
 */
fun hasWordsToSay(text: String): Boolean {
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
            val codePoint = 0x10000 + ((c.code - 0xD800) shl 10) + (text[i + 1].code - 0xDC00)
            if (codePoint in 0x10000..0x1CFFF || codePoint in 0x1D400..0x1D7FF || codePoint in 0x1E000..0x1EFFF ||
                codePoint in 0x20000..0x3FFFF
            ) return true
            i += 2
            continue
        }
        if (c.isLetterOrDigit()) return true
        i++
    }
    return false
}

/**
 * Cuts [text] into pieces at sentence ends where possible, so the first piece can be spoken while the rest
 * are still being synthesized. The first piece is kept short; each one after may be [growth] times the length
 * of the piece actually made before it (never under [firstChars]), up to [maxChars]. Every piece is one request
 * to the gateway's voice, and a piece is made while the one before it plays, so it has to be ready before that
 * one ends: growing from the real length, not the limit, keeps a short first sentence from being followed by a
 * piece too long to make in time. A 1,750-char reply of ordinary sentences is 4 pieces (6 at a flat 400).
 * [maxChars] keeps the longest piece at about 80 s of speech, inside the 120 s a speak request is given.
 *
 * A sentence longer than the piece it would go in (a list without full stops, say) is cut at its spaces, so it
 * neither delays the first words nor passes [maxChars]; only a single word longer than the limit stays whole.
 * Punctuation with no words is never a piece of its own, which the gateway couldn't voice: it goes with the piece
 * beside it while that stays inside its cap, and is left out where it wouldn't.
 */
fun speechChunks(text: String, maxChars: Int = 1200, firstChars: Int = 160, growth: Double = 3.0): List<String> {
    val sentences = sentencePieces(text)
    val chunks = mutableListOf<String>()
    val current = StringBuilder()
    var limit = minOf(firstChars, maxChars)
    // The limit of the piece after this one: it grows from the piece actually made.
    fun nextLimit() = minOf(maxChars, maxOf(firstChars, (current.length * growth).toInt()))
    fun flush() {
        chunks += current.toString()
        limit = nextLimit()
        current.clear()
    }
    fun append(part: String) {
        if (current.isNotEmpty()) current.append(' ')
        current.append(part)
    }
    for (sentence in sentences) {
        if (current.length + (if (current.isEmpty()) 0 else 1) + sentence.length <= limit) {
            append(sentence)
            continue
        }
        // Too long for this piece: one that fits the next piece starts it; a longer one fills this piece word by
        // word and goes on in the next.
        if (current.isNotEmpty() && sentence.length <= nextLimit()) {
            flush()
            append(sentence)
            continue
        }
        for (word in sentence.split(WHITESPACE)) {
            if (current.isNotEmpty() && current.length + word.length + 1 > limit) flush()
            append(word)
        }
    }
    if (current.isNotEmpty()) chunks += current.toString()
    // A piece of only punctuation (a leading `...`, the `!` left where an emoji was) gives the gateway no words to
    // say, and a piece it can't voice ends the reading: it goes with the piece before it, or the first one after,
    // while that piece stays inside its cap ([firstChars] for the first, [maxChars] after). Past the cap it's left
    // out, as there's nothing in it to hear. Text of only punctuation gives no pieces at all.
    val firstLimit = minOf(firstChars, maxChars)
    val spoken = mutableListOf<StringBuilder>()
    val before = StringBuilder()
    fun StringBuilder.join(part: String, cap: Int) {
        if (length + (if (isEmpty()) 0 else 1) + part.length > cap) return
        if (isNotEmpty()) this.append(' ')
        this.append(part)
    }
    for (chunk in chunks) {
        when {
            hasWordsToSay(chunk) -> {
                // Not `apply { append(...) }`: the local append above (into `current`) would win over the builder's.
                val piece = StringBuilder()
                if (before.isNotEmpty() && before.length + 1 + chunk.length <= firstLimit) piece.append(before).append(' ')
                before.clear()
                piece.append(chunk)
                spoken += piece
            }
            spoken.isEmpty() -> before.join(chunk, firstLimit)
            else -> spoken.last().join(chunk, if (spoken.size == 1) firstLimit else maxChars)
        }
    }
    return spoken.map { it.toString() }
}

/**
 * Cuts [text] into the sentences [speechChunks] fills its pieces with: a run of `.`, `!` or `?` plus any closing
 * quotes or brackets, followed by whitespace or the end of the text (Arabic and Devanagari stops count too, and in
 * Chinese or Japanese, written without spaces, a stop ends a sentence as it is). A full stop between digits is not
 * a sentence end — it's the point in `4.6` or a dot in a version like `2.0.1` — so it stays inside its sentence.
 * Splitting there made read aloud pause in the middle of a number ("four. Six") and turned `1.0×` into a piece
 * starting `0×`, which the voice backend can't say at all. Dotted names (`notes.md`) and the inner dots of `e.g.` or
 * `U.S.A.` stay put for the same reason; an abbreviation's last dot before a space still ends a sentence, as it
 * always did.
 *
 * The pieces keep every character of the input: joining them with a single space gives the text back, with one
 * space only where a piece boundary or a word cut fell — whitespace inside a sentence is kept as it is (a tab or
 * an internal newline stays), and the text reaching this through [speakableText] has had its runs collapsed.
 */
private fun sentencePieces(text: String): List<String> {
    val pieces = mutableListOf<String>()
    // Typographic quotes too: a model's `“Stop.” Then…` ends a sentence after the closing quote.
    fun closesSentence(c: Char) = c in SENTENCE_CLOSERS
    fun endsSentence(c: Char) = c in SENTENCE_STOPS || c in UNSPACED_STOPS
    var start = 0
    var i = 0
    while (i < text.length) {
        if (!endsSentence(text[i])) {
            i++
            continue
        }
        var end = i + 1
        var unspaced = text[i] in UNSPACED_STOPS
        while (end < text.length && (endsSentence(text[end]) || closesSentence(text[end]))) {
            if (text[end] in UNSPACED_STOPS) unspaced = true
            end++
        }
        // Chinese and Japanese put no space after a sentence: their own full stop ends one wherever it is, and so
        // does a `.`, `!` or `?` with one of their characters right after it.
        if (end < text.length && !text[end].isWhitespace() && !unspaced && !writtenWithoutSpaces(text[end])) {
            // Inside a number (a digit follows), or punctuation in the middle of a word: not a sentence end. The
            // whole run was just scanned and every character in it is a full stop, ! or ? or a closer, so none of
            // them can begin a sentence that ends elsewhere: carry on after the run. Rescanning the same suffix
            // from each character would make a long run cost time quadratic in its length.
            i = end
            continue
        }
        pieces += text.substring(start, end).trim()
        while (end < text.length && text[end].isWhitespace()) end++
        start = end
        i = end
    }
    val tail = text.substring(start).trim()
    if (tail.isNotEmpty()) pieces += tail
    return pieces
}

/** Full stops, `!` and `?` that a space follows: Latin ones, Arabic `؟` and `۔`, and Devanagari `।` and `॥`. */
private const val SENTENCE_STOPS = ".!?؟۔।॥"

/** Chinese and Japanese full stops, `！` and `？`, which end a sentence with no space after them. */
private const val UNSPACED_STOPS = "。！？｡"

/** What may close a sentence after its full stop, `!` or `?`: quotes and brackets, plain or typographic. */
private const val SENTENCE_CLOSERS = "\"')]”’»›」』）】》〉"

/** Chinese and Japanese characters and punctuation, full-width forms: text written with no spaces between sentences. */
private fun writtenWithoutSpaces(c: Char) = c in '　'..'鿿' || c in '豈'..'﫿' || c in '＀'..'￯'

private val STOP_PHRASES = setOf(
    "stop", "stop listening", "stop it", "stop please", "please stop", "stop stop", "that is all", "that's all",
    "never mind", "nevermind", "end conversation", "end the conversation", "goodbye", "good bye", "bye", "cancel",
)
private val ADDRESS_PREFIXES = listOf("hey hermes", "hermes", "okay", "ok", "hey")
private val PUNCTUATION = Regex("""[\p{P}]+""")

/**
 * A whole utterance that only says "stop" (or "never mind", "goodbye", …), optionally addressed to
 * Hermes, ends the conversation instead of being sent. "Stop the container" is a real request.
 */
fun isVoiceStopCommand(transcript: String): Boolean {
    var text = transcript.lowercase().replace(PUNCTUATION, " ").replace(WHITESPACE, " ").trim()
    ADDRESS_PREFIXES.firstOrNull { text.startsWith("$it ") }?.let { text = text.removePrefix(it).trim() }
    return text in STOP_PHRASES
}
