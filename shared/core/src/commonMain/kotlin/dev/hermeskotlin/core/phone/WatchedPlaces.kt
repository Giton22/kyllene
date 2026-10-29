package dev.hermeskotlin.core.phone

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A place the phone watches for the agent (`phone_geofence`): arriving at or leaving it sends [message] to the chat
 * it was added from, as the user. Shown on the Phone tools page, where the user can remove it.
 */
@Serializable
data class WatchedPlace(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Int,
    /** "enter" or "exit". */
    val trigger: String,
    val message: String,
    /** Where the message goes: the gateway, profile and stored chat the place was added from. */
    val gateway: String,
    val profile: String? = null,
    val storedSessionId: String,
    val chatTitle: String? = null,
    val addedAtMillis: Long = 0,
    /**
     * Whether the phone was last inside, null when unknown. Android forgets this whenever an alert is armed again
     * (each start of Herald) and then reports "entered" for a phone already there: kept here, so that isn't sent
     * as a new arrival.
     */
    val inside: Boolean? = null,
) {
    val arriving: Boolean get() = trigger == ENTER

    /** What goes to the chat: the message, marked as coming from the place. Shown whole when the place is added. */
    val sent: String get() = "[${if (arriving) "Arrived at" else "Left"} $name · watched place] $message"

    /** Whether Android saying the phone [entering] (or left) is news: false for what was already so. */
    fun changes(entering: Boolean): Boolean = inside != entering

    companion object {
        const val ENTER = "enter"
        const val EXIT = "exit"

        private val json = Json { ignoreUnknownKeys = true }

        fun encode(places: List<WatchedPlace>): String = json.encodeToString(places)

        /** Empty for nothing saved or something unreadable: a broken list watches nothing rather than crash. */
        fun decode(text: String?): List<WatchedPlace> =
            text?.let { runCatching { json.decodeFromString<List<WatchedPlace>>(it) }.getOrNull() }.orEmpty()
    }
}

/** The places the phone watches, for the Phone tools page. */
interface WatchedPlaces {
    val places: StateFlow<List<WatchedPlace>>

    fun remove(id: String)

    /** Arms the places again, e.g. after the user granted location: Android tells no app when that happens. */
    fun refresh() {}
}

/** For a platform that can't watch places. */
object NoWatchedPlaces : WatchedPlaces {
    override val places: StateFlow<List<WatchedPlace>> = MutableStateFlow(emptyList())

    override fun remove(id: String) = Unit
}
