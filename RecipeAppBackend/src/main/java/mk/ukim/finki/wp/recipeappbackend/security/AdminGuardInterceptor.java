package mk.ukim.finki.wp.recipeappbackend.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Runs {@link AdminGuard} before any /api/admin handler is invoked, so it comes before argument
 * resolution and body validation: a non-admin gets 403 whatever they send, and a new admin route
 * cannot forget the check. An exception thrown here is rendered by GlobalExceptionHandler.
 * <p>
 * The guard is fetched lazily so the web configuration does not force an AdminGuard bean into slices
 * that never serve an admin route.
 */
public class AdminGuardInterceptor implements HandlerInterceptor {

    private final ObjectProvider<AdminGuard> adminGuard;

    public AdminGuardInterceptor(ObjectProvider<AdminGuard> adminGuard) {
        this.adminGuard = adminGuard;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        adminGuard.getObject().requireAdmin(CurrentUserIdArgumentResolver.currentUserId());
        return true;
    }
}
