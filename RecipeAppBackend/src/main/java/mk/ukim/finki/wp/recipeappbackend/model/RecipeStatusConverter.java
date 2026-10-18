package mk.ukim.finki.wp.recipeappbackend.model;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Maps RecipeStatus to the lowercase text in recipes.status. Applied per field, not globally. */
@Converter
public class RecipeStatusConverter implements AttributeConverter<RecipeStatus, String> {

    @Override
    public String convertToDatabaseColumn(RecipeStatus status) {
        return status == null ? null : status.text();
    }

    @Override
    public RecipeStatus convertToEntityAttribute(String text) {
        return text == null ? null : RecipeStatus.fromText(text);
    }
}
