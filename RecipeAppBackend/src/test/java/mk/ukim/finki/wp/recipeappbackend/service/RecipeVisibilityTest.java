package mk.ukim.finki.wp.recipeappbackend.service;

import mk.ukim.finki.wp.recipeappbackend.exception.RecipeHiddenException;
import mk.ukim.finki.wp.recipeappbackend.exception.RecipeNotApprovedException;
import mk.ukim.finki.wp.recipeappbackend.exception.ResourceNotFoundException;
import mk.ukim.finki.wp.recipeappbackend.model.RecipeStatus;
import mk.ukim.finki.wp.recipeappbackend.model.entities.Recipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.User;
import mk.ukim.finki.wp.recipeappbackend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RecipeVisibilityTest {

    private final UUID authorId = UUID.randomUUID();
    private final UUID otherId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();

    private UserRepository userRepository;
    private RecipeVisibility visibility;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        when(userRepository.isAdmin(adminId)).thenReturn(true);
        visibility = new RecipeVisibility(userRepository);
    }

    private Recipe recipe(RecipeStatus status, boolean everApproved, UUID owner) {
        return Recipe.builder()
                .id(UUID.randomUUID())
                .title("New Title")
                .status(status)
                .everApproved(everApproved)
                .approvedTitle(everApproved ? "Old Title" : null)
                .user(owner == null ? null : User.builder().id(owner).build())
                .build();
    }

    @Test
    void isAdminIsFalseForNullWithoutQuerying() {
        assertThat(visibility.isAdmin(null)).isFalse();
        verifyNoInteractions(userRepository);
    }

    @Test
    void isAdminReadsTheFlag() {
        assertThat(visibility.isAdmin(adminId)).isTrue();
        assertThat(visibility.isAdmin(otherId)).isFalse();
    }

    @Test
    void approvedIsVisibleToEveryone() {
        Recipe r = recipe(RecipeStatus.APPROVED, true, authorId);

        assertThat(visibility.canSee(r, null)).isTrue();
        assertThat(visibility.canSee(r, otherId)).isTrue();
        assertThat(visibility.canSee(r, authorId)).isTrue();
        assertThat(visibility.canSee(r, adminId)).isTrue();
        assertThatCode(() -> visibility.requireVisible(r, null)).doesNotThrowAnyException();
    }

    @Test
    void pendingAndRejectedAreVisibleToAuthorAndAdminOnly() {
        for (RecipeStatus status : new RecipeStatus[]{RecipeStatus.PENDING, RecipeStatus.REJECTED}) {
            Recipe r = recipe(status, false, authorId);

            assertThat(visibility.canSee(r, authorId)).as("author %s", status).isTrue();
            assertThat(visibility.canSee(r, adminId)).as("admin %s", status).isTrue();
            assertThat(visibility.canSee(r, otherId)).as("other %s", status).isFalse();
            assertThat(visibility.canSee(r, null)).as("anonymous %s", status).isFalse();
        }
    }

    @Test
    void authorDoesNotNeedAnAdminLookup() {
        visibility.canSee(recipe(RecipeStatus.PENDING, false, authorId), authorId);

        verify(userRepository, never()).isAdmin(authorId);
    }

    @Test
    void authorlessPendingVisibleOnlyToAdmin() {
        Recipe r = recipe(RecipeStatus.PENDING, false, null);

        assertThat(visibility.canSee(r, adminId)).isTrue();
        assertThat(visibility.canSee(r, otherId)).isFalse();
        assertThat(visibility.canSee(r, null)).isFalse();
    }

    @Test
    void hiddenNeverApprovedIsAPlain404() {
        Recipe r = recipe(RecipeStatus.PENDING, false, authorId);

        assertThatThrownBy(() -> visibility.requireVisible(r, otherId))
                .isExactlyInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> visibility.requireVisible(r, null))
                .isExactlyInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void hiddenEverApprovedPendingCarriesTitleAndStatus() {
        Recipe r = recipe(RecipeStatus.PENDING, true, authorId);

        assertThatThrownBy(() -> visibility.requireVisible(r, otherId))
                .isInstanceOfSatisfying(RecipeHiddenException.class, ex -> {
                    assertThat(ex.getRecipeId()).isEqualTo(r.getId());
                    assertThat(ex.getApprovedTitle()).isEqualTo("Old Title");
                    assertThat(ex.getStatus()).isEqualTo(RecipeStatus.PENDING);
                });
    }

    @Test
    void hiddenEverApprovedRejectedCarriesRejectedStatus() {
        Recipe r = recipe(RecipeStatus.REJECTED, true, authorId);

        assertThatThrownBy(() -> visibility.requireVisible(r, null))
                .isInstanceOfSatisfying(RecipeHiddenException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(RecipeStatus.REJECTED));
    }

    @Test
    void hiddenEverApprovedIsVisibleToAuthorAndAdmin() {
        Recipe r = recipe(RecipeStatus.PENDING, true, authorId);

        assertThatCode(() -> visibility.requireVisible(r, authorId)).doesNotThrowAnyException();
        assertThatCode(() -> visibility.requireVisible(r, adminId)).doesNotThrowAnyException();
    }

    @Test
    void interactionNeedsAnApprovedRecipe() {
        Recipe approved = recipe(RecipeStatus.APPROVED, true, authorId);

        assertThatCode(() -> visibility.requireApprovedForInteraction(approved, otherId)).doesNotThrowAnyException();
        assertThatCode(() -> visibility.requireApprovedForInteraction(approved, adminId)).doesNotThrowAnyException();
    }

    @Test
    void interactionOnHiddenRecipeThrowsTheVisibilityExceptions() {
        Recipe neverApproved = recipe(RecipeStatus.PENDING, false, authorId);
        Recipe editedAfterApproval = recipe(RecipeStatus.PENDING, true, authorId);

        assertThatThrownBy(() -> visibility.requireApprovedForInteraction(neverApproved, otherId))
                .isExactlyInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> visibility.requireApprovedForInteraction(editedAfterApproval, null))
                .isExactlyInstanceOf(RecipeHiddenException.class);
    }

    @Test
    void interactionOnVisibleButUnapprovedRecipeIs409ForAuthorAndAdmin() {
        for (RecipeStatus status : new RecipeStatus[]{RecipeStatus.PENDING, RecipeStatus.REJECTED}) {
            Recipe r = recipe(status, true, authorId);

            assertThatThrownBy(() -> visibility.requireApprovedForInteraction(r, authorId))
                    .as("author %s", status).isExactlyInstanceOf(RecipeNotApprovedException.class);
            assertThatThrownBy(() -> visibility.requireApprovedForInteraction(r, adminId))
                    .as("admin %s", status).isExactlyInstanceOf(RecipeNotApprovedException.class);
        }
    }

    @Test
    void showsReviewForAuthorAndAdminOnly() {
        Recipe r = recipe(RecipeStatus.APPROVED, true, authorId);

        assertThat(visibility.showsReview(r, authorId)).isTrue();
        assertThat(visibility.showsReview(r, adminId)).isTrue();
        assertThat(visibility.showsReview(r, otherId)).isFalse();
        assertThat(visibility.showsReview(r, null)).isFalse();
    }

    @Test
    void authorlessRecipeShowsReviewOnlyToAdmin() {
        Recipe r = recipe(RecipeStatus.APPROVED, false, null);

        assertThat(visibility.showsReview(r, adminId)).isTrue();
        assertThat(visibility.showsReview(r, otherId)).isFalse();
    }
}
