package mk.ukim.finki.wp.recipeappbackend.mail;

import mk.ukim.finki.wp.recipeappbackend.service.CommentRemovedEvent;
import mk.ukim.finki.wp.recipeappbackend.service.RecipeRemovedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

/**
 * Emails the author once an admin removal has committed. Runs off the request thread, and a failure
 * is only logged: it never reaches the admin and is not retried. Nothing is sent for a recipe or
 * comment without an author, or when the admin removed their own.
 */
@Component
public class RemovalEmailListener {

    private static final Logger log = LoggerFactory.getLogger(RemovalEmailListener.class);

    private final ReviewEmailSender sender;

    public RemovalEmailListener(ReviewEmailSender sender) {
        this.sender = sender;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRecipeRemoved(RecipeRemovedEvent event) {
        try {
            if (skip(event.authorEmail(), event.authorId(), event.adminId())) {
                return;
            }
            ReviewEmails.Email email = RemovalEmails.recipeRemoved(event.title(), event.reason());
            sender.send(event.authorEmail(), email.subject(), email.body());
        } catch (Exception e) {
            log.warn("Could not send the removal email for recipe {}", event.recipeId(), e);
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommentRemoved(CommentRemovedEvent event) {
        try {
            if (skip(event.authorEmail(), event.authorId(), event.adminId())) {
                return;
            }
            ReviewEmails.Email email = RemovalEmails.commentRemoved(
                    event.recipeTitle(), event.reason(), event.commentText());
            sender.send(event.authorEmail(), email.subject(), email.body());
        } catch (Exception e) {
            log.warn("Could not send the removal email for comment {}", event.commentId(), e);
        }
    }

    private static boolean skip(String authorEmail, UUID authorId, UUID adminId) {
        return authorEmail == null || authorId == null || authorId.equals(adminId);
    }
}
