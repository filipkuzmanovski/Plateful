package mk.ukim.finki.wp.recipeappbackend.photo;

import mk.ukim.finki.wp.recipeappbackend.model.createDTO.RecipeVideoCreateDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.RecipeVideoDto;
import mk.ukim.finki.wp.recipeappbackend.model.entities.Recipe;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Validates client-supplied storage paths for a recipe video and turns them into public URLs.
 * A path is only accepted if it lives in the caller's own folder: the clip as
 * {userId}/{uuid}.{mp4|webm|mov}, the still-frame poster as {userId}/{uuid}.jpg.
 * The video bytes are never seen here: the bucket enforces size and MIME type.
 */
@Component
public class RecipeVideoPaths {

    static final int MAX_SECONDS = 60;

    private final String publicBaseUrl;

    public RecipeVideoPaths(@Value("${app.storage.videos-public-base-url}") String publicBaseUrl) {
        this.publicBaseUrl = publicBaseUrl.endsWith("/")
                ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1)
                : publicBaseUrl;
    }

    public String publicUrl(String path) {
        return publicBaseUrl + "/" + path;
    }

    /** Returns the input itself when valid; null (no video) passes through as null. */
    public RecipeVideoCreateDto validate(UUID userId, RecipeVideoCreateDto video) {
        if (video == null) {
            return null;
        }
        String folder = Pattern.quote(userId.toString());
        if (!matches("^" + folder + "/[0-9a-f-]{36}\\.(mp4|webm|mov)$", video.path())) {
            throw new InvalidVideoException("Video must be an uploaded file in your folder.");
        }
        if (!matches("^" + folder + "/[0-9a-f-]{36}\\.jpg$", video.posterPath())) {
            throw new InvalidVideoException("Video poster must be an uploaded image in your folder.");
        }
        Integer seconds = video.durationSeconds();
        if (seconds == null || seconds < 1 || seconds > MAX_SECONDS) {
            throw new InvalidVideoException("Videos can be up to " + MAX_SECONDS + " seconds long.");
        }
        return video;
    }

    /** The recipe's video with public URLs, or null when it has none. */
    public RecipeVideoDto toDisplay(Recipe recipe) {
        if (recipe.getVideoPath() == null) {
            return null;
        }
        return new RecipeVideoDto(
                publicUrl(recipe.getVideoPath()),
                publicUrl(recipe.getVideoPosterPath()),
                recipe.getVideoDurationSeconds());
    }

    private static boolean matches(String regex, String path) {
        return path != null && Pattern.matches(regex, path);
    }
}
