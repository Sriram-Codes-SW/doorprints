/*
 * Copyright 2026 Sriram (Sriram-Codes-SW)
 *
 * This file is part of Doorprints.
 *
 * Doorprints is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, version 3 of the License.
 *
 * Doorprints is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with Doorprints (the file LICENSE;
 * the file NOTICE has additional permissions under section 7). If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package app.doorprints.ui

import androidx.compose.runtime.Composable
import app.doorprints.data.Repository
import app.doorprints.location.Place

/**
 * The app features the house form ([HouseEditScreen]) needs that are still Android code in `:app` (ADR-23 CMP-6 P6a):
 * the reverse geocoder, the "Are you at a house?" alert, and the photos (the camera and the photo picker, shrinking and
 * storing a picked photo, and what the image loader reads for a stored one). Android: `AndroidHouseFormServices` in
 * `:app`. iOS: `IosHouseFormServices` (CMP-8b), with no photos added and no geocoder yet.
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
     * the Exif block), with [tags] already chosen (slice 5: the Moving in card's *Add a photo* passes MOVE_IN). Runs
     * off the main thread.
     */
    suspend fun addPhoto(houseId: String, photo: PickedPhoto, tags: List<String> = emptyList()): Repository.AddPhotoResult

    /**
     * What the image loader (Coil) is given for the stored photo [photoId] (Android: the file). By id, not by the row's
     * stored path, which goes stale on iOS when the app's container folder moves (S4b-BL-52).
     */
    fun photoModel(photoId: String): Any
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
