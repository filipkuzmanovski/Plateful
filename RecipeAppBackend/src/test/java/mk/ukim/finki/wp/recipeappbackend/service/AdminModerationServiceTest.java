package mk.ukim.finki.wp.recipeappbackend.service;

import mk.ukim.finki.wp.recipeappbackend.exception.InvalidReviewReasonException;
import mk.ukim.finki.wp.recipeappbackend.exception.ResourceNotFoundException;
import mk.ukim.finki.wp.recipeappbackend.model.RecipeStatus;
import mk.ukim.finki.wp.recipeappbackend.model.entities.Recipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.RecipeComment;
import mk.ukim.finki.wp.recipeappbackend.model.entities.User;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeCommentRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AdminModerationServiceTest {

    private RecipeRepository recipeRepository;
    private RecipeCommentRepository commentRepository;
    private ApplicationEventPublisher publisher;
    private AdminModerationService service;

    private final UUID adminId = UUID.randomUUID();
    private final UUID authorId = UUID.randomUUID();
    private final UUID commenterId = UUID.randomUUID();
    private final UUID recipeId = UUID.randomUUID();
    private final UUID commentId = UUID.randomUUID();
    private User author;
    private User commenter;

    @BeforeEach
    void setUp() {
        recipeRepository = mock(RecipeRepository.class);
        commentRepository = mock(RecipeCommentRepository.class);
        publisher = mock(ApplicationEventPublisher.class);
        service = new AdminModerationService(recipeRepository, commentRepository, publisher);

        author = User.builder().id(authorId).firstName("Al").lastName("Author").email("al@example.test").build();
        commenter = User.builder().id(commenterId).firstName("Cy").lastName("Commenter").email("cy@example.test").build();
    }

    private Recipe recipe(User owner, RecipeStatus status) {
        return Recipe.builder().id(recipeId).title("Soup").user(owner).status(status).build();
    }

    private RecipeComment comment(Recipe recipe) {
        return RecipeComment.builder().id(commentId).recipe(recipe).user(commenter).body("Too salty for me").build();
    }

    // ---- remove recipe -------------------------------------------------------------------------

    @Test
    void removeRecipeDeletesItAndPublishesCapturedFields() {
        Recipe recipe = recipe(author, RecipeStatus.APPROVED);
        when(recipeRepository.findForReviewById(recipeId)).thenReturn(Optional.of(recipe));

        service.removeRecipe(adminId, recipeId, "  Spam  ");

        verify(recipeRepository).delete(recipe);
        ArgumentCaptor<RecipeRemovedEvent> captor = ArgumentCaptor.forClass(RecipeRemovedEvent.class);
        verify(publisher).publishEvent(captor.capture());
        RecipeRemovedEvent event = captor.getValue();
        assertThat(event.recipeId()).isEqualTo(recipeId);
        assertThat(event.title()).isEqualTo("Soup");
        assertThat(event.reason()).isEqualTo("Spam");
        assertThat(event.authorEmail()).isEqualTo("al@example.test");
        assertThat(event.authorId()).isEqualTo(authorId);
        assertThat(event.adminId()).isEqualTo(adminId);
    }

    @Test
    void removeRecipeWorksOnAPendingRecipe() {
        Recipe recipe = recipe(author, RecipeStatus.PENDING);
        when(recipeRepository.findForReviewById(recipeId)).thenReturn(Optional.of(recipe));

        service.removeRecipe(adminId, recipeId, "Off topic");

        verify(recipeRepository).delete(recipe);
    }

    @Test
    void removeAuthorlessRecipePublishesNullEmail() {
        Recipe recipe = recipe(null, RecipeStatus.APPROVED);
        when(recipeRepository.findForReviewById(recipeId)).thenReturn(Optional.of(recipe));

        service.removeRecipe(adminId, recipeId, "Imported junk");

        verify(recipeRepository).delete(recipe);
        ArgumentCaptor<RecipeRemovedEvent> captor = ArgumentCaptor.forClass(RecipeRemovedEvent.class);
        verify(publisher).publishEvent(captor.capture());
        assertThat(captor.getValue().authorEmail()).isNull();
        assertThat(captor.getValue().authorId()).isNull();
    }

    @Test
    void removeMissingRecipeIs404() {
        when(recipeRepository.findForReviewById(recipeId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.removeRecipe(adminId, recipeId, "Spam"))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(recipeRepository, never()).delete(any(Recipe.class));
        verifyNoInteractions(publisher);
    }

    @Test
    void removeRecipeRejectsBadReasonsBeforeTouchingAnything() {
        for (String bad : new String[]{null, "", "   ", "x".repeat(501), "  " + "x".repeat(501) + "  "}) {
            assertThatThrownBy(() -> service.removeRecipe(adminId, recipeId, bad))
                    .isInstanceOf(InvalidReviewReasonException.class);
        }

        verifyNoInteractions(recipeRepository, publisher);
    }

    @Test
    void removeRecipeAcceptsA500CharacterReason() {
        Recipe recipe = recipe(author, RecipeStatus.APPROVED);
        when(recipeRepository.findForReviewById(recipeId)).thenReturn(Optional.of(recipe));

        service.removeRecipe(adminId, recipeId, "x".repeat(500));

        verify(recipeRepository).delete(recipe);
    }

    // ---- remove comment ------------------------------------------------------------------------

    @Test
    void removeCommentDeletesItAndPublishesCapturedFields() {
        RecipeComment comment = comment(recipe(author, RecipeStatus.APPROVED));
        when(commentRepository.findById(commentId)).thenReturn(Optional.of(comment));

        service.removeComment(adminId, commentId, " Rude ");

        verify(commentRepository).delete(comment);
        ArgumentCaptor<CommentRemovedEvent> captor = ArgumentCaptor.forClass(CommentRemovedEvent.class);
        verify(publisher).publishEvent(captor.capture());
        CommentRemovedEvent event = captor.getValue();
        assertThat(event.commentId()).isEqualTo(commentId);
        assertThat(event.recipeTitle()).isEqualTo("Soup");
        assertThat(event.commentText()).isEqualTo("Too salty for me");
        assertThat(event.reason()).isEqualTo("Rude");
        assertThat(event.authorEmail()).isEqualTo("cy@example.test");
        assertThat(event.authorId()).isEqualTo(commenterId);
        assertThat(event.adminId()).isEqualTo(adminId);
    }

    @Test
    void removeCommentWorksWhateverTheRecipeStatus() {
        for (RecipeStatus status : RecipeStatus.values()) {
            RecipeCommentRepository repo = mock(RecipeCommentRepository.class);
            ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
            RecipeComment comment = comment(recipe(author, status));
            when(repo.findById(commentId)).thenReturn(Optional.of(comment));

            new AdminModerationService(recipeRepository, repo, events).removeComment(adminId, commentId, "Rude");

            verify(repo).delete(comment);
            verify(events).publishEvent(any(CommentRemovedEvent.class));
        }
    }

    @Test
    void removeMissingCommentIs404() {
        when(commentRepository.findById(commentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.removeComment(adminId, commentId, "Rude"))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(commentRepository, never()).delete(any(RecipeComment.class));
        verifyNoInteractions(publisher);
    }

    @Test
    void removeCommentRejectsBadReasonsBeforeTouchingAnything() {
        for (String bad : new String[]{null, "", "   ", "x".repeat(501)}) {
            assertThatThrownBy(() -> service.removeComment(adminId, commentId, bad))
                    .isInstanceOf(InvalidReviewReasonException.class);
        }

        verifyNoInteractions(commentRepository, publisher);
    }
}
