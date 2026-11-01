package mk.ukim.finki.wp.recipeappbackend.generation;

import mk.ukim.finki.wp.recipeappbackend.ai.AiImage;
import mk.ukim.finki.wp.recipeappbackend.exception.InvalidScanPhotoException;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Turns 1 to 3 uploaded photos into small, metadata-free JPEGs, entirely in memory. Only the declared
 * limits are trusted: the file name and content type are never looked at, the dimensions are read from
 * the header before any pixel is decoded, and the re-encode writes no metadata, so EXIF (GPS, camera,
 * time) and every other segment is gone. Every decode problem becomes an
 * {@link InvalidScanPhotoException}; nothing else escapes. Messages never include image bytes.
 */
@Component
public class PhotoPreparer {

    static final int MAX_PHOTOS = 3;
    static final int MAX_BYTES = 1_048_576;
    static final int MAX_SIDE = 4096;
    static final int OUTPUT_SIDE = 1536;
    static final float QUALITY = 0.85f;
    static final int GUARD_TOTAL = 2_800_000;
    static final float GUARD_QUALITY = 0.7f;

    private final int guardTotal;

    public PhotoPreparer() {
        this(GUARD_TOTAL);
    }

    /** Test seam: lets a test force the size guard without building multi-megabyte images. */
    PhotoPreparer(int guardTotal) {
        this.guardTotal = guardTotal;
    }

    public List<AiImage> prepare(List<byte[]> photos) {
        if (photos == null || photos.isEmpty() || photos.size() > MAX_PHOTOS) {
            throw new InvalidScanPhotoException("Photo count must be 1 to " + MAX_PHOTOS);
        }
        for (byte[] photo : photos) {
            checkEnvelope(photo);
        }

        List<BufferedImage> scaled = new ArrayList<>(photos.size());
        for (byte[] photo : photos) {
            scaled.add(decodeAndScale(photo));
        }

        List<byte[]> encoded = encodeAll(scaled, QUALITY);
        long total = 0;
        for (byte[] bytes : encoded) {
            total += bytes.length;
        }
        if (total > guardTotal) {
            encoded = encodeAll(scaled, GUARD_QUALITY);
        }

        List<AiImage> result = new ArrayList<>(encoded.size());
        for (byte[] bytes : encoded) {
            result.add(new AiImage("image/jpeg", bytes));
        }
        return result;
    }

    private static void checkEnvelope(byte[] photo) {
        if (photo == null || photo.length == 0) {
            throw new InvalidScanPhotoException("Photo is empty");
        }
        if (photo.length > MAX_BYTES) {
            throw new InvalidScanPhotoException("Photo is larger than " + MAX_BYTES + " bytes");
        }
        if (photo.length < 3
                || (photo[0] & 0xFF) != 0xFF || (photo[1] & 0xFF) != 0xD8 || (photo[2] & 0xFF) != 0xFF) {
            throw new InvalidScanPhotoException("Photo is not a JPEG");
        }
    }

    /**
     * Decodes and scales one photo. Colour conversion can also fail for exotic colour spaces (CMYK/YCCK
     * from some cameras and scanners), so the scale step is covered too: nothing but
     * {@link InvalidScanPhotoException} may escape.
     */
    private static BufferedImage decodeAndScale(byte[] photo) {
        try {
            return scale(decode(photo));
        } catch (InvalidScanPhotoException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new InvalidScanPhotoException("Photo cannot be converted (" + e.getClass().getSimpleName() + ")");
        }
    }

    /** Reads the size from the header first, then decodes. Any failure is "invalid". */
    private static BufferedImage decode(byte[] photo) {
        ImageReader reader = null;
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(photo))) {
            if (in == null) {
                throw new InvalidScanPhotoException("Photo cannot be read");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("jpeg");
            if (!readers.hasNext()) {
                throw new InvalidScanPhotoException("No JPEG reader available");
            }
            reader = readers.next();
            reader.setInput(in, true, true);

            // A warning (for example "Premature end of JPEG file") means the image is damaged: the JDK
            // would otherwise return a half-gray picture without throwing.
            AtomicBoolean damaged = new AtomicBoolean(false);
            reader.addIIOReadWarningListener((source, warning) -> damaged.set(true));

            int width = reader.getWidth(0);
            int height = reader.getHeight(0);
            if (width < 1 || height < 1 || width > MAX_SIDE || height > MAX_SIDE) {
                throw new InvalidScanPhotoException("Photo dimensions out of range");
            }

            BufferedImage image = reader.read(0);
            if (image == null || damaged.get()) {
                throw new InvalidScanPhotoException("Photo is damaged");
            }
            return image;
        } catch (InvalidScanPhotoException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            // IIOException, IllegalArgumentException, CMMException (CMYK) and any other ImageIO surprise.
            throw new InvalidScanPhotoException("Photo cannot be decoded (" + e.getClass().getSimpleName() + ")");
        } finally {
            if (reader != null) {
                reader.dispose();
            }
        }
    }

    /** Scales to at most {@link #OUTPUT_SIDE} on the long side (never up) into an RGB image. */
    private static BufferedImage scale(BufferedImage source) {
        int w = source.getWidth();
        int h = source.getHeight();
        int longSide = Math.max(w, h);
        int targetW = w;
        int targetH = h;
        if (longSide > OUTPUT_SIDE) {
            double factor = (double) OUTPUT_SIDE / longSide;
            targetW = Math.max(1, (int) Math.round(w * factor));
            targetH = Math.max(1, (int) Math.round(h * factor));
        }
        BufferedImage target = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(source, 0, 0, targetW, targetH, null);
        } finally {
            g.dispose();
        }
        return target;
    }

    private static List<byte[]> encodeAll(List<BufferedImage> images, float quality) {
        List<byte[]> out = new ArrayList<>(images.size());
        for (BufferedImage image : images) {
            out.add(encode(image, quality));
        }
        return out;
    }

    /** Writes a JPEG with no metadata (null metadata means no EXIF, XMP or comment segments). */
    private static byte[] encode(BufferedImage image, float quality) {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new InvalidScanPhotoException("No JPEG writer available");
        }
        ImageWriter writer = writers.next();
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
                writer.setOutput(out);
                writer.write(null, new IIOImage(image, null, null), param);
            }
            return bytes.toByteArray();
        } catch (IOException | RuntimeException e) {
            throw new InvalidScanPhotoException("Photo cannot be re-encoded (" + e.getClass().getSimpleName() + ")");
        } finally {
            writer.dispose();
        }
    }
}
