package app.doorprints.shared.export

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `data.json` has to come back exactly as it went in — that is the whole promise of the JSON backup. */
class BackupTest {

    private val bundle = ExportFixture.bundle()

    @Test
    fun dataJsonRoundTripsEveryField() {
        val data = BackupData.of(bundle)
        val text = BackupFormat.json.encodeToString(BackupData.serializer(), data)
        val back = BackupFormat.json.decodeFromString(BackupData.serializer(), text)
        assertEquals(data, back)
        assertEquals(bundle.houses, back.houses)
        assertEquals(bundle.visits, back.visits)
        assertEquals(bundle.photos, back.photos)
    }

    @Test
    fun theSameDataAlwaysProducesTheSameBytes() {
        val once = BackupFormat.json.encodeToString(BackupData.serializer(), BackupData.of(bundle))
        val twice = BackupFormat.json.encodeToString(BackupData.serializer(), BackupData.of(ExportFixture.bundle()))
        assertEquals(once, twice)
        assertFalse(once.contains('\n'), "no pretty-printing: the bytes must be stable and small")
        assertTrue(once.startsWith("{\"format\":\"doorprints-backup/1\""))
    }

    @Test
    fun nullsAreLeftOutRatherThanWrittenAsNull() {
        val text = BackupFormat.json.encodeToString(BackupData.serializer(), BackupData.of(bundle))
        assertFalse(text.contains(":null"), text)
        // ...and the round trip still restores them as null.
        val back = BackupFormat.json.decodeFromString(BackupData.serializer(), text)
        assertNull(back.houses.first { it.id == "h2" }.address)
    }

    @Test
    fun anUnknownFieldFromANewerAppDoesNotBreakTheImport() {
        val text = "{\"format\":\"doorprints-backup/1\",\"exportedAt\":1,\"somethingNew\":42,\"houses\":[]}"
        val back = BackupFormat.json.decodeFromString(BackupData.serializer(), text)
        assertEquals(1L, back.exportedAt)
        assertTrue(back.houses.isEmpty())
    }

    @Test
    fun manifestAndDataAreRejectedWhenTheFormatIsNotOurs() {
        val counts = BackupCounts(1, 0, 0)
        assertEquals(
            BackupProblem.UNSUPPORTED_VERSION,
            BackupValidation.checkManifest(
                BackupManifest(format = "doorprints-backup/2", createdAt = "2026-09-22T10:15:30Z", counts = counts)
            ),
        )
        assertEquals(
            BackupProblem.UNSUPPORTED_VERSION,
            BackupValidation.checkData(BackupData(format = "other", exportedAt = 0)),
        )
        assertNull(
            BackupValidation.checkManifest(BackupManifest(createdAt = "2026-09-22T10:15:30Z", counts = counts))
        )
        assertNull(BackupValidation.checkData(BackupData.of(bundle)))
    }

    @Test
    fun duplicateOrBlankIdsAreBrokenData() {
        val twice = BackupData(exportedAt = 0, houses = listOf(ExportFixture.house1, ExportFixture.house1))
        assertEquals(BackupProblem.BROKEN_DATA, BackupValidation.checkData(twice))
        val blank = BackupData(exportedAt = 0, houses = listOf(ExportFixture.house1.copy(id = " ")))
        assertEquals(BackupProblem.BROKEN_DATA, BackupValidation.checkData(blank))
        val orphanPhoto = BackupData(exportedAt = 0, photos = listOf(ExportPhoto("p", "", "p.jpg", 0)))
        assertEquals(BackupProblem.BROKEN_DATA, BackupValidation.checkData(orphanPhoto))
    }

