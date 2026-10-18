package mk.ukim.finki.wp.recipeappbackend.photo;

import mk.ukim.finki.wp.recipeappbackend.model.createDTO.RecipeVideoCreateDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.RecipeVideoDto;
import mk.ukim.finki.wp.recipeappbackend.model.entities.Recipe;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecipeVideoPathsTest {

    private static final String BASE = "https://x.supabase.co/storage/v1/object/public/recipe-videos";
    private static final String BAD_VIDEO = "Video must be an uploaded file in your folder.";
    private static final String BAD_POSTER = "Video poster must be an uploaded image in your folder.";
    private static final String BAD_DURATION = "Videos can be up to 60 seconds long.";

    private final RecipeVideoPaths paths = new RecipeVideoPaths(BASE + "/");

    private final UUID u = UUID.randomUUID();

    private String clip(String ext) {
        return u + "/" + UUID.randomUUID() + "." + ext;
    }

    private String poster() {
        return u + "/" + UUID.randomUUID() + ".jpg";
    }

    @Test
    void acceptsOwnFolderVideoAndPoster() {
        for (String ext : new String[]{"mp4", "webm", "mov"}) {
            RecipeVideoCreateDto dto = new RecipeVideoCreateDto(clip(ext), poster(), 42);

            assertThat(paths.validate(u, dto)).isSameAs(dto);
        }
    }

    @Test
    void rejectsForeignFolder() {
        String foreign = UUID.randomUUID() + "/" + UUID.randomUUID() + ".mp4";

        assertThatThrownBy(() -> paths.validate(u, new RecipeVideoCreateDto(foreign, poster(), 10)))
                .isInstanceOf(InvalidVideoException.class)
                .hasMessage(BAD_VIDEO);
    }

    @Test
    void rejectsBadExtension() {
        assertThatThrownBy(() -> paths.validate(u, new RecipeVideoCreateDto(clip("avi"), poster(), 10)))
                .isInstanceOf(InvalidVideoException.class)
                .hasMessage(BAD_VIDEO);
    }

    @Test
    void rejectsNonJpegPoster() {
        String png = u + "/" + UUID.randomUUID() + ".png";

        assertThatThrownBy(() -> paths.validate(u, new RecipeVideoCreateDto(clip("mp4"), png, 10)))
                .isInstanceOf(InvalidVideoException.class)
                .hasMessage(BAD_POSTER);
    }

    @Test
    void rejectsPosterInForeignFolder() {
        String foreign = UUID.randomUUID() + "/" + UUID.randomUUID() + ".jpg";

        assertThatThrownBy(() -> paths.validate(u, new RecipeVideoCreateDto(clip("mp4"), foreign, 10)))
                .isInstanceOf(InvalidVideoException.class)
                .hasMessage(BAD_POSTER);
    }

    @Test
    void rejectsMalformedUuid() {
        assertThatThrownBy(() -> paths.validate(u, new RecipeVideoCreateDto(u + "/cat.mp4", poster(), 10)))
                .isInstanceOf(InvalidVideoException.class)
                .hasMessage(BAD_VIDEO);
        assertThatThrownBy(() -> paths.validate(u, new RecipeVideoCreateDto(clip("mp4"), u + "/cat.jpg", 10)))
                .isInstanceOf(InvalidVideoException.class)
                .hasMessage(BAD_POSTER);
    }

    @Test
    void durationBounds() {
        for (int bad : new int[]{0, 61}) {
            assertThatThrownBy(() -> paths.validate(u, new RecipeVideoCreateDto(clip("mp4"), poster(), bad)))
                    .isInstanceOf(InvalidVideoException.class)
                    .hasMessage(BAD_DURATION);
        }
        for (int ok : new int[]{1, 60}) {
            RecipeVideoCreateDto dto = new RecipeVideoCreateDto(clip("mp4"), poster(), ok);

            assertThat(paths.validate(u, dto)).isSameAs(dto);
        }
    }

    @Test
    void nullVideoIsNull() {
        assertThat(paths.validate(u, null)).isNull();
    }

    @Test
    void publicUrlJoinsBase() {
        assertThat(paths.publicUrl("a/b.mp4")).isEqualTo(BASE + "/a/b.mp4");
    }

    @Test
    void toDisplayBuildsPublicUrls() {
        Recipe r = new Recipe();
        r.setVideoPath("a/b.mp4");
        r.setVideoPosterPath("a/c.jpg");
        r.setVideoDurationSeconds(42);

        assertThat(paths.toDisplay(r)).isEqualTo(new RecipeVideoDto(BASE + "/a/b.mp4", BASE + "/a/c.jpg", 42));
    }

    @Test
    void toDisplayIsNullWithoutVideo() {
        assertThat(paths.toDisplay(new Recipe())).isNull();
    }
}
