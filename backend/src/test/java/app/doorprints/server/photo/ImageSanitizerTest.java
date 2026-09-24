package app.doorprints.server.photo;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** F-07 magic-byte detection and OSI L6 metadata (GPS) stripping. */
public class ImageSanitizerTest {

    /** SOI, APP0 JFIF, APP1 Exif with a fake GPS string, COM, DQT, SOS + data, EOI, then a trailer. */
    public static byte[] jpegWithExif() {
        var out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xD8});
        segment(out, 0xE0, "JFIF\0\1\1\0\0\1\0\1\0\0".getBytes(StandardCharsets.ISO_8859_1));
        segment(out, 0xE1, "Exif\0\0GPSLatitude=12.9716".getBytes(StandardCharsets.ISO_8859_1));
        segment(out, 0xE2, "ICC_PROFILE\0".getBytes(StandardCharsets.ISO_8859_1));
        segment(out, 0xFE, "secret comment".getBytes(StandardCharsets.ISO_8859_1));
        segment(out, 0xDB, new byte[65]);
        segment(out, 0xDA, new byte[10]);
        out.writeBytes(new byte[]{0x12, 0x34, (byte) 0xFF, 0x00, 0x56}); // entropy data with a stuffed FF
        out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xD9});
        out.writeBytes("MotionPhoto GPS trailer".getBytes(StandardCharsets.ISO_8859_1));
        return out.toByteArray();
    }

    private static void segment(ByteArrayOutputStream out, int marker, byte[] payload) {
        int len = payload.length + 2;
        out.writeBytes(new byte[]{(byte) 0xFF, (byte) marker, (byte) (len >> 8), (byte) len});
        out.writeBytes(payload);
    }

    @Test
    void stripsJpegExifCommentsAndTrailerButKeepsImageData() {
        var in = jpegWithExif();
        var result = ImageSanitizer.sanitize(in);
        assertThat(result.contentType()).isEqualTo("image/jpeg");
        var text = new String(result.data(), StandardCharsets.ISO_8859_1);
        assertThat(text).doesNotContain("GPSLatitude").doesNotContain("secret comment").doesNotContain("trailer");
        assertThat(text).contains("JFIF").contains("ICC_PROFILE");
        assertThat(result.data()[result.data().length - 2] & 0xFF).isEqualTo(0xFF);
        assertThat(result.data()[result.data().length - 1] & 0xFF).isEqualTo(0xD9);
        // Sanitising twice changes nothing.
        assertThat(ImageSanitizer.sanitize(result.data()).data()).isEqualTo(result.data());
    }

    static byte[] pngWithText() {
        var out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        chunk(out, "IHDR", new byte[13]);
        chunk(out, "tEXt", "Location\0Home".getBytes(StandardCharsets.ISO_8859_1));
        chunk(out, "eXIf", "GPS".getBytes(StandardCharsets.ISO_8859_1));
        chunk(out, "IDAT", new byte[]{1, 2, 3});
        chunk(out, "IEND", new byte[0]);
        out.writeBytes("junk".getBytes(StandardCharsets.ISO_8859_1));
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        int n = data.length;
        out.writeBytes(new byte[]{(byte) (n >> 24), (byte) (n >> 16), (byte) (n >> 8), (byte) n});
        var typeBytes = type.getBytes(StandardCharsets.ISO_8859_1);
        out.writeBytes(typeBytes);
        out.writeBytes(data);
        var crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        long c = crc.getValue();
        out.writeBytes(new byte[]{(byte) (c >> 24), (byte) (c >> 16), (byte) (c >> 8), (byte) c});
    }

    @Test
    void stripsPngTextAndExifChunks() {
        var result = ImageSanitizer.sanitize(pngWithText());
        assertThat(result.contentType()).isEqualTo("image/png");
        var text = new String(result.data(), StandardCharsets.ISO_8859_1);
        assertThat(text).contains("IHDR").contains("IDAT");
        assertThat(text.indexOf("IEND")).isEqualTo(text.length() - 8); // IEND type + CRC are the last bytes
        assertThat(text).doesNotContain("Location").doesNotContain("eXIf").doesNotContain("junk");
    }

    @Test
    void stripsWebpExifAndClearsFlags() {
        var chunks = new ByteArrayOutputStream();
        webpChunk(chunks, "VP8X", new byte[]{0x0C, 0, 0, 0, 0, 0, 0, 0, 0, 0}); // EXIF + XMP flags set
        webpChunk(chunks, "VP8L", new byte[]{1, 2, 3}); // odd size -> padded
        webpChunk(chunks, "EXIF", "GPS-data".getBytes(StandardCharsets.ISO_8859_1));
        webpChunk(chunks, "XMP ", "<x:gps/>".getBytes(StandardCharsets.ISO_8859_1));
        var body = chunks.toByteArray();
        var out = new ByteArrayOutputStream();
        out.writeBytes("RIFF".getBytes(StandardCharsets.ISO_8859_1));
        int size = body.length + 4;
        out.writeBytes(new byte[]{(byte) size, (byte) (size >> 8), (byte) (size >> 16), (byte) (size >> 24)});
        out.writeBytes("WEBP".getBytes(StandardCharsets.ISO_8859_1));
        out.writeBytes(body);

        var result = ImageSanitizer.sanitize(out.toByteArray());
        assertThat(result.contentType()).isEqualTo("image/webp");
        var data = result.data();
        var text = new String(data, StandardCharsets.ISO_8859_1);
        assertThat(text).doesNotContain("GPS").doesNotContain("EXIF").contains("VP8L");
        assertThat(data[20] & 0x0C).isZero(); // VP8X flags byte: 12 (RIFF hdr) + 8 (chunk hdr)
        int riff = (data[4] & 0xFF) | (data[5] & 0xFF) << 8 | (data[6] & 0xFF) << 16 | (data[7] & 0xFF) << 24;
        assertThat(riff).isEqualTo(data.length - 8);
    }

    private static void webpChunk(ByteArrayOutputStream out, String fourcc, byte[] data) {
        out.writeBytes(fourcc.getBytes(StandardCharsets.ISO_8859_1));
        int n = data.length;
        out.writeBytes(new byte[]{(byte) n, (byte) (n >> 8), (byte) (n >> 16), (byte) (n >> 24)});
        out.writeBytes(data);
        if ((n & 1) == 1) out.write(0);
    }

    @Test
    void rejectsNonImagesAndTruncatedFiles() {
        assertThatThrownBy(() -> ImageSanitizer.sanitize("<html><script>alert(1)</script></html>".getBytes()))
                .isInstanceOf(IllegalArgumentException.class);
        var jpeg = jpegWithExif();
        var truncated = java.util.Arrays.copyOf(jpeg, 30);
        assertThatThrownBy(() -> ImageSanitizer.sanitize(truncated)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ImageSanitizer.sanitize(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
