package mk.ukim.finki.wp.recipeappbackend.exception;

import org.springframework.http.HttpStatus;

/**
 * A report the rules refuse. Carries the HTTP status and the machine-readable code the client branches on;
 * GlobalExceptionHandler turns it into a ProblemDetail with both.
 */
public class ReportException extends RuntimeException {

    public static final String INVALID_REPORT = "invalid_report";
    public static final String OWN_CONTENT = "own_content";
    public static final String ALREADY_REPORTED = "already_reported";
    public static final String REPORT_LIMIT = "report_limit";
    public static final String ALREADY_HANDLED = "already_handled";

    private final HttpStatus status;
    private final String code;

    private ReportException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static ReportException invalid(String message) {
        return new ReportException(HttpStatus.BAD_REQUEST, INVALID_REPORT, message);
    }

    public static ReportException ownContent() {
        return new ReportException(HttpStatus.FORBIDDEN, OWN_CONTENT, "You can't report your own content.");
    }

    public static ReportException alreadyReported() {
        return new ReportException(HttpStatus.CONFLICT, ALREADY_REPORTED, "You've already reported this.");
    }

    public static ReportException limit() {
        return new ReportException(HttpStatus.TOO_MANY_REQUESTS, REPORT_LIMIT,
                "You've sent 20 reports today. You can report more tomorrow.");
    }

    /** An admin dismissed an item that has no open reports (someone else got there first). */
    public static ReportException alreadyHandled() {
        return new ReportException(HttpStatus.CONFLICT, ALREADY_HANDLED, "These reports were already handled.");
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
