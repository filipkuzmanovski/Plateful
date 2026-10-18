package mk.ukim.finki.wp.recipeappbackend.security;

import mk.ukim.finki.wp.recipeappbackend.exception.AdminForbiddenException;
import mk.ukim.finki.wp.recipeappbackend.service.RecipeVisibility;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Called first by every admin route. Admin status is read from users.is_admin on every call (never
 * from the JWT), and an admin must also have a verified second factor. A failing factor lookup
 * propagates, so the request fails closed.
 */
@Component
public class AdminGuard {

    private final RecipeVisibility visibility;
    private final MfaFactorLookup mfaFactorLookup;

    public AdminGuard(RecipeVisibility visibility, MfaFactorLookup mfaFactorLookup) {
        this.visibility = visibility;
        this.mfaFactorLookup = mfaFactorLookup;
    }

    public void requireAdmin(UUID userId) {
        if (!visibility.isAdmin(userId)) {
            throw AdminForbiddenException.forbidden();
        }
        if (!mfaFactorLookup.hasVerifiedFactor(userId)) {
            throw AdminForbiddenException.mfaSetupRequired();
        }
    }
}
