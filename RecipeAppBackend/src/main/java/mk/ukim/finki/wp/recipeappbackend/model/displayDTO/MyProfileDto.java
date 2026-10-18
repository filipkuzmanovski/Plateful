package mk.ukim.finki.wp.recipeappbackend.model.displayDTO;

import mk.ukim.finki.wp.recipeappbackend.model.entities.User;

import java.util.UUID;

/**
 * The signed-in user's own profile: the public name fields plus what only they
 * need. Public GET /api/users/{id} keeps UserDisplayDto, which never exposes isAdmin.
 */
public record MyProfileDto(
        UUID id,
        String firstName,
        String lastName,
        boolean isAdmin,
        long rejectedCount
) {
    public static MyProfileDto of(User user, long rejectedCount) {
        return new MyProfileDto(user.getId(), user.getFirstName(), user.getLastName(), user.isAdmin(), rejectedCount);
    }
}
