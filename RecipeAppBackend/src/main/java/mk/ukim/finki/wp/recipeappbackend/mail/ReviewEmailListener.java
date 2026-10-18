package mk.ukim.finki.wp.recipeappbackend.mail;

import mk.ukim.finki.wp.recipeappbackend.service.RecipeReviewedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Emails the author once an approve/reject has committed. Runs off the request thread, and a
 * failure is only logged: it never reaches the admin and is not retried.
 */
@Component
public class ReviewEmailListener {

    private static final Logger log = LoggerFactory.getLogger(ReviewEmailListener.class);

    private final ReviewEmailSender sender;
    private final String frontendUrl;

    public ReviewEmailListener(ReviewEmailSender sender, @Value("${plateful.frontend-url}") String frontendUrl) {
        this.sender = sender;
        this.frontendUrl = frontendUrl.endsWith("/") ? frontendUrl.substring(0, frontendUrl.length() - 1) : frontendUrl;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRecipeReviewed(RecipeReviewedEvent event) {
        try {
            if (event.authorEmail() == null || event.authorId() == null || event.authorId().equals(event.adminId())) {
                return;
            }
            String link = frontendUrl + "/recipes/" + event.recipeId();
            ReviewEmails.Email email = event.approved()
                    ? ReviewEmails.approved(event.title(), link)
                    : ReviewEmails.rejected(event.title(), event.reason(), link + "/edit");
            sender.send(event.authorEmail(), email.subject(), email.body());
        } catch (Exception e) {
            log.warn("Could not send the review email for recipe {}", event.recipeId(), e);
        }
    }
}
