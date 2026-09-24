package com.househunt.app.ui

/**
 * The next step a location note offers (LocationPermissionNote in :app): the same on the Map, the house form and the
 * Assistant.
 *  - [ALLOW]: no location, Android will ask: *Allow location* launches the request.
 *  - [OPEN_SETTINGS]: no location, Android will not ask again: *Open settings*.
 *  - [TURN_ON_PRECISE]: approximate only, Android will ask: *Turn on precise location* launches the request again,
 *    and Android shows its "Change to precise location?" prompt.
 *  - [OPEN_SETTINGS_PRECISE]: approximate only, Android will not ask again: *Open settings*, and the note says to
 *    turn on *Use precise location* there (the page itself says location is *Allowed*).
 */
enum class LocationFix { ALLOW, OPEN_SETTINGS, TURN_ON_PRECISE, OPEN_SETTINGS_PRECISE }
