package app.doorprints.location

/**
 * A reverse-geocoded address: what `:app`'s `ReverseGeocoder` (Android's Geocoder) returns and the house form's
 * `fillPlace` (`:ui`) fills a new house from. Common since CMP-4 P4c (was in `:app`'s `location/Geo.kt`), with its
 * package kept.
 */
data class Place(val street: String?, val locality: String?, val address: String?)
