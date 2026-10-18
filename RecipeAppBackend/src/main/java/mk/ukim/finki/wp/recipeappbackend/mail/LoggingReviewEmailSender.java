package mk.ukim.finki.wp.recipeappbackend.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Stand-in used while no mail account is configured: writes the email to the log instead of sending it. */
public class LoggingReviewEmailSender implements ReviewEmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingReviewEmailSender.class);

    @Override
    public void send(String to, String subject, String body) {
        log.info("Review email (not sent, no mail account configured)\nTo: {}\nSubject: {}\n\n{}", to, subject, body);
    }
}
