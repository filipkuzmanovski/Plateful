package mk.ukim.finki.wp.recipeappbackend.security;

import mk.ukim.finki.wp.recipeappbackend.exception.AdminForbiddenException;
import mk.ukim.finki.wp.recipeappbackend.service.RecipeVisibility;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Called first by every admin route. Admin status is read from users.is_admin on every call (never
 * from the JWT; the flag alone, because the aal2 requirement is the filter's job here and a missing
 * factor must still answer admin_mfa_setup_required), and an admin must also have a verified second factor. A failing factor lookup
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
        if (!visibility.hasAdminFlag(userId)) {
            throw AdminForbiddenException.forbidden();
        }
        if (!mfaFactorLookup.hasVerifiedFactor(userId)) {
            throw AdminForbiddenException.mfaSetupRequired();
        }
    }
}
