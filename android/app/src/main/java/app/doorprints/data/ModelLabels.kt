package app.doorprints.data

import androidx.annotation.StringRes
import app.doorprints.R
import app.doorprints.shared.model.Checklist
import app.doorprints.shared.model.HouseStatus

/**
 * Translated label of a status (values/strings.xml status_*), as an Android resource for the screens and the Hunt
 * notification that still read Android resources. The enum itself lives in :shared; the Compose resource version and
 * the status glyph are in :ui (`ui/ModelLabels.kt`, CMP-4 P4c).
 */
@get:StringRes
val HouseStatus.labelRes: Int
    get() = when (this) {
        HouseStatus.NEW -> R.string.status_NEW
        HouseStatus.SHORTLISTED -> R.string.status_SHORTLISTED
        HouseStatus.REJECTED -> R.string.status_REJECTED
    }

/**
 * Translated labels (strings.xml check_*) for the shared [Checklist] keys, in display order. Keys are
 * language-neutral and shared with the API and the web app, like the web's check.water keys.
 */
object ChecklistLabels {
    val items: Map<String, Int> = Checklist.keys.associateWith { key ->
        when (key) {
            "water" -> R.string.check_water
            "power" -> R.string.check_power
            "parking" -> R.string.check_parking
            "sunlight" -> R.string.check_sunlight
            "ventilation" -> R.string.check_ventilation
            "noise" -> R.string.check_noise
            "security" -> R.string.check_security
            "maintenance" -> R.string.check_maintenance
            "neighbourhood" -> R.string.check_neighbourhood
            "commute" -> R.string.check_commute
            // A key added to :shared must get a translated label here first (ModelMappingTest checks this).
            else -> error("No label for checklist key '$key'")
        }
    }
}
