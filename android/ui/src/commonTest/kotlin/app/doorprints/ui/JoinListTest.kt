package app.doorprints.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Android review, round 12: the list join used to stop at three items, silently dropping a fourth. It also builds the
 * Export screen's partial-backup note, whose whole job is to say what a backup leaves out, so the next `BackupGap` must
 * appear in the note rather than vanish. The English patterns are used here; every language has the same three
 * strings. Common since CMP-6 (was `:app`'s, on `ImportWorker.joinList`).
 */
class JoinListTest {

    private fun join(vararg items: String) = joinList(
        items.toList(),
        two = { a, b -> "$a and $b" },
        three = { a, b, c -> "$a, $b and $c" },
        middle = { a, b -> "$a, $b" },
    )

    @Test
    fun everyItemIsKeptWhateverTheCount() {
        assertEquals("", join())
        assertEquals("a", join("a"))
        assertEquals("a and b", join("a", "b"))
        assertEquals("a, b and c", join("a", "b", "c"))
        assertEquals("a, b, c and d", join("a", "b", "c", "d"))
        assertEquals("a, b, c, d and e", join("a", "b", "c", "d", "e"))
    }
}
