package app.doorprints.shared.export

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The HTML copy must be written as it is built, not assembled in memory first (S4-02, review of Sprint 4a).
 *
 * The bug this pins: with every photo embedded as a base64 `data:` URI, a shortlist of 150 photos is tens of
 * megabytes, and a `StringBuilder` plus its `toString()` copy plus the UTF-8 bytes is an `OutOfMemoryError` in a
 * WorkManager job — for the default export format, and again for the copy that travels inside every JSON backup.
 * There is no portable way to assert on peak allocation here, so the test asserts the property that makes the
 * allocation bounded on Android: each photo reaches the destination before the next one is asked for, so when the
 * destination is an `OutputStreamWriter` over the user's file, nothing but one photo is ever resident.
 */
class HtmlStreamTest {

    /** Many photos on one house, so "has the earlier output already been handed over?" has something to measure. */
    private val manyPhotos = (1..20).map {
        ExportPhoto(id = "p$it", houseId = "h1", fileName = "p$it.jpg", createdAt = 1_790_004_000_000 + it)
    }

    private val bundle = ExportBundle.build(
        ExportFixture.options(), ExportFixture.houses, ExportFixture.visits, manyPhotos,
    )

    @Test
    fun streamingAndStringProduceTheSameDocument() {
        val streamed = StringBuilder()
        HtmlWriter.write(streamed, bundle, ExportFixture.fakePhotoSrc)
        assertEquals(HtmlWriter.write(bundle, ExportFixture.fakePhotoSrc), streamed.toString())
    }

    @Test
    fun eachPhotoReachesTheDestinationBeforeTheNextOneIsAskedFor() {
        val sink = StringBuilder()
        val lengthWhenAsked = mutableListOf<Int>()
        val uriLength = "data:image/jpeg;base64,".length + 1000
        HtmlWriter.write(sink, bundle) { photo ->
            lengthWhenAsked += sink.length
            "data:image/jpeg;base64," + "A".repeat(1000 - photo.id.length) + photo.id
        }

        assertEquals(manyPhotos.size, lengthWhenAsked.size, "every photo should have been asked for once")
        assertTrue(lengthWhenAsked.first() > 0, "the document before the first photo must already be written out")
        for (i in 1 until lengthWhenAsked.size) {
            assertTrue(
                lengthWhenAsked[i] - lengthWhenAsked[i - 1] >= uriLength,
                "photo $i was asked for before photo ${i - 1} reached the destination",
            )
        }
        assertTrue(sink.length > lengthWhenAsked.last(), "the tail of the document must be written too")
    }
}
