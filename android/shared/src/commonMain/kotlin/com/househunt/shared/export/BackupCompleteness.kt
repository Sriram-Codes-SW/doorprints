package com.househunt.shared.export

/** One kind of data a backup made with the chosen options leaves out (see [BackupCompleteness]). */
enum class BackupGap {
    /** "Shortlisted only": every house that is not shortlisted, and the visits with no house. */
    HOUSES_NOT_SHORTLISTED,

    /** A hand-picked set ([ExportScope.SELECTED]): every house that was not picked. */
    HOUSES_NOT_SELECTED,

    /** "All houses" with *Include rejected houses* off. */
    REJECTED_HOUSES,

    /** "Photos of shortlisted houses" while houses that are not shortlisted are in the file. */
    PHOTOS_NOT_SHORTLISTED,

    /** "No photos". */
    PHOTOS,

    /** *Include contact details* off: owner and broker names and phone numbers. */
    CONTACTS,
}

/**
 * Whether a full backup (`ExportFormat.BACKUP`) made with some [ExportOptions] holds everything on the phone, and if
 * not, what it leaves out (UX review, round 11).
 *
 * The scope, rejected, photos and contacts options apply to the backup as to every other format, and a backup is
 * the one file a user keeps in order to wipe or replace the phone. A "Full backup" that quietly restores only part
 * of their data puts it at risk, so the export screen names every gap before the file is made and the result says
 * "partial backup" instead of calling it complete (docs/05 section 14.6 reserves "Full backup" for the complete
 * file). Pure and in `:shared` so the web's `/data` page can apply the same rule, gap for gap.
 *
 * Gaps that another choice already covers are not listed twice: with "Shortlisted only" the rejected houses and the
 * photos of houses that are not shortlisted are already part of [BackupGap.HOUSES_NOT_SHORTLISTED].
 */
object BackupCompleteness {

    /** What [options] leave out of a backup, in the order the screen names them; empty for a complete backup. */
    fun gaps(options: ExportOptions): List<BackupGap> = buildList {
        when (options.scope) {
            ExportScope.ALL -> if (!options.includeRejected) add(BackupGap.REJECTED_HOUSES)
            ExportScope.SHORTLISTED -> add(BackupGap.HOUSES_NOT_SHORTLISTED)
            ExportScope.SELECTED -> add(BackupGap.HOUSES_NOT_SELECTED)
        }
        when (options.photos) {
            PhotoScope.ALL -> Unit
            // Every house in a shortlist-only file is shortlisted, so all of their photos are in it.
            PhotoScope.SHORTLISTED ->
                if (options.scope != ExportScope.SHORTLISTED) add(BackupGap.PHOTOS_NOT_SHORTLISTED)
            PhotoScope.NONE -> add(BackupGap.PHOTOS)
        }
        if (!options.includeContacts) add(BackupGap.CONTACTS)
    }

    /** True when a backup made with [options] can restore everything on the phone. */
    fun isComplete(options: ExportOptions): Boolean = gaps(options).isEmpty()
}
