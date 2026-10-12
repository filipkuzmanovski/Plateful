package mk.ukim.finki.wp.recipeappbackend.photo;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecipePhotoPathsTest {

    private final RecipePhotoPaths paths =
            new RecipePhotoPaths("https://x.supabase.co/storage/v1/object/public/recipe-photos/");

    private final UUID u = UUID.randomUUID();

    private String newPath() {
        return u + "/" + UUID.randomUUID() + ".webp";
    }

    @Test
    void acceptsOwnPathsInOrder() {
        String p1 = newPath();
        String p2 = newPath();

        assertThat(paths.validate(u, List.of(p1, p2))).containsExactly(p1, p2);
    }

    @Test
    void nullIsEmpty() {
        assertThat(paths.validate(u, null)).isEmpty();
    }

    @Test
    void rejectsOtherUsersFolder() {
        String other = UUID.randomUUID() + "/" + UUID.randomUUID() + ".webp";
        assertInvalid(other);
    }

    @Test
    void rejectsTraversal() {
        assertInvalid(u + "/../x.webp");
    }

    @Test
    void rejectsFullUrl() {
        assertInvalid("https://evil.com/a.jpg");
    }

    @Test
    void rejectsWrongExtension() {
        assertInvalid(u + "/" + UUID.randomUUID() + ".gif");
    }

    @Test
    void rejectsMissingUuidName() {
        assertInvalid(u + "/cat.jpg");
    }

    @Test
    void rejectsDuplicates() {
        String p = newPath();

        assertThatThrownBy(() -> paths.validate(u, List.of(p, p)))
                .isInstanceOf(InvalidPhotoException.class)
                .hasMessage("Duplicate photo: " + p);
    }

    @Test
    void rejectsSeven() {
        List<String> seven = java.util.stream.IntStream.range(0, 7).mapToObj(i -> newPath()).toList();

        assertThatThrownBy(() -> paths.validate(u, seven))
                .isInstanceOf(InvalidPhotoException.class)
                .hasMessage("At most 6 photos");
    }

    @Test
    void publicUrlJoinsWithSingleSlash() {
        assertThat(paths.publicUrl("a/b.jpg"))
                .isEqualTo("https://x.supabase.co/storage/v1/object/public/recipe-photos/a/b.jpg");
    }

    private void assertInvalid(String path) {
        assertThatThrownBy(() -> paths.validate(u, List.of(path)))
                .isInstanceOf(InvalidPhotoException.class)
                .hasMessageStartingWith("Invalid photo: ");
    }
}
