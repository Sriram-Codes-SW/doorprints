package app.doorprints.server.photo;

import java.io.ByteArrayOutputStream;
import java.util.Set;

/**
 * Server-side checks for uploaded photos (threat model F-07, OSI L6 privacy):
 * <ol>
 *   <li>The type is detected from magic bytes (JPEG, PNG, WebP); the client's Content-Type is not trusted.</li>
 *   <li>Metadata that can hold location or identity is removed without re-encoding the pixels: JPEG APP1 (Exif incl.
 *       GPS, XMP), APP3-APP13, APP15 and COM segments and anything after EOI (motion-photo payloads); PNG
 *       {@code eXIf/tEXt/zTXt/iTXt/tIME} chunks and anything after IEND; WebP {@code EXIF} and {@code XMP } chunks.
 *       JPEG APP0 (JFIF), APP2 (ICC colour profile) and APP14 (Adobe colour transform) are kept.</li>
 * </ol>
 * Both clients already re-encode photos (which drops Exif), so this is defence in depth against other clients. The
 * Exif orientation tag goes too; clients rotate the pixels before upload.
 */
public final class ImageSanitizer {

    public record Result(String contentType, byte[] data) {
    }

    private static final Set<String> PNG_DROP = Set.of("eXIf", "tEXt", "zTXt", "iTXt", "tIME");

    private ImageSanitizer() {
    }

    /** @throws IllegalArgumentException if the bytes are not a well-formed JPEG, PNG or WebP */
    public static Result sanitize(byte[] in) {
        if (in == null || in.length < 12) throw invalid();
        if (u8(in, 0) == 0xFF && u8(in, 1) == 0xD8 && u8(in, 2) == 0xFF) return new Result("image/jpeg", jpeg(in));
        if (u8(in, 0) == 0x89 && in[1] == 'P' && in[2] == 'N' && in[3] == 'G'
                && in[4] == 0x0D && in[5] == 0x0A && in[6] == 0x1A && in[7] == 0x0A) {
            return new Result("image/png", png(in));
        }
        if (ascii(in, 0, "RIFF") && ascii(in, 8, "WEBP")) return new Result("image/webp", webp(in));
        throw invalid();
    }

    static byte[] jpeg(byte[] in) {
        var out = new ByteArrayOutputStream(in.length);
        out.write(0xFF);
        out.write(0xD8);
        int i = 2;
        while (i + 1 < in.length) {
            if (u8(in, i) != 0xFF) throw invalid();
            int marker = u8(in, i + 1);
            if (marker == 0xFF) { // fill byte
                i++;
                continue;
            }
            if (marker == 0xD9) { // EOI without image data
                out.write(0xFF);
                out.write(0xD9);
                return out.toByteArray();
            }
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) { // standalone markers
                out.write(in, i, 2);
                i += 2;
                continue;
            }
            if (i + 3 >= in.length) throw invalid();
            int len = (u8(in, i + 2) << 8) | u8(in, i + 3);
            if (len < 2 || i + 2 + len > in.length) throw invalid();
            if (marker == 0xDA) { // SOS: copy the image data up to and including the first EOI, drop any trailer
                int end = indexOfEoi(in, i + 2 + len);
                if (end < 0) throw invalid();
                out.write(in, i, end - i);
                return out.toByteArray();
            }
            boolean drop = marker == 0xFE || (marker >= 0xE1 && marker <= 0xEF && marker != 0xE2 && marker != 0xEE);
            if (!drop) out.write(in, i, 2 + len);
            i += 2 + len;
        }
        throw invalid();
    }

    /** Index just past the first FF D9 at or after {@code from}. Entropy-coded data never contains FF D9. */
    private static int indexOfEoi(byte[] in, int from) {
        for (int j = from; j + 1 < in.length; j++) {
            if (u8(in, j) == 0xFF && u8(in, j + 1) == 0xD9) return j + 2;
        }
        return -1;
    }

    static byte[] png(byte[] in) {
        var out = new ByteArrayOutputStream(in.length);
        out.write(in, 0, 8);
        int i = 8;
        while (i + 12 <= in.length) {
            long len = be32(in, i);
            if (len < 0 || len > in.length - i - 12L) throw invalid();
            var type = new String(in, i + 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            int total = (int) len + 12;
            if (!PNG_DROP.contains(type)) out.write(in, i, total);
            i += total;
            if (type.equals("IEND")) return out.toByteArray();
        }
        throw invalid();
    }

    static byte[] webp(byte[] in) {
        long riffSize = le32(in, 4);
        if (riffSize < 4 || riffSize + 8 > in.length) throw invalid();
        int end = (int) riffSize + 8;
        var body = new ByteArrayOutputStream(in.length);
        int i = 12;
        while (i + 8 <= end) {
            var fourcc = new String(in, i, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            long size = le32(in, i + 4);
            long padded = size + (size & 1);
            if (i + 8 + padded > end) throw invalid();
            int total = (int) (8 + padded);
            if (fourcc.equals("EXIF") || fourcc.equals("XMP ")) {
                i += total;
                continue;
            }
            int start = body.size();
            body.write(in, i, total);
            if (fourcc.equals("VP8X") && size >= 1) {
                // Clear the "has EXIF" (0x08) and "has XMP" (0x04) flags now that the chunks are gone.
                var bytes = body.toByteArray();
                bytes[start + 8] = (byte) (bytes[start + 8] & ~0x0C);
                body.reset();
                body.write(bytes, 0, bytes.length);
            }
            i += total;
        }
        if (i != end) throw invalid();
        var chunks = body.toByteArray();
        var out = new ByteArrayOutputStream(chunks.length + 12);
        out.write(in, 0, 4); // RIFF
        long newSize = 4L + chunks.length;
        out.write((int) (newSize & 0xFF));
        out.write((int) ((newSize >> 8) & 0xFF));
        out.write((int) ((newSize >> 16) & 0xFF));
        out.write((int) ((newSize >> 24) & 0xFF));
        out.write(in, 8, 4); // WEBP
        out.write(chunks, 0, chunks.length);
        return out.toByteArray();
    }

    private static int u8(byte[] b, int i) {
        return b[i] & 0xFF;
    }

    private static long be32(byte[] b, int i) {
        return ((long) u8(b, i) << 24) | ((long) u8(b, i + 1) << 16) | ((long) u8(b, i + 2) << 8) | u8(b, i + 3);
    }

    private static long le32(byte[] b, int i) {
        return ((long) u8(b, i + 3) << 24) | ((long) u8(b, i + 2) << 16) | ((long) u8(b, i + 1) << 8) | u8(b, i);
    }

    private static boolean ascii(byte[] b, int at, String s) {
        if (b.length < at + s.length()) return false;
        for (int k = 0; k < s.length(); k++) if (b[at + k] != s.charAt(k)) return false;
        return true;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Only well-formed JPEG, PNG or WebP images are allowed");
    }
}
