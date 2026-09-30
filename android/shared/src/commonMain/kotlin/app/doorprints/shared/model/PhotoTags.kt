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

package app.doorprints.shared.model

/** Why a list of tags is refused (the server's PUT and a backup refuse it; the editor never builds one). */
enum class TagProblem { TOO_MANY, EMPTY, TOO_LONG, FIXED_KEY, DUPLICATE }

/**
 * A photo's tags (docs/11 5.7, slice 5): at most [MAX], each a fixed key of [FIXED] (translated in the UI) or a custom
 * text of 1..[MAX_LENGTH] characters, no two the same ignoring case, and a custom tag never equal to a fixed key in any
 * case. The web's `photo-tags.ts` has the same rules and passes the same vector M6.
 */
object PhotoTags {
    val FIXED: List<String> = listOf(
        "EXTERIOR", "ENTRANCE", "KITCHEN_FITTINGS", "BATHROOM_FITTINGS", "DAMP", "CRACK", "LEAK", "VIEW", "WATER_TANK",
        "METER", "PARKING", "LIFT", "GOOD_POINT", "PROBLEM", "MOVE_IN",
    )

    /** The tag of the move-in condition record (docs/11 5.24): *Add a photo* on the Moving in card chooses it. */
    const val MOVE_IN = "MOVE_IN"

    const val MAX = 10
    const val MAX_LENGTH = 30

    fun isFixed(tag: String): Boolean = tag in FIXED

    /**
     * What a reader keeps: each tag trimmed, a blank or over-long one dropped, one that is a fixed key in another case
     * read as that key, a repeat (ignoring case) dropped, then the first [MAX]. Never null: no tags is an empty list.
     */
    fun coerced(tags: List<String>?): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        for (raw in tags.orEmpty()) {
            val t = raw.trim()
            if (t.isEmpty() || t.length > MAX_LENGTH) continue
            val upper = t.uppercase()
            val tag = if (isFixed(upper)) upper else t
            if (!seen.add(tag.lowercase())) continue
            out += tag
            if (out.size == MAX) break
        }
        return out
    }

    /** Null when [tags] may be stored as they are; else the first problem (M6), as the server's PUT refuses it. */
    fun validate(tags: List<String>): TagProblem? {
        if (tags.size > MAX) return TagProblem.TOO_MANY
        val seen = HashSet<String>()
        for (tag in tags) {
            if (tag.isBlank()) return TagProblem.EMPTY
            if (tag.length > MAX_LENGTH) return TagProblem.TOO_LONG
            if (!isFixed(tag) && isFixed(tag.uppercase())) return TagProblem.FIXED_KEY
            if (!seen.add(tag.lowercase())) return TagProblem.DUPLICATE
        }
        return null
    }

    /** [tags] with [tag] added at the end (the editor's chip or its custom field); unchanged at [MAX] or for a repeat. */
    fun with(tags: List<String>, tag: String): List<String> {
        if (tags.size >= MAX) return tags
        val next = coerced(tags + tag)
        return if (next.size > tags.size) next else tags
    }

    /** [tags] without [tag] (ignoring case). */
    fun without(tags: List<String>, tag: String): List<String> = tags.filter { !it.equals(tag, ignoreCase = true) }
}

/**
 * A photo's metadata (docs/11 5.7, slice 5): the room of its house it shows (may dangle: shown as untagged), its tags
 * and its caption, and when they were last edited ([metaUpdatedAt], epoch ms, 0 = never): the last write wins on it, in
 * sync and in an import.
 */
data class PhotoMeta(
    val roomId: String? = null,
    val tags: List<String> = emptyList(),
    val caption: String? = null,
    val metaUpdatedAt: Long = 0L,
) {
    /** True when the photo has a room, a tag or a caption. */
    val isSet: Boolean get() = roomId != null || tags.isNotEmpty() || caption != null

    /** The same room, tags and caption as [other] (the time aside): the editor writes only a real change. */
    fun sameValues(other: PhotoMeta): Boolean = roomId == other.roomId && tags == other.tags && caption == other.caption

    /** True when every value is in range: what the server's PUT and a backup's check demand. */
    val isValid: Boolean
        get() = (roomId == null || (roomId.isNotEmpty() && roomId.length <= MAX_ROOM_ID)) &&
            PhotoTags.validate(tags) == null && (caption?.length ?: 0) <= MAX_CAPTION && metaUpdatedAt >= 0

    companion object {
        const val MAX_CAPTION = 200
        const val MAX_ROOM_ID = 64

        /**
         * What a reader keeps, like the web's `cleanMeta`: a room id when it is 1..[MAX_ROOM_ID] characters, the tags
         * [PhotoTags.coerced], the caption cut at [MAX_CAPTION] (none when blank) and a negative time 0.
         */
        fun coerced(roomId: String?, tags: List<String>?, caption: String?, metaUpdatedAt: Long?): PhotoMeta = PhotoMeta(
            roomId = roomId?.takeIf { it.isNotEmpty() && it.length <= MAX_ROOM_ID },
            tags = PhotoTags.coerced(tags),
            caption = caption?.takeIf { it.isNotBlank() }?.take(MAX_CAPTION),
            metaUpdatedAt = (metaUpdatedAt ?: 0L).coerceAtLeast(0L),
        )

        /** Last write wins: an incoming edit replaces the stored one only when it is newer. */
        fun incomingWins(storedAt: Long, incomingAt: Long): Boolean = incomingAt > storedAt
    }
}
