package com.househunt.app.data

import androidx.annotation.StringRes
import com.househunt.app.R
import com.househunt.shared.model.Checklist
import com.househunt.shared.model.HouseStatus

/** Translated label of a status (values/strings.xml status_*). The enum itself lives in :shared. */
@get:StringRes
val HouseStatus.labelRes: Int
    get() = when (this) {
        HouseStatus.NEW -> R.string.status_NEW
        HouseStatus.SHORTLISTED -> R.string.status_SHORTLISTED
        HouseStatus.REJECTED -> R.string.status_REJECTED
    }

/**
 * The status glyph shown before the status text (UX-002): ● New, ★ Shortlisted, ✕ Rejected, so the status never rests
 * on colour alone. Decorative: every place that draws it keeps it out of TalkBack's speech, which reads the text.
 */
val HouseStatus.glyph: String
    get() = when (this) {
        HouseStatus.NEW -> "●"
        HouseStatus.SHORTLISTED -> "★"
        HouseStatus.REJECTED -> "✕"
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