    /**
     * The other half of the zip-slip guard: an importer turns a row id into a file name
     * (`filesDir/photos/<photo id>.jpg`), so an id is attacker input in a hand-edited backup just as much as an
     * entry name is.
     */
    @Test
    fun anIdThatWouldEscapeThePhotoDirectoryIsBrokenData() {
        val longId = "a".repeat(200)
        for (bad in listOf("../x", "a/b", "..", "photos/../x", "a\\b", "a b", "a.jpg", "", " ", longId)) {
            assertEquals(
                BackupProblem.BROKEN_DATA,
                BackupValidation.checkData(
                    BackupData(exportedAt = 0, houses = listOf(ExportFixture.house1.copy(id = bad)))
                ),
                "house id should be refused: '$bad'",
            )
            assertEquals(
                BackupProblem.BROKEN_DATA,
                BackupValidation.checkData(
                    BackupData(exportedAt = 0, photos = listOf(ExportPhoto(bad, "h1", "p.jpg", 0)))
                ),
                "photo id should be refused: '$bad'",
            )
            assertEquals(
                BackupProblem.BROKEN_DATA,
                BackupValidation.checkData(
                    BackupData(exportedAt = 0, photos = listOf(ExportPhoto("p1", bad, "p.jpg", 0)))
                ),
                "photo houseId should be refused: '$bad'",
            )
            assertEquals(
                BackupProblem.BROKEN_DATA,
                BackupValidation.checkData(
                    BackupData(exportedAt = 0, visits = listOf(ExportFixture.visit1.copy(id = bad)))
                ),
                "visit id should be refused: '$bad'",
            )
            assertEquals(
                BackupProblem.BROKEN_DATA,
                BackupValidation.checkData(
                    BackupData(exportedAt = 0, visits = listOf(ExportFixture.visit1.copy(houseId = bad)))
                ),
                "visit houseId should be refused: '$bad'",
            )
        }
        // A UUID, which is what both apps actually mint, and the short ids the fixtures use.
        for (good in listOf("h1", "c0a80101-7f3e-4a2b-9c1d-000000000001", "A_b-9", "a".repeat(64))) {
            assertTrue(BackupValidation.isValidId(good), "should be allowed: '$good'")
        }
        assertNull(BackupValidation.checkData(BackupData.of(bundle)))
    }

    /**
     * docs/schemas/README.md section 5 (ticket S4-00/a): `data.json` groups visits and photos by house, in house
     * order, while the bundle — and so the CSV/XLSX tables — stays sorted globally. The fixture interleaves on
     * purpose, like the canonical sample: house B's visit arrives between house A's two, and house B's photo is
     * older than house A's, so the two rules give different orders and the test cannot pass by accident.
     */
    @Test
    fun dataJsonGroupsVisitsAndPhotosByHouse() {
        val a = ExportFixture.house1.copy(id = "hA", createdAt = 100)
        val b = ExportFixture.house2.copy(id = "hB", createdAt = 200, status = "NEW")
        fun visit(id: String, house: String, at: Long) =
            ExportFixture.visit1.copy(id = id, houseId = house, arrivedAt = at)
        fun photo(id: String, house: String, at: Long) = ExportPhoto(id, house, "$id.jpg", at)
        val built = ExportBundle.build(
            ExportFixture.options(),
            houses = listOf(b, a),
            visits = listOf(visit("vA2", "hA", 3_000), visit("vB1", "hB", 2_000), visit("vA1", "hA", 1_000)),
            photos = listOf(photo("pA1", "hA", 5_000), photo("pB1", "hB", 4_000)),
        )
        // The bundle is globally sorted...
        assertEquals(listOf("vA1", "vB1", "vA2"), built.visits.map { it.id })
        assertEquals(listOf("pB1", "pA1"), built.photos.map { it.id })
        // ...data.json is grouped by house, in house order.
        val data = BackupData.of(built)
        assertEquals(listOf("hA", "hB"), data.houses.map { it.id })
        assertEquals(listOf("vA1", "vA2", "vB1"), data.visits.map { it.id })
        assertEquals(listOf("pA1", "pB1"), data.photos.map { it.id })
        assertNotEquals(built.visits.map { it.id }, data.visits.map { it.id }, "the fixture must interleave")
    }

    /** A hand-built bundle can hold a row whose house is not in it; it goes last rather than being dropped. */
    @Test
    fun aRowWhoseHouseIsNotInTheCopyComesLast() {
        val orphanVisit = ExportFixture.visit1.copy(id = "v0", houseId = null, arrivedAt = 1)
        val orphanPhoto = ExportPhoto("p0", "gone", "p0.jpg", 1)
        val bundle = ExportBundle(
            ExportFixture.options(),
            houses = listOf(ExportFixture.house1),
            visits = listOf(orphanVisit, ExportFixture.visit1),
            photos = listOf(orphanPhoto, ExportFixture.photo1),
        )
        val data = BackupData.of(bundle)
        assertEquals(listOf("v1", "v0"), data.visits.map { it.id })
        assertEquals(listOf("p1", "p0"), data.photos.map { it.id })
    }

