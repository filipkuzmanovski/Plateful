package mk.ukim.finki.wp.recipeappbackend.generation;

import mk.ukim.finki.wp.recipeappbackend.ai.AiImage;
import mk.ukim.finki.wp.recipeappbackend.exception.InvalidScanPhotoException;
import org.junit.jupiter.api.Test;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PhotoPreparerTest {

    private final PhotoPreparer preparer = new PhotoPreparer();

    // ---- helpers -------------------------------------------------------------------------------

    private static BufferedImage gradient(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, ((x * 255 / w) << 16) | ((y * 255 / h) << 8) | 0x40);
            }
        }
        return img;
    }

    private static BufferedImage noise(int w, int h, long seed) {
        Random random = new Random(seed);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, random.nextInt(0x1000000));
            }
        }
        return img;
    }

    private static byte[] jpeg(BufferedImage img, float quality) throws Exception {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(quality);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(img, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    private static byte[] jpeg(int w, int h) throws Exception {
        return jpeg(gradient(w, h), 0.9f);
    }

    private static BufferedImage decode(byte[] data) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(data));
    }

    private static boolean contains(byte[] haystack, String needle) {
        byte[] n = needle.getBytes(StandardCharsets.ISO_8859_1);
        outer:
        for (int i = 0; i <= haystack.length - n.length; i++) {
            for (int j = 0; j < n.length; j++) {
                if (haystack[i + j] != n[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    // ---- accepted ------------------------------------------------------------------------------

    @Test
    void jpeg_isAcceptedAndScaledToAtMost1536() throws Exception {
        List<AiImage> out = preparer.prepare(List.of(jpeg(3000, 2000)));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).mimeType()).isEqualTo("image/jpeg");
        BufferedImage img = decode(out.get(0).data());
        assertThat(img.getWidth()).isEqualTo(1536);
        assertThat(img.getHeight()).isEqualTo(1024);
    }

    @Test
    void portraitJpeg_isScaledByItsLongSide() throws Exception {
        BufferedImage img = decode(preparer.prepare(List.of(jpeg(2000, 3000))).get(0).data());
        assertThat(img.getWidth()).isEqualTo(1024);
        assertThat(img.getHeight()).isEqualTo(1536);
    }

    @Test
    void smallJpeg_isNotUpscaled() throws Exception {
        BufferedImage img = decode(preparer.prepare(List.of(jpeg(800, 600))).get(0).data());
        assertThat(img.getWidth()).isEqualTo(800);
        assertThat(img.getHeight()).isEqualTo(600);
    }

    @Test
    void threeJpegs_areAllReturnedInOrder() throws Exception {
        List<AiImage> out = preparer.prepare(List.of(jpeg(300, 200), jpeg(400, 200), jpeg(500, 200)));
        assertThat(out).hasSize(3);
        assertThat(decode(out.get(0).data()).getWidth()).isEqualTo(300);
        assertThat(decode(out.get(1).data()).getWidth()).isEqualTo(400);
        assertThat(decode(out.get(2).data()).getWidth()).isEqualTo(500);
    }

    @Test
    void grayscaleJpeg_isAccepted() throws Exception {
        BufferedImage gray = new BufferedImage(200, 100, BufferedImage.TYPE_BYTE_GRAY);
        gray.getGraphics().fillRect(0, 0, 100, 100);
        List<AiImage> out = preparer.prepare(List.of(jpeg(gray, 0.9f)));
        assertThat(decode(out.get(0).data()).getWidth()).isEqualTo(200);
    }

    // ---- refused: type and size ----------------------------------------------------------------

    @Test
    void png_isRefused() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(gradient(50, 50), "png", out);
        assertThatThrownBy(() -> preparer.prepare(List.of(out.toByteArray())))
                .isInstanceOf(InvalidScanPhotoException.class);
    }

    @Test
    void gif_isRefused() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(gradient(50, 50), "gif", out);
        assertThatThrownBy(() -> preparer.prepare(List.of(out.toByteArray())))
                .isInstanceOf(InvalidScanPhotoException.class);
    }

    @Test
    void textNamedJpg_isRefused() {
        byte[] text = "hello".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> preparer.prepare(List.of(text)))
                .isInstanceOf(InvalidScanPhotoException.class);
    }

    @Test
    void emptyFile_isRefused() {
        assertThatThrownBy(() -> preparer.prepare(List.of(new byte[0])))
                .isInstanceOf(InvalidScanPhotoException.class);
    }

    @Test
    void overOneMegabyte_isRefused() {
        byte[] big = new byte[1_048_577];
        big[0] = (byte) 0xFF;
        big[1] = (byte) 0xD8;
        big[2] = (byte) 0xFF;
        assertThatThrownBy(() -> preparer.prepare(List.of(big)))
                .isInstanceOf(InvalidScanPhotoException.class);
    }

    @Test
    void nullEntry_isRefused() {
        List<byte[]> photos = new ArrayList<>();
        photos.add(null);
        assertThatThrownBy(() -> preparer.prepare(photos)).isInstanceOf(InvalidScanPhotoException.class);
    }

    // ---- refused: count ------------------------------------------------------------------------

    @Test
    void noFiles_isRefused() {
        assertThatThrownBy(() -> preparer.prepare(List.of())).isInstanceOf(InvalidScanPhotoException.class);
        assertThatThrownBy(() -> preparer.prepare(null)).isInstanceOf(InvalidScanPhotoException.class);
    }

    @Test
    void fourFiles_isRefused() throws Exception {
        byte[] one = jpeg(50, 50);
        assertThatThrownBy(() -> preparer.prepare(List.of(one, one, one, one)))
                .isInstanceOf(InvalidScanPhotoException.class);
    }

    // ---- refused: dimensions and damage --------------------------------------------------------

    @Test
    void headerOver4096_isRefusedWithoutDecoding() throws Exception {
        byte[] wide = jpeg(5000, 10);
        assertThatThrownBy(() -> preparer.prepare(List.of(wide)))
                .isInstanceOf(InvalidScanPhotoException.class);
    }

    @Test
    void exactly4096_isAccepted() throws Exception {
        byte[] edge = jpeg(4096, 8);
        BufferedImage img = decode(preparer.prepare(List.of(edge)).get(0).data());
        assertThat(img.getWidth()).isEqualTo(1536);
    }

    @Test
    void truncatedJpeg_isRefused() throws Exception {
        byte[] full = jpeg(noise(300, 300, 7), 0.9f);
        byte[] half = Arrays.copyOf(full, full.length / 2);
        assertThatThrownBy(() -> preparer.prepare(List.of(half)))
                .isInstanceOf(InvalidScanPhotoException.class);
    }

    @Test
    void headerOnlyJpeg_isRefused() {
        byte[] header = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 4, 0, 0};
        assertThatThrownBy(() -> preparer.prepare(List.of(header)))
                .isInstanceOf(InvalidScanPhotoException.class);
    }

    @Test
    void cmykJpeg_isInvalidNot500() throws Exception {
        // A 4-band raster written without a colour model produces a CMYK-style JPEG that ImageIO
        // cannot turn into a BufferedImage. Whatever the JDK does with it, we must refuse cleanly.
        Raster raster = Raster.createInterleavedRaster(DataBuffer.TYPE_BYTE, 64, 64, 4, null);
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(raster, null, null), writer.getDefaultWriteParam());
        } finally {
            writer.dispose();
        }
        byte[] plain4 = out.toByteArray();
        assertThat(plain4[0]).isEqualTo((byte) 0xFF);

        // What cameras and scanners really write: the same 4 components plus an Adobe APP14 segment
        // (transform 0 = CMYK, 2 = YCCK).
        for (int transform : new int[] {0, 2}) {
            byte[] adobe = {(byte) 0xFF, (byte) 0xEE, 0x00, 0x0E, 'A', 'd', 'o', 'b', 'e',
                    0x00, 0x64, 0x00, 0x00, 0x00, 0x00, (byte) transform};
            ByteArrayOutputStream withAdobe = new ByteArrayOutputStream();
            withAdobe.write(plain4, 0, 2);
            withAdobe.write(adobe);
            withAdobe.write(plain4, 2, plain4.length - 2);
            assertCleanOutcome(withAdobe.toByteArray());
        }
        assertCleanOutcome(plain4);
    }

    /** Either a clean refusal or a valid JPEG; never any other exception (that would be a 500). */
    private void assertCleanOutcome(byte[] photo) throws Exception {
        try {
            List<AiImage> out = preparer.prepare(List.of(photo));
            assertThat(decode(out.get(0).data())).isNotNull();
        } catch (InvalidScanPhotoException expected) {
            assertThat(expected.getMessage()).doesNotContain("�");
        }
    }

    // ---- metadata ------------------------------------------------------------------------------

    @Test
    void exifGps_isRemoved() throws Exception {
        byte[] plain = jpeg(200, 100);
        byte[] payload = "Exif\0\0GPSLatitudeRef N GPSLongitude 21.43".getBytes(StandardCharsets.ISO_8859_1);
        int segmentLength = payload.length + 2;
        ByteArrayOutputStream withExif = new ByteArrayOutputStream();
        withExif.write(plain, 0, 2); // FF D8
        withExif.write(0xFF);
        withExif.write(0xE1);
        withExif.write(segmentLength >> 8);
        withExif.write(segmentLength & 0xFF);
        withExif.write(payload);
        withExif.write(plain, 2, plain.length - 2);
        byte[] input = withExif.toByteArray();
        assertThat(contains(input, "GPS")).isTrue();

        byte[] output = preparer.prepare(List.of(input)).get(0).data();

        assertThat(contains(output, "Exif")).isFalse();
        assertThat(contains(output, "GPS")).isFalse();
        assertThat(decode(output).getWidth()).isEqualTo(200);
    }

    // ---- size guard ----------------------------------------------------------------------------

    @Test
    void sizeGuard_reencodesAtLowerQualityWhenTotalTooBig() throws Exception {
        List<byte[]> photos = List.of(
                jpeg(noise(400, 300, 1), 0.9f), jpeg(noise(400, 300, 2), 0.9f), jpeg(noise(400, 300, 3), 0.9f));

        List<AiImage> firstPass = new PhotoPreparer().prepare(photos);
        // A guard of 1 byte forces the second pass.
        List<AiImage> guarded = new PhotoPreparer(1).prepare(photos);

        assertThat(guarded).hasSize(3);
        for (int i = 0; i < 3; i++) {
            assertThat(guarded.get(i).data().length).isLessThan(firstPass.get(i).data().length);
            assertThat(guarded.get(i).mimeType()).isEqualTo("image/jpeg");
        }
    }

    @Test
    void sizeGuard_isNotTriggeredForSmallTotals() throws Exception {
        List<byte[]> photos = List.of(jpeg(300, 200));
        byte[] normal = new PhotoPreparer().prepare(photos).get(0).data();
        byte[] sameAgain = new PhotoPreparer(10_000_000).prepare(photos).get(0).data();
        assertThat(sameAgain).isEqualTo(normal);
    }
}
