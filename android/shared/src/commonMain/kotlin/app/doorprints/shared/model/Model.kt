package app.doorprints.shared.model

/**
 * House status. Stored and sent by [name] (shared with the API, the web app and the Room database), so the constant
 * names must never change. The label is translated by each app (Android: HouseStatus.labelRes in :app).
 */
enum class HouseStatus {
    NEW,
    SHORTLISTED,
    REJECTED,
    ;

    companion object {
        /** Parses the wire/database name; anything unknown or missing becomes [NEW] (same as the v0.1 mapper). */
        fun fromWire(value: String?): HouseStatus = entries.firstOrNull { it.name == (value ?: "NEW") } ?: NEW
    }
}

/** How a visit was recorded: by Hunt mode's stay detector or by the user. Stored and sent by [name]. */
enum class VisitSource {
    AUTO,
    MANUAL,
    ;

    companion object {
        /** Parses the wire/database name; anything unknown or missing becomes [MANUAL]. */
        fun fromWire(value: String?): VisitSource = entries.firstOrNull { it.name == (value ?: "MANUAL") } ?: MANUAL
    }
}

/**
 * Things worth checking at every house, each scored 0 (bad) to 5 (great). The keys are language-neutral and shared
 * with the API and the web app (check.water, ...); each app maps them to translated labels. Order is display order.
 */
object Checklist {
    val keys: List<String> = listOf(
        "water",
        "power",
        "parking",
        "sunlight",
        "ventilation",
        "noise",
        "security",
        "maintenance",
        "neighbourhood",
        "commute",
    )

    const val MIN_SCORE = 0
    const val MAX_SCORE = 5
}

/** Same cap as the server's app.limits.max-photos-per-house default (threat model F-06). */
const val MAX_PHOTOS_PER_HOUSE = 20

/** Overall house score, used for sorting ("best first"), Compare and the Hunt notification. */
object HouseScore {
    /**
     * 0-5 overall score: average of the checklist, blended 50/50 with the star rating when both exist.
     * Null if nothing has been scored yet. A 0 is a real score, not "missing".
     */
    fun of(checklist: Map<String, Int>, rating: Int?): Double? {
        val check = if (checklist.isEmpty()) null else checklist.values.average()
        val stars = rating?.toDouble()
        return when {
            check != null && stars != null -> (check + stars) / 2
            else -> check ?: stars
        }
    }

    /** Sort key for "best first": unscored houses go last. */
    fun rankKey(score: Double?): Double = score ?: -1.0
}
