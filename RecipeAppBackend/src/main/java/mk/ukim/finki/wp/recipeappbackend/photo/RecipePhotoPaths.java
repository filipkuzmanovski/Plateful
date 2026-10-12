package mk.ukim.finki.wp.recipeappbackend.photo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Validates client-supplied storage paths for recipe photos and turns them into public URLs.
 * A path is only accepted if it lives in the caller's own folder: {userId}/{uuid}.{jpg|png|webp}.
 */
@Component
public class RecipePhotoPaths {

    static final int MAX_PHOTOS = 6;

    private final String publicBaseUrl;

    public RecipePhotoPaths(@Value("${app.storage.photos-public-base-url}") String publicBaseUrl) {
        this.publicBaseUrl = publicBaseUrl.endsWith("/")
                ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1)
                : publicBaseUrl;
    }

    public String publicUrl(String path) {
        return publicBaseUrl + "/" + path;
    }

    /** Returns an immutable copy in the same order; null counts as empty. */
    public List<String> validate(UUID userId, List<String> paths) {
        if (paths == null) {
            return List.of();
        }
        if (paths.size() > MAX_PHOTOS) {
            throw new InvalidPhotoException("At most " + MAX_PHOTOS + " photos");
        }
        Pattern allowed = Pattern.compile(
                "^" + Pattern.quote(userId.toString()) + "/[0-9a-fA-F-]{36}\\.(jpg|png|webp)$");
        Set<String> seen = new HashSet<>();
        for (String path : paths) {
            if (path == null || !allowed.matcher(path).matches()) {
                throw new InvalidPhotoException("Invalid photo: " + path);
            }
            if (!seen.add(path)) {
                throw new InvalidPhotoException("Duplicate photo: " + path);
            }
        }
        return List.copyOf(paths);
    }
}
