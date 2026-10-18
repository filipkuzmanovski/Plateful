package mk.ukim.finki.wp.recipeappbackend.mail;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import mk.ukim.finki.wp.recipeappbackend.service.RecipeReviewedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ReviewEmailListenerTest {

    private final UUID recipeId = UUID.randomUUID();
    private final UUID authorId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();

    private ReviewEmailSender sender;
    private ReviewEmailListener listener;

    @BeforeEach
    void setUp() {
        sender = mock(ReviewEmailSender.class);
        listener = new ReviewEmailListener(sender, "http://localhost:5173");
    }

    @Test
    void sendsApprovedEmail() {
        listener.onRecipeReviewed(new RecipeReviewedEvent(
                recipeId, true, "Soup", null, "author@example.com", authorId, adminId));

        ReviewEmails.Email expected = ReviewEmails.approved("Soup", "http://localhost:5173/recipes/" + recipeId);
        verify(sender).send("author@example.com", expected.subject(), expected.body());
    }

    @Test
    void sendsRejectedEmailWithEditLink() {
        listener.onRecipeReviewed(new RecipeReviewedEvent(
                recipeId, false, "Soup", "Too salty", "author@example.com", authorId, adminId));

        ReviewEmails.Email expected = ReviewEmails.rejected(
                "Soup", "Too salty", "http://localhost:5173/recipes/" + recipeId + "/edit");
        verify(sender).send("author@example.com", expected.subject(), expected.body());
    }

    @Test
    void skipsAuthorless() {
        listener.onRecipeReviewed(new RecipeReviewedEvent(recipeId, true, "Soup", null, null, null, adminId));

        verify(sender, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void skipsSelfReview() {
        listener.onRecipeReviewed(new RecipeReviewedEvent(
                recipeId, true, "Soup", null, "admin@example.com", adminId, adminId));

        verify(sender, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void senderFailureIsSwallowedAndLoggedAsWarning() {
        doThrow(new IllegalStateException("smtp down")).when(sender).send(anyString(), anyString(), anyString());
        Logger logger = (Logger) LoggerFactory.getLogger(ReviewEmailListener.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        boolean additive = logger.isAdditive();
        logger.setAdditive(false); // keep the expected warning out of the test output
        try {
            assertThatCode(() -> listener.onRecipeReviewed(new RecipeReviewedEvent(
                    recipeId, true, "Soup", null, "author@example.com", authorId, adminId)))
                    .doesNotThrowAnyException();

            assertThat(logs.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).contains(recipeId.toString());
            });
        } finally {
            logger.detachAppender(logs);
            logger.setAdditive(additive);
        }
    }
}
