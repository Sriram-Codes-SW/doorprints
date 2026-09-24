package app.doorprints.data

import androidx.annotation.StringRes
import app.doorprints.R
import app.doorprints.shared.model.HouseStatus

/**
 * Translated label of a status (values/strings.xml status_*), as an Android resource for the Hunt notification, which
 * has no composition. The enum itself lives in :shared; the screens read the Compose resource version, and the status
 * glyph is in :ui (`ui/ModelLabels.kt`, CMP-4 P4c). The checklist labels are only Compose resources since CMP-6 P6a
 * (`ChecklistResources`).
 */
@get:StringRes
val HouseStatus.labelRes: Int
    get() = when (this) {
        HouseStatus.NEW -> R.string.status_NEW
        HouseStatus.SHORTLISTED -> R.string.status_SHORTLISTED
        HouseStatus.REJECTED -> R.string.status_REJECTED
    }
