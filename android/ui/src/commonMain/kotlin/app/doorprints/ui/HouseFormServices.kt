package app.doorprints.ui

import androidx.compose.runtime.Composable
import app.doorprints.data.Repository
import app.doorprints.location.Place

/**
 * The app features the house form ([HouseEditScreen]) needs that are still Android code in `:app` (ADR-23 CMP-6 P6a):
 * the reverse geocoder, the "Are you at a house?" alert, and the photos (the camera and the photo picker, shrinking and
 * storing a picked photo, and what the image loader reads for a stored one). Android: `AndroidHouseFormServices` in
 * `:app`. iOS: with the iOS shell (CMP-8).
 */
interface HouseFormServices {
    /** The street, locality and address at a point, or null when there is no geocoder or it has no answer. */
    suspend fun reverseGeocode(lat: Double, lon: Double): Place?

    /** Removes the "Are you at a house?" alert of [visitId]: the form has just saved a house for that visit. */
    fun clearVisitAlert(visitId: String)

    /**
     * The camera and the photo picker for this composition. [onPicked] gets each photo taken or picked; nothing is
     * called when the user backs out.
     */
    @Composable
    fun rememberPhotoSources(onPicked: (PickedPhoto) -> Unit): PhotoSources

    /**
     * Shrinks [photo] and stores it as a photo of house [houseId] (Android: `AndroidRepository.addPhoto`, which drops
     * the Exif block). Runs off the main thread.
     */
    suspend fun addPhoto(houseId: String, photo: PickedPhoto): Repository.AddPhotoResult

    /** What the image loader (Coil) is given for a stored photo's [path] (Android: the file). */
    fun photoModel(path: String): Any
}

/** A photo just taken or picked, before it is stored: [uri] is the platform's reference to it (Android: a `Uri`). */
class PickedPhoto(val uri: String)

/** The two ways to add a photo; see [HouseFormServices.rememberPhotoSources]. */
interface PhotoSources {
    /** Opens the camera; the photo arrives through `onPicked`. */
    fun takePhoto()

    /** Opens the system's photo picker (images only). */
    fun pickFromGallery()
}