    /** Section 5: `checklist` keys alphabetically in `data.json`; the fixture's are deliberately not. */
    @Test
    fun checklistKeysAreWrittenSorted() {
        assertEquals(listOf("water", "noise", "parking"), ExportFixture.house1.checklist.keys.toList())
        val text = BackupFormat.json.encodeToString(BackupData.serializer(), BackupData.of(bundle))
        assertTrue(text.contains("\"checklist\":{\"noise\":2,\"parking\":4,\"water\":5}"), text)
    }

    /**
     * Section 4.4: a field the format always writes is refused when a file leaves it out, instead of being read as
     * the Kotlin default — as the server refuses it (`BackupService`: "status is required", "source is required",
     * "format was missing").
     */
    @Test
    fun alwaysPresentFieldsWithAKotlinDefaultAreStillRequiredInAFile() {
        val house = "{\"id\":\"h1\",\"label\":\"\",\"lat\":1.5,\"lon\":2.5,\"checklist\":{}," +
            "\"createdAt\":1,\"updatedAt\":1}"
        val visit = "{\"id\":\"v1\",\"lat\":1.5,\"lon\":2.5,\"arrivedAt\":1,\"updatedAt\":1}"
        fun decode(text: String) = BackupFormat.json.decodeFromString(BackupData.serializer(), text)

        assertFailsWith<Exception>("no status") {
            decode("{\"format\":\"doorprints-backup/1\",\"exportedAt\":1,\"houses\":[$house]}")
        }
        assertFailsWith<Exception>("no source") {
            decode("{\"format\":\"doorprints-backup/1\",\"exportedAt\":1,\"visits\":[$visit]}")
        }
        assertFailsWith<Exception>("no format") { decode("{\"exportedAt\":1}") }
        assertFailsWith<Exception>("no manifest format") {
            BackupFormat.json.decodeFromString(
                BackupManifest.serializer(),
                "{\"createdAt\":\"2026-09-22T10:15:30Z\",\"counts\":{\"houses\":0,\"visits\":0,\"photos\":0}}",
            )
        }
        assertFailsWith<Exception>("status null") {
            decode(
                "{\"format\":\"doorprints-backup/1\",\"exportedAt\":1,\"houses\":[" +
                    house.replace("\"checklist\"", "\"status\":null,\"checklist\"") + "]}"
            )
        }
        assertFailsWith<Exception>("lat missing") {
            decode(
                "{\"format\":\"doorprints-backup/1\",\"exportedAt\":1,\"houses\":[" +
                    house.replace("\"lat\":1.5,", "\"status\":\"NEW\",") + "]}"
            )
        }
        // With the fields present, the same rows read fine.
        val ok = decode(
            "{\"format\":\"doorprints-backup/1\",\"exportedAt\":1,\"houses\":[" +
                house.replace("\"checklist\"", "\"status\":\"NEW\",\"checklist\"") + "],\"visits\":[" +
                visit.replace("\"updatedAt\"", "\"source\":\"AUTO\",\"updatedAt\"") + "]}"
        )
        assertEquals("NEW", ok.houses.single().status)
        assertEquals("AUTO", ok.visits.single().source)
    }

    /**
     * The one lenient always-present field (docs/schemas/README.md sections 3.1 and 4.4, S4-00/g, decided: option
     * (b)): an absent **or `null`** `checklist` reads as `{}`, "no scores", as on the server. Pinned on both JSON
     * decoders, because they are different code: a backup ZIP's `data.json` goes through the streaming decoder
     * (`decodeFromString`), a bare `data.json` through the tree decoder (`parseToJsonElement`, then
     * `decodeFromJsonElement`, see `BackupReader`). The leniency must stay on this one property: a `null` `status`
     * is still refused on both, which is what `coerceInputValues` would have broken.
     */
    @Test
    fun checklistAbsentOrNullReadsAsNoScores() {
        val row = "{\"id\":\"h1\",\"label\":\"\",\"lat\":1.5,\"lon\":2.5,\"status\":\"NEW\"," +
            "\"createdAt\":1,\"updatedAt\":1"
        fun document(house: String) = "{\"format\":\"doorprints-backup/1\",\"exportedAt\":1,\"houses\":[$house]}"
        val decoders: List<Pair<String, (String) -> BackupData>> = listOf(
            "streaming" to { house: String ->
                BackupFormat.json.decodeFromString(BackupData.serializer(), document(house))
            },
            "tree" to { house: String ->
                BackupFormat.json.decodeFromJsonElement(
                    BackupData.serializer(),
                    BackupFormat.json.parseToJsonElement(document(house)),
                )
            },
        )
        for ((name, decode) in decoders) {
            assertEquals(emptyMap(), decode("$row}").houses.single().checklist, "$name: absent")
            assertEquals(emptyMap(), decode("$row,\"checklist\":null}").houses.single().checklist, "$name: null")
            assertEquals(emptyMap(), decode("$row,\"checklist\":{}}").houses.single().checklist, "$name: {}")
            assertEquals(
                mapOf("water" to 3, "noise" to 1),
                decode("$row,\"checklist\":{\"water\":3,\"noise\":1}}").houses.single().checklist,
                "$name: scores",
            )
            assertFailsWith<Exception>("$name: status null must still be refused") {
                decode(row.replace("\"status\":\"NEW\"", "\"status\":null") + ",\"checklist\":null}")
            }
            assertFailsWith<Exception>("$name: a checklist that is not an object is still broken") {
                decode("$row,\"checklist\":[1]}")
            }
        }
        // Writing is unchanged: an empty checklist is still written as {}, never left out and never null.
        val written = BackupFormat.json.encodeToString(
            BackupData.serializer(),
            BackupData(exportedAt = 1, houses = listOf(ExportFixture.house2)),
        )
        assertTrue(written.contains("\"checklist\":{}"), written)
    }

