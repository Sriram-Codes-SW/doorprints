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

package app.doorprints.shared.listing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every case of `docs/schemas/listing-fixtures.json` reads the same here as in the web's `listing-text.spec.ts`
 * (docs/11 5.29): a share text fills the same fields on Android and on the web.
 */
class ListingFixturesTest {
    private fun repoFile(relative: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.exists()) return candidate
            dir = dir.parentFile
        }
        error("$relative not found above ${File("").absolutePath}")
    }

    @Test
    fun everyFixtureCaseReadsAsExpected() {
        val root = Json.parseToJsonElement(repoFile("docs/schemas/listing-fixtures.json").readText()).jsonObject
        assertEquals("doorprints-listing-fixtures/1", root.getValue("format").jsonPrimitive.content)
        val cases = root.getValue("cases").jsonArray
        assertTrue("at least one case per portal", cases.size >= ListingText.PORTALS.size)
        for (case in cases) {
            val c = case.jsonObject
            val name = c.getValue("name").jsonPrimitive.content
            val text = c.getValue("text").jsonPrimitive.content
            val e = c.getValue("expect").jsonObject
            fun str(key: String): String? = e[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
            fun long(key: String): Long? = e[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.toLong()
            val d = ListingText.parse(text)
            assertEquals("$name: label", str("label"), d.label)
            assertEquals("$name: price", long("price"), d.price)
            assertEquals("$name: priceType", str("priceType"), d.priceType)
            assertEquals("$name: bedrooms", long("bedrooms")?.toInt(), d.bedrooms)
            assertEquals("$name: locality", str("locality"), d.locality)
            assertEquals("$name: contactPhone", str("contactPhone"), d.contactPhone)
            assertEquals("$name: listingUrl", str("listingUrl"), d.listingUrl)
            assertEquals("$name: portal", str("portal"), ListingText.portal(d.listingUrl))
            str("notesStart")?.let { assertTrue("$name: notes start with $it", d.notes!!.startsWith(it)) }
            // Slice 1a: a case whose notes begin with "<n> sq ft" fills the carpet area with that n; the others none.
            val area = str("notesStart")?.let { Regex("^(\\d+) sq ft").find(it)?.groupValues?.get(1)?.toInt() }
            assertEquals("$name: areaSqft", area, d.areaSqft)
            assertTrue("$name: the whole text is in the notes", d.notes!!.contains(text.trim().take(40)))
        }
    }
}
