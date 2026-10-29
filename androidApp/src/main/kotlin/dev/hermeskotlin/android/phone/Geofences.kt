package dev.hermeskotlin.android.phone

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import dev.hermeskotlin.core.phone.PhoneTool
import dev.hermeskotlin.core.phone.WatchedPlace
import dev.hermeskotlin.core.phone.WatchedPlaces
import dev.hermeskotlin.core.phone.enabledPhoneTools
import dev.hermeskotlin.core.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The places the phone watches for the agent, through Android's own proximity alerts (no Google services). Kept on
 * the phone; armed while the Geofences tool is on and disarmed the moment it's switched off, so a place the user
 * still sees listed never fires behind a switch that's off. Android forgets alerts on a restart: every start of
 * Herald arms them again (a restart or an update wakes Herald for that).
 */
class Geofences(private val context: Context, private val settings: SettingsStore, private val scope: CoroutineScope) : WatchedPlaces {

    private val prefs = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
    private val _places = MutableStateFlow(WatchedPlace.decode(prefs.getString(KEY, null)))
    override val places: StateFlow<List<WatchedPlace>> = _places.asStateFlow()

    private val on = settings.settings.filterNotNull().map { PhoneTool.Geofences in it.enabledPhoneTools() }.distinctUntilChanged()

    /**
     * Whether the tool is on now: a place that fires behind a switch turned off is dropped. Waits for the settings,
     * which a process just woken by the alert is still reading.
     */
    suspend fun enabled(): Boolean = PhoneTool.Geofences in settings.settings.filterNotNull().first().enabledPhoneTools()

    /** Whether the Location tool is on: only then may adding a place tell the agent where the phone is. */
    suspend fun locationOn(): Boolean = PhoneTool.Location in settings.settings.filterNotNull().first().enabledPhoneTools()

    /** Records where the phone is for [id]; true when that's news (see [WatchedPlace.inside]). */
    fun moved(id: String, entering: Boolean): Boolean = synchronized(this) {
        val place = find(id) ?: return false
        if (!place.changes(entering)) return false
        save(_places.value.map { if (it.id == id) it.copy(inside = entering) else it })
        true
    }

    override fun refresh() {
        scope.launch { arm(if (enabled()) _places.value else emptyList()) }
    }

    fun start() {
        scope.launch {
            // By id: recording where the phone is changes the list, and mustn't arm everything again for it.
            combine(on, _places) { on, places -> if (on) places else emptyList() }
                .distinctUntilChanged { a, b -> a.map { it.id } == b.map { it.id } }
                .collect(::arm)
        }
    }

    fun find(id: String): WatchedPlace? = _places.value.firstOrNull { it.id == id }

    /** Adds [place]; false when the phone already watches as many as it keeps. */
    fun add(place: WatchedPlace): Boolean = synchronized(this) {
        if (_places.value.size >= MAX_PLACES) return false
        save(_places.value + place)
        true
    }

    override fun remove(id: String) = synchronized(this) {
        disarm(id)
        save(_places.value.filterNot { it.id == id })
    }

    private fun save(places: List<WatchedPlace>) {
        prefs.edit { putString(KEY, WatchedPlace.encode(places)) }
        _places.value = places
    }

    /** Armed: exactly [places]. Others armed before are taken back. */
    private val armed = mutableSetOf<String>()

    @SuppressLint("MissingPermission") // allowed() checks it first.
    private fun arm(places: List<WatchedPlace>) = synchronized(this) {
        val manager = context.getSystemService(LocationManager::class.java) ?: return
        // Every kept place too, not only those armed in this process: Android may still hold alerts from an earlier one.
        (armed + _places.value.map { it.id } - places.map { it.id }.toSet()).forEach(::disarm)
        if (!allowed()) return
        for (place in places) {
            try {
                // A repeat with the same intent replaces the alert: arming again on every start is safe.
                manager.addProximityAlert(place.latitude, place.longitude, place.radiusMeters.toFloat(), NEVER, intent(place.id))
                armed += place.id
            } catch (e: SecurityException) {
                // Location taken back meanwhile: watched again on the next start once it's allowed.
            } catch (e: IllegalArgumentException) {
                // The phone has no provider for alerts.
            }
        }
    }

    private fun disarm(id: String) {
        context.getSystemService(LocationManager::class.java)?.let { manager ->
            runCatching { manager.removeProximityAlert(intent(id)) }
        }
        armed -= id
    }

    /** Precise location all the time: proximity alerts need precise access, and fire while Herald is closed. */
    fun allowed(): Boolean = granted(Manifest.permission.ACCESS_FINE_LOCATION) &&
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION))

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Mutable: Android adds whether the phone entered or left. Explicit, so only Herald's receiver gets it. */
    private fun intent(id: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        id.hashCode(),
        Intent(context, GeofenceReceiver::class.java).setAction(ACTION).setData(android.net.Uri.fromParts("herald-place", id, null))
            .putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0),
    )

    companion object {
        const val ACTION = "dev.hermeskotlin.action.PLACE"
        const val EXTRA_ID = "place"
        const val MAX_PLACES = 20
        private const val NEVER = -1L
        private const val STORE = "watched_places"
        private const val KEY = "places"
    }
}
