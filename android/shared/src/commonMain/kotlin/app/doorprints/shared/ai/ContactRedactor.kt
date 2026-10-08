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

package app.doorprints.shared.ai

/**
 * Keeps a house's contact person out of text bound for Gemini on the device (threat model F-30, requirement AI-010):
 * the server's `ContactRedactor` (backend `app.doorprints.server.ai`), ported rule for rule for on-device AI (docs/03
 * §13.1, ADR-26). The shared test vectors (`docs/ai/evals/parity-vectors.json`) hold both to the same answers.
 *
 * [Redactor.freeText] for what the user types freely (label, checklist keys, listing URL, notes): the whole saved name
 * and every name part of 3+ letters that is not an honorific. [Redactor.place] for address, street and locality: the
 * whole name only, so "Kumar Park" survives. Both remove the saved phone (8+ digits, any separators) and anything that
 * looks like a phone number or an email address (the address goes whole, before the name parts). Placeholders [CONTACT],
 * [PHONE] and [EMAIL].
 *
 * Written without character-class intersections and `\R`, which Kotlin/Native's regex does not share with the JVM.
 */
object ContactRedactor {
    const val CONTACT = "[contact]"
    const val PHONE = "[phone]"
    const val EMAIL = "[email]"

    /** Line prefix under which documents indexed before the fix stored the contact name. */
    private val STORED_CONTACT_LINE =
        Regex("^[ \\t]*Contact[ \\t]*:.*(?:\\r\\n|\\n|\\r|$)", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))

    private const val WORD = "[\\p{L}\\p{M}\\p{N}]"
    private val PHONE_LIKE = Regex(
        "(?<![\\p{L}\\p{M}\\p{N}+])(?:" +
            "\\+\\d(?:[ .()\\-]{0,2}\\d){6,14}" + // +<cc> ... (7-15 digits)
            "|(?:(?:\\+?91|0)[ \\-]?)?[6-9](?:[ .\\-]?\\d){9}" + // Indian mobile
            "|0\\d{2,4}[ \\-]?\\d{3,4}[ \\-]?\\d{3,4}" + // STD code + landline
            "|\\d{10,15}" + // long digit run
            ")(?!$WORD)",
    )

    /**
     * An email address: local part (letters, digits, `._%+-`), `@`, a dotted domain. A URL or a bare handle is not one.
     * The lookbehind makes the match start at the front of a run (linear time, and a long local part goes whole).
     */
    private val EMAIL_LIKE = Regex(
        "(?<![\\p{L}\\p{M}\\p{N}._%+-])[\\p{L}\\p{M}\\p{N}._%+-]+@[\\p{L}\\p{N}-]+(?:\\.[\\p{L}\\p{N}-]+)+" +
            "(?![\\p{L}\\p{M}\\p{N}])",
    )
    private val HONORIFICS = setOf(
        "mr", "mrs", "ms", "miss", "dr", "sri", "shri", "smt", "kumari", "sir", "madam", "uncle", "aunty", "auntie",
        "anna", "akka", "ji", "garu", "owner", "broker", "agent", "landlord", "the", "and",
    )
    private val NON_LETTERS = Regex("[^\\p{L}\\p{M}]+")
    private val SPACES = Regex("\\s+")

    /** A redactor for one house's text, knowing its contact name and phone (either may be null). */
    fun forContact(contactName: String?, contactPhone: String?) = Redactor(contactName, contactPhone)

    /** For text that may hold an old `Contact:` line: drops that line, then redacts like [Redactor.freeText]. */
    fun scrubStoredText(text: String?, contactName: String?, contactPhone: String?): String? {
        if (text.isNullOrEmpty()) return text
        return forContact(contactName, contactPhone).freeText(STORED_CONTACT_LINE.replace(text, ""))
    }

    /** Only the generic rules (phone-like numbers, then email addresses), for text with no known contact. */
    fun redactGeneric(text: String?): String? =
        if (text.isNullOrEmpty()) text else EMAIL_LIKE.replace(PHONE_LIKE.replace(text, PHONE), EMAIL)

    /** Redacts one house's contact name and phone from text; made with [forContact]. */
    class Redactor internal constructor(contactName: String?, contactPhone: String?) {
        private val fullName: List<Regex>
        private val nameParts: List<Regex>
        private val savedPhone: Regex? = phonePattern(contactPhone)

        init {
            val name = contactName?.trim()?.replace(SPACES, " ") ?: ""
            val significant = mutableListOf<String>()
            val tokens = mutableListOf<String>()
            val initials = mutableListOf<String>()
            for (p in name.split(NON_LETTERS)) {
                if (p.isEmpty() || p.lowercase() in HONORIFICS) continue
                tokens += p
                if (codePoints(p) >= 3) significant += p else initials += p
            }
            val full = mutableListOf<Regex>()
            if (codePoints(name) >= 2) full += word(Regex.escape(name))
            if (initials.isNotEmpty() && significant.isNotEmpty()) {
                val orders = linkedSetOf(tokens.toList(), initials + significant, significant + initials)
                for (order in orders) full += word(joinWithInitials(order))
            }
            if (significant.size >= 2) {
                full += word(joinParts(significant))
                val reversed = significant.reversed()
                if (reversed != significant) full += word(joinParts(reversed))
            }
            fullName = full
            nameParts = LinkedHashSet(significant).sortedByDescending { it.length }.map { word(Regex.escape(it)) }
        }

        /** Place fields (address, street, locality): the whole name, the saved phone and phone-like numbers. */
        fun place(s: String?): String? {
            if (s.isNullOrEmpty()) return s
            var out = generic(s)
            for (p in fullName) out = p.replace(out, CONTACT)
            return out
        }

        /** Free text (label, checklist keys, listing URL, notes): as [place], plus every name part of 3+ letters. */
        fun freeText(s: String?): String? {
            if (s.isNullOrEmpty()) return s
            var out = place(s)!!
            for (p in nameParts) out = p.replace(out, CONTACT)
            return out
        }

        /** The rules that need no name: the saved phone, phone-like numbers, then email addresses (before the name parts). */
        private fun generic(s: String): String {
            val out = savedPhone?.replace(s, PHONE) ?: s
            return EMAIL_LIKE.replace(PHONE_LIKE.replace(out, PHONE), EMAIL)
        }

        private companion object {
            const val MIN_SAVED_PHONE_DIGITS = 8
            const val SEP = "[^\\p{L}\\p{M}\\p{N}]+"
            const val SEP_OPTIONAL = "[^\\p{L}\\p{M}\\p{N}]*"

            fun word(regex: String) = Regex("(?<!$WORD)(?:$regex)(?!$WORD)", RegexOption.IGNORE_CASE)

            fun joinParts(parts: List<String>) = parts.joinToString(SEP) { Regex.escape(it) }

            fun joinWithInitials(tokens: List<String>): String {
                val sb = StringBuilder()
                var previousWasInitial = false
                for (t in tokens) {
                    val initial = codePoints(t) < 3
                    if (sb.isNotEmpty()) sb.append(if (previousWasInitial && initial) SEP_OPTIONAL else SEP)
                    if (initial) {
                        splitCodePoints(t).forEachIndexed { i, letter ->
                            if (i > 0) sb.append(SEP_OPTIONAL)
                            sb.append(Regex.escape(letter))
                        }
                    } else {
                        sb.append(Regex.escape(t))
                    }
                    previousWasInitial = initial
                }
                return sb.toString()
            }

            fun phonePattern(phone: String?): Regex? {
                if (phone == null) return null
                val digits = phone.filter { it in '0'..'9' }
                if (digits.length < MIN_SAVED_PHONE_DIGITS) return null
                val variants = linkedSetOf(digits)
                if (digits.length > 10) variants += digits.takeLast(10)
                if (digits.startsWith("0") && digits.length > MIN_SAVED_PHONE_DIGITS) variants += digits.substring(1)
                val alternatives = variants.map { v -> v.toList().joinToString("[ .()\\-]{0,2}") }
                return Regex("(?<!\\d)\\+?(?:${alternatives.joinToString("|")})(?!\\d)")
            }
        }
    }
}

/** The number of Unicode code points in [s] (a surrogate pair counts once), as Java's `codePointCount`. */
internal fun codePoints(s: String): Int = splitCodePoints(s).size

/** [s] split into code points, each as a string. */
internal fun splitCodePoints(s: String): List<String> {
    val out = mutableListOf<String>()
    var i = 0
    while (i < s.length) {
        val end = if (s[i].isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) i + 2 else i + 1
        out += s.substring(i, end)
        i = end
    }
    return out
}
