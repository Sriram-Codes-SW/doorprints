package app.doorprints.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Photos for an export: shrunk to 1024 px and re-encoded as JPEG q70 (docs/11 section 5.2), which is small enough
 * to embed a whole shortlist in one HTML file and still readable on a laptop screen.
 *
 * Decoding happens with `inSampleSize` so a 12-megapixel photo never becomes a 48 MB bitmap in memory; the exact
 * size is then reached with one `createScaledBitmap`. Every bitmap is recycled straight after use, because an
 * export of 200 photos runs in a background worker with the same heap as the app.
 */
object PhotoBytes {

    const val MAX_EDGE = 1024
    const val QUALITY = 70

    /** Full-size photos for the backup ZIP are copied byte for byte; only the readable copies are shrunk. */
    fun shrunkJpeg(file: File, maxEdge: Int = MAX_EDGE, quality: Int = QUALITY): ByteArray? {
        if (!file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        val decoded = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null

        val longest = maxOf(decoded.width, decoded.height)
        val scaled = if (longest <= maxEdge) {
            decoded
        } else {
            val ratio = maxEdge.toDouble() / longest
            val target = Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * ratio).toInt().coerceAtLeast(1),
                (decoded.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
            if (target !== decoded) decoded.recycle()
            target
        }
        return try {
            ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
                out.toByteArray()
            }
        } finally {
            scaled.recycle()
        }
    }

    /** A decoded bitmap for the PDF, at most [maxEdge] on its longest side. The caller recycles it. */
    fun bitmap(file: File, maxEdge: Int): Bitmap? {
        val bytes = shrunkJpeg(file, maxEdge) ?: return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    /** `data:image/jpeg;base64,…` for the self-contained HTML copy. NO_WRAP: line breaks would break the URI. */
    fun dataUri(file: File): String? {
        val bytes = shrunkJpeg(file) ?: return null
        return "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
