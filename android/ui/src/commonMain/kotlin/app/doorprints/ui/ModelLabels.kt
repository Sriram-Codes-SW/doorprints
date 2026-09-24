package app.doorprints.ui

import app.doorprints.shared.model.Checklist
import app.doorprints.shared.model.HouseStatus
import app.doorprints.ui.res.*
import org.jetbrains.compose.resources.StringResource

/*
 * The UI's names for the shared enums and checklist keys, as Compose resources (CMP-4 P4c, for Compare; every screen's
 * since CMP-6 P6a). `:app`'s `data/ModelLabels.kt` keeps an Android-resource version of the status labels
 * (`HouseStatus.labelRes`) for the Hunt notification, with the same text (`StringParityTest`).
 */

/** Translated label of a status (`status_*`). */
val HouseStatus.labelResource: StringResource
    get() = when (this) {
        HouseStatus.NEW -> Res.string.status_NEW
        HouseStatus.SHORTLISTED -> Res.string.status_SHORTLISTED
        HouseStatus.REJECTED -> Res.string.status_REJECTED
    }

/**
 * The status glyph shown before the status text (UX-002): ● New, ★ Shortlisted, ✕ Rejected, so the status never rests
 * on colour alone. Decorative: every place that draws it keeps it out of TalkBack's speech, which reads the text.
 * Common since CMP-4 P4c (was `:app`'s `data/ModelLabels.kt`).
 */
val HouseStatus.glyph: String
    get() = when (this) {
        HouseStatus.NEW -> "●"
        HouseStatus.SHORTLISTED -> "★"
        HouseStatus.REJECTED -> "✕"
    }

/**
 * Translated labels (`check_*`) for the shared [Checklist] keys, in display order. Keys are language-neutral and shared
 * with the API and the web app, like the web's check.water keys.
 */
object ChecklistResources {
    val items: Map<String, StringResource> = Checklist.keys.associateWith { key ->
        when (key) {
            "water" -> Res.string.check_water
            "power" -> Res.string.check_power
            "parking" -> Res.string.check_parking
            "sunlight" -> Res.string.check_sunlight
            "ventilation" -> Res.string.check_ventilation
            "noise" -> Res.string.check_noise
            "security" -> Res.string.check_security
            "maintenance" -> Res.string.check_maintenance
            "neighbourhood" -> Res.string.check_neighbourhood
            "commute" -> Res.string.check_commute
            // A key added to :shared must get a translated label here first (ModelLabelsTest checks this).
            else -> error("No label for checklist key '$key'")
        }
    }
}
