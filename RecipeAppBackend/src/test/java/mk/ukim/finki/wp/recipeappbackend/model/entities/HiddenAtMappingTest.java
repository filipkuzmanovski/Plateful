package mk.ukim.finki.wp.recipeappbackend.model.entities;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** hiddenAt must stay a plain column: a timestamp generator on it would hide every new row. */
class HiddenAtMappingTest {

    @Test
    void hiddenAtHasNoGeneratorOnRecipeOrComment() throws NoSuchFieldException {
        for (Class<?> type : new Class<?>[]{Recipe.class, RecipeComment.class}) {
            var field = type.getDeclaredField("hiddenAt");
            assertThat(field.isAnnotationPresent(CreationTimestamp.class)).as(type.getSimpleName()).isFalse();
            assertThat(field.isAnnotationPresent(UpdateTimestamp.class)).as(type.getSimpleName()).isFalse();
        }
    }

    @Test
    void createdAtKeepsItsGenerator() throws NoSuchFieldException {
        assertThat(RecipeComment.class.getDeclaredField("createdAt").isAnnotationPresent(CreationTimestamp.class)).isTrue();
        assertThat(Recipe.class.getDeclaredField("createdAt").isAnnotationPresent(CreationTimestamp.class)).isTrue();
    }
}
