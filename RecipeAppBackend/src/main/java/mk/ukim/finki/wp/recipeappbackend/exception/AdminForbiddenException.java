package mk.ukim.finki.wp.recipeappbackend.exception;

/**
 * The caller may not use the admin API. Carries the machine-readable code the client branches on:
 * "forbidden" (not an admin) or "admin_mfa_setup_required" (an admin with no verified second factor).
 * GlobalExceptionHandler turns it into a 403.
 */
public class AdminForbiddenException extends RuntimeException {

    public static final String FORBIDDEN = "forbidden";
    public static final String MFA_SETUP_REQUIRED = "admin_mfa_setup_required";

    private final String code;

    private AdminForbiddenException(String code, String message) {
        super(message);
        this.code = code;
    }

    public static AdminForbiddenException forbidden() {
        return new AdminForbiddenException(FORBIDDEN, "Admin access required");
    }

    public static AdminForbiddenException mfaSetupRequired() {
        return new AdminForbiddenException(MFA_SETUP_REQUIRED,
                "Set up two-factor authentication to use the admin area");
    }

    public String getCode() {
        return code;
    }
}
