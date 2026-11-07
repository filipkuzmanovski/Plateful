package mk.ukim.finki.wp.recipeappbackend.model;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Locale;

/**
 * Why someone reported a recipe or comment. The database (content_reports.reason) and the API both use the
 * lowercase text, exactly one of the five values; nothing else is accepted.
 */
public enum ReportReason {
    SPAM,
    OFFENSIVE,
    UNSAFE,
    NOT_FOOD,
    OTHER;

    /** The lowercase text used in the database column and in JSON. */
    public String text() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Exact match on the lowercase text; anything else (null, other case, unknown) is refused. */
    public static ReportReason fromText(String text) {
        for (ReportReason reason : values()) {
            if (reason.text().equals(text)) {
                return reason;
            }
        }
        throw new IllegalArgumentException("Unknown report reason: " + text);
    }

    /** Maps the enum to the lowercase text in content_reports.reason. Applied per field, not globally. */
    @Converter
    public static class TextConverter implements AttributeConverter<ReportReason, String> {

        @Override
        public String convertToDatabaseColumn(ReportReason reason) {
            return reason == null ? null : reason.text();
        }

        @Override
        public ReportReason convertToEntityAttribute(String text) {
            return text == null ? null : fromText(text);
        }
    }
}
