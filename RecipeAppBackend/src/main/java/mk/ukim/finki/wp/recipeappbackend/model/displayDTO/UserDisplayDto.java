package mk.ukim.finki.wp.recipeappbackend.model.displayDTO;

import mk.ukim.finki.wp.recipeappbackend.model.entities.User;

import java.util.UUID;

/**
 * Deliberately just first/last name, no email — this is what shows up
 * publicly next to a recipe, rating, or comment ("recipe by Jane Doe"), not
 * a full profile. id is included even though it's not "displayed" as text,
 * so the frontend can link to "more recipes by this author" or compare
 * against the logged-in user to show "this is you" / enable edit buttons.
 */
public record UserDisplayDto(
        UUID id,
        String firstName,
        String lastName
) {
    public static UserDisplayDto fromEntity(User user) {
        return new UserDisplayDto(user.getId(), user.getFirstName(), user.getLastName());
    }
}
