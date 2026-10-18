package mk.ukim.finki.wp.recipeappbackend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Two-factor enforcement. Supabase marks a session with the claim aal = "aal1" (password only) or
 * "aal2" (password plus a verified second factor). A user who has set up two-factor must not be
 * able to act on an aal1 token, otherwise the second factor protects nothing: an attacker with
 * only the password could still call the API.
 *
 * The rule: for an authenticated request that is a mutation (any method other than GET) or a
 * read of a path with a "me" or "admin" segment, if the token is not aal2 and the user has a verified factor, answer 401 with code
 * "mfa_required". Public GETs are never blocked, so browsing works for everyone.
 *
 * Fail closed: if we cannot find out whether the user has a factor (database down, or any other
 * error), the request is refused with 503 rather than let through.
 *
 * IMPORTANT: this class is deliberately NOT a Spring bean (no @Component, no @Bean). SecurityConfig
 * creates it with "new" inside the security chain. A filter that is a bean is also registered by
 * Spring Boot as an ordinary servlet filter that runs BEFORE the JWT is read and sets the
 * "already filtered" flag of OncePerRequestFilter, so the copy inside the security chain would
 * skip itself and enforcement would silently stop working.
 */
public final class MfaEnforcementFilter extends OncePerRequestFilter {

    static final String REQUIRED_DETAIL = "Two-factor verification required";
    static final String UNAVAILABLE_DETAIL = "Could not verify two-factor status";
    static final String CODE = "mfa_required";

    private static final Logger log = LoggerFactory.getLogger(MfaEnforcementFilter.class);

    private final MfaFactorLookup lookup;
    private final JsonMapper mapper;

    public MfaEnforcementFilter(MfaFactorLookup lookup, JsonMapper mapper) {
        this.lookup = lookup;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        // Anonymous requests and non-JWT principals are not our concern: the authorization rules decide.
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof Jwt jwt)
                || !isGuarded(request) || "aal2".equals(jwt.getClaimAsString("aal"))) {
            chain.doFilter(request, response);
            return;
        }

        UUID userId;
        try {
            userId = UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException e) {
            // Supabase subjects are always UUIDs, so such a token has no factor to check; the
            // @CurrentUserId resolver rejects it with 401 on any route that needs an identity.
            chain.doFilter(request, response);
            return;
        }

        boolean hasFactor;
        try {
            hasFactor = lookup.hasVerifiedFactor(userId);
        } catch (Exception e) { // ANY failure, not just DataAccessException: fail closed
            // Class name only: never log anything beyond what is needed to diagnose.
            log.warn("MFA status lookup failed ({}); refusing request", e.getClass().getSimpleName());
            writeProblem(response, HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE_DETAIL, false);
            return;
        }

        if (hasFactor) {
            writeProblem(response, HttpStatus.UNAUTHORIZED, REQUIRED_DETAIL, true);
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * Mutations and reads of any path with a "me" or "admin" segment are guarded; every other GET (public
     * browsing) is not. Segments are compared whole, so "medium" and "administrator-picks" do not match,
     * while "/api/users/me/saved" and "/api/admin/recipes/pending" do.
     *
     * The decision is made on the DECODED path. getRequestURI() is raw, so "/api/users/m%65" would not
     * contain a "me" segment and slip past, yet Spring routes on the decoded path and would reach the
     * "/me" handler. UrlPathHelper also drops ";" path parameters, matching how the routes are resolved.
     */
    private static boolean isGuarded(HttpServletRequest request) {
        if (!"GET".equals(request.getMethod())) {
            return true;
        }
        String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        List<String> segments = Arrays.asList(path.split("/"));
        return segments.contains("me") || segments.contains("admin");
    }

    private void writeProblem(HttpServletResponse response, HttpStatus status, String detail, boolean mfaRequired)
            throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        if (mfaRequired) {
            problem.setProperty("code", CODE);
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"insufficient_user_authentication\"");
        }
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(mapper.writeValueAsString(problem));
    }
}
