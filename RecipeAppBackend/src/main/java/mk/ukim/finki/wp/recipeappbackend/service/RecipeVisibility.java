package mk.ukim.finki.wp.recipeappbackend.service;

import mk.ukim.finki.wp.recipeappbackend.exception.RecipeHiddenException;
import mk.ukim.finki.wp.recipeappbackend.exception.RecipeNotApprovedException;
import mk.ukim.finki.wp.recipeappbackend.exception.ResourceNotFoundException;
import mk.ukim.finki.wp.recipeappbackend.model.RecipeStatus;
import mk.ukim.finki.wp.recipeappbackend.model.entities.Recipe;
import mk.ukim.finki.wp.recipeappbackend.repository.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The one visibility rule for recipe reads: a recipe is visible to viewer V when it is approved,
 * or V is its author, or V is an admin. Admin status is read from users.is_admin on every call
 * (the JWT only supplies the aal2 requirement, see {@link #isAdmin}). A null viewer is an anonymous request.
 * <p>
 * Listing queries (browse, cuisine counts) use the approved-only specification instead, because
 * they are approved-only even for authors and admins.
 */
@Component
public class RecipeVisibility {

    private final UserRepository userRepository;

    public RecipeVisibility(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Admin rights for the current request: users.is_admin AND an aal2 session. An admin on an aal1
     * token (password only, or no second factor yet) is treated like any other user, so a stolen
     * password never exposes unpublished recipes and an admin without 2FA cannot self-approve.
     */
    public boolean isAdmin(UUID viewerId) {
        return viewerId != null && currentSessionIsAal2() && userRepository.isAdmin(viewerId);
    }

    /** users.is_admin alone, whatever the session level. Only the admin guard needs this. */
    public boolean hasAdminFlag(UUID userId) {
        return userId != null && userRepository.isAdmin(userId);
    }

    private static boolean currentSessionIsAal2() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof Jwt jwt && "aal2".equals(jwt.getClaimAsString("aal"));
    }

    public boolean canSee(Recipe recipe, UUID viewerId) {
        return recipe.getStatus() == RecipeStatus.APPROVED || isAuthorOrAdmin(recipe, viewerId);
    }

    /**
     * Throws when the recipe is not visible. The only place that chooses between the "hidden"
     * 404 (it was approved once, so the title is still public) and the plain 404.
     */
    public void requireVisible(Recipe recipe, UUID viewerId) {
        if (canSee(recipe, viewerId)) {
            return;
        }
        if (recipe.isEverApproved()) {
            throw new RecipeHiddenException(recipe.getId(), recipe.getApprovedTitle(), recipe.getStatus());
        }
        throw new ResourceNotFoundException("Recipe", recipe.getId());
    }

    /**
     * Guard for every comment, rating and reaction write, deletes included: the recipe must be visible
     * (same exceptions as {@link #requireVisible}) and approved. A viewer who can see an unapproved
     * recipe (its author or an admin) gets {@link RecipeNotApprovedException}.
     */
    public void requireApprovedForInteraction(Recipe recipe, UUID viewerId) {
        requireVisible(recipe, viewerId);
        if (recipe.getStatus() != RecipeStatus.APPROVED) {
            throw new RecipeNotApprovedException(recipe.getId(), recipe.getStatus());
        }
    }

    /** The review block is for the author and admins only. */
    public boolean showsReview(Recipe recipe, UUID viewerId) {
        return isAuthorOrAdmin(recipe, viewerId);
    }

    private boolean isAuthorOrAdmin(Recipe recipe, UUID viewerId) {
        if (viewerId == null) {
            return false;
        }
        return isAuthor(recipe, viewerId) || isAdmin(viewerId);
    }

    private static boolean isAuthor(Recipe recipe, UUID viewerId) {
        return recipe.getUser() != null && viewerId.equals(recipe.getUser().getId());
    }
}