    /**
     * Visits that belong to no house (Hunt mode records a dwell at a place that is not a house yet with
     * `houseId = null`) must survive a backup: the whole promise of `data.json` is that a restore loses nothing.
     * They go last, sorted like every other visit (docs/schemas/README.md section 5), and only into `data.json` —
     * the bundle's own lists, which the tables are built from, do not change.
     */
    @Test
    fun visitsWithoutAHouseAreInTheBackupAndComeLast() {
        fun unlinked(id: String, at: Long) =
            ExportFixture.visit1.copy(id = id, houseId = null, arrivedAt = at, leftAt = null, source = "AUTO")
        val early = unlinked("u1", 1_000)
        val late = unlinked("u2", 1_790_005_000_000)
        val built = ExportBundle.build(
            ExportFixture.options(),
            ExportFixture.houses,
            listOf(late, ExportFixture.visit1, early),
            ExportFixture.photos,
        )
        assertEquals(listOf("u1", "u2"), built.unlinkedVisits.map { it.id })
        assertEquals(listOf("v1"), built.visits.map { it.id }, "the tables stay as they were")

        val data = BackupData.of(built)
        assertEquals(listOf("v1", "u1", "u2"), data.visits.map { it.id })
        assertEquals(BackupCounts(2, 3, 1), BackupCounts.of(data))
        assertNull(BackupValidation.checkData(data))

        val text = BackupFormat.json.encodeToString(BackupData.serializer(), data)
        assertFalse(text.contains(":null"), text)
        val back = BackupFormat.json.decodeFromString(BackupData.serializer(), text)
        assertEquals(data, back)
        assertNull(back.visits.first { it.id == "u1" }.houseId)

        // A shortlist or a hand-picked set is a copy of those houses, not of everything on the phone.
        for (scope in listOf(ExportScope.SHORTLISTED, ExportScope.SELECTED)) {
            val partial = ExportBundle.build(
                ExportFixture.options(scope = scope),
                ExportFixture.houses,
                listOf(late, ExportFixture.visit1, early),
                ExportFixture.photos,
            )
            assertTrue(partial.unlinkedVisits.isEmpty(), "$scope")
        }
    }

    @Test
    fun zipSlipAndOtherNastyPathsAreRefused() {
        for (bad in listOf(
            "/etc/passwd", "../../etc/passwd", "photos/../../x.jpg", "C:\\x.jpg", "photos\\x.jpg", "",
            "./photos/x.jpg",
        )) {
            assertTrue(BackupValidation.isSuspiciousPath(bad), "should be refused: '$bad'")
        }
        for (good in listOf("data.json", "manifest.json", "photos/p1.jpg", "Doorprints-2026-09-22.html")) {
            assertFalse(BackupValidation.isSuspiciousPath(good), "should be allowed: '$good'")
        }
    }

    @Test
    fun onlyASinglePhotosFolderLevelCounts() {
        assertTrue(BackupValidation.isPhotoEntry("photos/p1.jpg"))
        assertFalse(BackupValidation.isPhotoEntry("photos/"))
        assertFalse(BackupValidation.isPhotoEntry("photos/sub/p1.jpg"))
        assertFalse(BackupValidation.isPhotoEntry("other/p1.jpg"))
        assertFalse(BackupValidation.isPhotoEntry("photos/../p1.jpg"))
    }
}
