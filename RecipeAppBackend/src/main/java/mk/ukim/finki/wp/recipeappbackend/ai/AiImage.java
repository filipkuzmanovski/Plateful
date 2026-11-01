package mk.ukim.finki.wp.recipeappbackend.ai;

/**
 * One image sent to a vision model. {@link #toString()} shows the MIME type and the byte count only,
 * so the bytes can never reach a log line.
 *
 * @param mimeType e.g. {@code image/jpeg}
 * @param data     the encoded image
 */
public record AiImage(String mimeType, byte[] data) {

    @Override
    public String toString() {
        return "AiImage[mimeType=" + mimeType + ", bytes=" + (data == null ? 0 : data.length) + "]";
    }
}
