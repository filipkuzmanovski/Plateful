package mk.ukim.finki.wp.recipeappbackend.mail;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import mk.ukim.finki.wp.recipeappbackend.service.CommentRemovedEvent;
import mk.ukim.finki.wp.recipeappbackend.service.RecipeRemovedEvent;
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

class RemovalEmailListenerTest {

    private final UUID targetId = UUID.randomUUID();
    private final UUID authorId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();

    private ReviewEmailSender sender;
    private RemovalEmailListener listener;

    @BeforeEach
    void setUp() {
        sender = mock(ReviewEmailSender.class);
        listener = new RemovalEmailListener(sender);
    }

    private RecipeRemovedEvent recipeEvent(String email, UUID author) {
        return new RecipeRemovedEvent(targetId, "Soup", "Spam", email, author, adminId);
    }

    private CommentRemovedEvent commentEvent(String email, UUID author) {
        return new CommentRemovedEvent(targetId, "Soup", "Too salty", "Rude", email, author, adminId);
    }

    @Test
    void sendsRecipeRemovedEmail() {
        listener.onRecipeRemoved(recipeEvent("author@example.com", authorId));

        ReviewEmails.Email expected = RemovalEmails.recipeRemoved("Soup", "Spam");
        verify(sender).send("author@example.com", expected.subject(), expected.body());
    }

    @Test
    void sendsCommentRemovedEmail() {
        listener.onCommentRemoved(commentEvent("author@example.com", authorId));

        ReviewEmails.Email expected = RemovalEmails.commentRemoved("Soup", "Rude", "Too salty");
        verify(sender).send("author@example.com", expected.subject(), expected.body());
    }

    @Test
    void skipsAuthorless() {
        listener.onRecipeRemoved(recipeEvent(null, null));
        listener.onCommentRemoved(commentEvent(null, null));

        verify(sender, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void skipsSelfRemoval() {
        listener.onRecipeRemoved(recipeEvent("admin@example.com", adminId));
        listener.onCommentRemoved(commentEvent("admin@example.com", adminId));

        verify(sender, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void senderFailureIsSwallowedAndLoggedAsWarning() {
        doThrow(new IllegalStateException("smtp down")).when(sender).send(anyString(), anyString(), anyString());
        Logger logger = (Logger) LoggerFactory.getLogger(RemovalEmailListener.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        boolean additive = logger.isAdditive();
        logger.setAdditive(false); // keep the expected warnings out of the test output
        try {
            assertThatCode(() -> {
                listener.onRecipeRemoved(recipeEvent("author@example.com", authorId));
                listener.onCommentRemoved(commentEvent("author@example.com", authorId));
            }).doesNotThrowAnyException();

            assertThat(logs.list).hasSize(2).allSatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).contains(targetId.toString());
            });
        } finally {
            logger.detachAppender(logs);
            logger.setAdditive(additive);
        }
    }
}
