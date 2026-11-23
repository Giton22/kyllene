package dev.hermeskotlin.ui.pet

import dev.hermeskotlin.core.pet.PetApi
import dev.hermeskotlin.core.pet.PetChoice
import dev.hermeskotlin.core.pet.PetGallery
import dev.hermeskotlin.core.pet.PetSprite
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import dev.hermeskotlin.ui.resources.*
import org.jetbrains.compose.resources.getString

data class PetGalleryState(
    val gallery: PetGallery? = null,
    val loading: Boolean = false,
    val error: String? = null,
    /** The pet being adopted right now. */
    val adopting: String? = null,
)

/** The open chat's profile pet: the active sprite, and the gallery to adopt one from. */
class PetController(private val api: PetApi, private val scope: CoroutineScope) {

    private var profile: String? = null
    private var refreshJob: Job? = null
    private var galleryJob: Job? = null

    private val _sprite = MutableStateFlow<PetSprite?>(null)

    /** The active pet; null when the profile has none showing. */
    val sprite: StateFlow<PetSprite?> = _sprite.asStateFlow()

    private val _gallery = MutableStateFlow(PetGalleryState())
    val gallery: StateFlow<PetGalleryState> = _gallery.asStateFlow()

    private val thumbnails = mutableMapOf<String, ByteArray?>()

    /** Points at [profile]'s pet; a different profile has its own. */
    fun bind(profile: String?) {
        if (profile != this.profile) {
            this.profile = profile
            refreshJob?.cancel()
            galleryJob?.cancel()
            _sprite.value = null
            _gallery.value = PetGalleryState()
            thumbnails.clear()
        }
        refresh()
    }

    fun refresh() {
        val requestedProfile = profile
        refreshJob?.cancel()
        refreshJob = scope.launch {
            try {
                val active = api.active(requestedProfile, _sprite.value)
                if (this@PetController.profile == requestedProfile) _sprite.value = active
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // An older gateway without pets, or offline: keep whatever is showing.
            }
        }
    }

    fun loadGallery() {
        val requestedProfile = profile
        galleryJob?.cancel()
        _gallery.update { it.copy(loading = true, error = null) }
        galleryJob = scope.launch {
            try {
                val gallery = api.gallery(requestedProfile)
                if (this@PetController.profile == requestedProfile) {
                    _gallery.update { it.copy(gallery = gallery, loading = false) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (this@PetController.profile == requestedProfile) {
                    _gallery.update { it.copy(loading = false, error = e.message ?: getString(Res.string.pet_error_load)) }
                }
            }
        }
    }

    suspend fun thumbnail(pet: PetChoice): ByteArray? {
        if (pet.slug in thumbnails) return thumbnails[pet.slug]
        val requestedProfile = profile
        return try {
            api.thumbnail(pet, requestedProfile).also {
                if (this.profile == requestedProfile) thumbnails[pet.slug] = it
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** Adopts [pet]; returns its name, or throws with the gateway's reason. */
    suspend fun adopt(pet: PetChoice): String {
        val requestedProfile = profile
        _gallery.update { it.copy(adopting = pet.slug, error = null) }
        try {
            val name = api.select(pet.slug, requestedProfile)
            if (this.profile == requestedProfile) {
                _gallery.update { state ->
                    state.copy(gallery = state.gallery?.copy(enabled = true, active = pet.slug))
                }
                val active = api.active(requestedProfile, null)
                if (this.profile == requestedProfile) _sprite.value = active
            }
            return name
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (this.profile == requestedProfile) {
                _gallery.update { it.copy(error = e.message ?: getString(Res.string.pet_error_adopt, pet.displayName)) }
            }
            throw e
        } finally {
            if (this.profile == requestedProfile) {
                _gallery.update { if (it.adopting == pet.slug) it.copy(adopting = null) else it }
            }
        }
    }

    /** Adopts the pet whose id or name is [name]; null when the gallery has no such pet. */
    suspend fun adopt(name: String): String? {
        val requestedProfile = profile
        val gallery = _gallery.value.gallery ?: api.gallery(requestedProfile).also { g ->
            if (this.profile == requestedProfile) _gallery.update { it.copy(gallery = g) }
        }
        val pet = gallery.pets.firstOrNull { it.slug.equals(name, ignoreCase = true) }
            ?: gallery.pets.firstOrNull { it.displayName.equals(name, ignoreCase = true) }
            ?: return null
        return adopt(pet)
    }

    /** Turns the pet display off for this profile (on every client, as Desktop's picker does). */
    suspend fun hide() {
        val requestedProfile = profile
        api.disable(requestedProfile)
        if (this.profile == requestedProfile) {
            _sprite.value = null
            _gallery.update { it.copy(gallery = it.gallery?.copy(enabled = false)) }
        }
    }
}
