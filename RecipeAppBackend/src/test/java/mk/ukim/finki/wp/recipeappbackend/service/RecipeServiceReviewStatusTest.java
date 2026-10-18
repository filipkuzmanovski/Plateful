package mk.ukim.finki.wp.recipeappbackend.service;

import mk.ukim.finki.wp.recipeappbackend.cuisine.CuisineCatalog;
import mk.ukim.finki.wp.recipeappbackend.cuisine.InvalidCuisineException;
import mk.ukim.finki.wp.recipeappbackend.exception.ResourceNotFoundException;
import mk.ukim.finki.wp.recipeappbackend.model.RecipeStatus;
import mk.ukim.finki.wp.recipeappbackend.model.createDTO.RecipeCreateDto;
import mk.ukim.finki.wp.recipeappbackend.model.createDTO.RecipeIngredientCreateDto;
import mk.ukim.finki.wp.recipeappbackend.model.entities.Recipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.User;
import mk.ukim.finki.wp.recipeappbackend.photo.RecipePhotoPaths;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeCommentRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeIngredientRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipePhotoRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeRatingRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecipeServiceReviewStatusTest {

    private static final String BASE = "https://x/recipe-photos";

    private final UUID userId = UUID.randomUUID();
    private final RecipePhotoPaths paths = new RecipePhotoPaths(BASE);
    private final String p1 = userId + "/" + UUID.randomUUID() + ".webp";
    private final OffsetDateTime longAgo = OffsetDateTime.now().minusDays(3);

    private RecipeRepository recipeRepository;
    private RecipeVisibility visibility;
    private RecipeService service;
    private User owner;

    @BeforeEach
    void setUp() {
        recipeRepository = mock(RecipeRepository.class);
        RecipeIngredientRepository ingredientRepository = mock(RecipeIngredientRepository.class);
        RecipePhotoRepository photoRepository = mock(RecipePhotoRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        visibility = mock(RecipeVisibility.class);
        service = new RecipeService(
                recipeRepository,
                ingredientRepository,
                mock(RecipeRatingRepository.class),
                mock(RecipeCommentRepository.class),
                userRepository,
                new CuisineCatalog(),
                photoRepository,
                paths,
                visibility);
        owner = User.builder().id(userId).build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(owner));
        when(recipeRepository.save(any(Recipe.class))).thenAnswer(inv -> inv.getArgument(0));
        when(ingredientRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(photoRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static RecipeCreateDto dto(String title, List<String> photos, List<String> cuisines) {
        return new RecipeCreateDto(title, 2, null, null, null, null, null,
                false, false, false, false, cuisines, photos,
                List.of(new RecipeIngredientCreateDto("salt", "1 tsp salt")));
    }

    private static RecipeCreateDto dto(String title) {
        return dto(title, List.of(), null);
    }

    private Recipe existing(UUID recipeId, RecipeStatus status) {
        Recipe r = Recipe.builder().id(recipeId).user(owner).title("Old title")
                .status(status).submittedAt(longAgo).revision(3).build();
        when(recipeRepository.findForReviewById(recipeId)).thenReturn(Optional.of(r));
        return r;
    }

    @Test
    void updateLoadsWithRowLock() {
        UUID recipeId = UUID.randomUUID();
        existing(recipeId, RecipeStatus.PENDING);

        service.update(userId, recipeId, dto("Locked edit"));

        // The PESSIMISTIC_WRITE load is shared with approve/reject; a plain findById would race a review.
        verify(recipeRepository).findForReviewById(recipeId);
        verify(recipeRepository, never()).findById(recipeId);
    }

    @Test
    void deleteLoadsWithRowLock() {
        UUID recipeId = UUID.randomUUID();
        Recipe recipe = existing(recipeId, RecipeStatus.PENDING);

        service.delete(userId, recipeId);

        verify(recipeRepository).findForReviewById(recipeId);
        verify(recipeRepository, never()).findById(recipeId);
        verify(recipeRepository).delete(recipe);
    }

    @Test
    void updateOfMissingRecipeIs404() {
        UUID recipeId = UUID.randomUUID();
        when(recipeRepository.findForReviewById(recipeId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(userId, recipeId, dto("Nope")))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.delete(userId, recipeId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private Recipe savedByCreate() {
        ArgumentCaptor<Recipe> captor = ArgumentCaptor.forClass(Recipe.class);
        verify(recipeRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void userCreateIsPending() {
        when(visibility.isAdmin(userId)).thenReturn(false);

        service.create(userId, dto("Soup"));

        Recipe saved = savedByCreate();
        assertThat(saved.getStatus()).isEqualTo(RecipeStatus.PENDING);
        assertThat(saved.getSubmittedAt()).isNotNull();
        assertThat(saved.isEverApproved()).isFalse();
        assertThat(saved.getApprovedTitle()).isNull();
        assertThat(saved.getReviewedBy()).isNull();
    }

    @Test
    void adminCreateIsApproved() {
        when(visibility.isAdmin(userId)).thenReturn(true);

        service.create(userId, dto("Soup"));

        Recipe saved = savedByCreate();
        assertThat(saved.getStatus()).isEqualTo(RecipeStatus.APPROVED);
        assertThat(saved.isEverApproved()).isTrue();
        assertThat(saved.getApprovedTitle()).isEqualTo("Soup");
        assertThat(saved.getReviewedBy()).isSameAs(owner);
        assertThat(saved.getReviewedAt()).isNotNull();
        assertThat(saved.getRejectionReason()).isNull();
    }

    @Test
    void editApprovedGoesPending() {
        UUID recipeId = UUID.randomUUID();
        Recipe recipe = existing(recipeId, RecipeStatus.APPROVED);
        recipe.setEverApproved(true);
        recipe.setApprovedTitle("Approved title");
        when(visibility.isAdmin(userId)).thenReturn(false);

        service.update(userId, recipeId, dto("New title"));

        assertThat(recipe.getStatus()).isEqualTo(RecipeStatus.PENDING);
        assertThat(recipe.getSubmittedAt()).isAfter(longAgo);
        assertThat(recipe.getRevision()).isEqualTo(4);
        assertThat(recipe.isEverApproved()).isTrue();
        assertThat(recipe.getApprovedTitle()).isEqualTo("Approved title");
        assertThat(recipe.getTitle()).isEqualTo("New title");
    }

    @Test
    void editRejectedGoesPendingAndClearsReason() {
        UUID recipeId = UUID.randomUUID();
        Recipe recipe = existing(recipeId, RecipeStatus.REJECTED);
        recipe.setRejectionReason("Missing steps");
        when(visibility.isAdmin(userId)).thenReturn(false);

        service.update(userId, recipeId, dto("Fixed"));

        assertThat(recipe.getStatus()).isEqualTo(RecipeStatus.PENDING);
        assertThat(recipe.getRejectionReason()).isNull();
        assertThat(recipe.getSubmittedAt()).isAfter(longAgo);
        assertThat(recipe.getRevision()).isEqualTo(4);
    }

    @Test
    void editWhilePendingKeepsQueuePosition() {
        UUID recipeId = UUID.randomUUID();
        Recipe recipe = existing(recipeId, RecipeStatus.PENDING);
        when(visibility.isAdmin(userId)).thenReturn(false);

        service.update(userId, recipeId, dto("Tweaked"));

        assertThat(recipe.getStatus()).isEqualTo(RecipeStatus.PENDING);
        assertThat(recipe.getSubmittedAt()).isEqualTo(longAgo);
        assertThat(recipe.getRevision()).isEqualTo(4);
    }

    @Test
    void photoOnlyEditBumpsRevision() {
        UUID recipeId = UUID.randomUUID();
        Recipe recipe = existing(recipeId, RecipeStatus.PENDING);
        recipe.setTitle("Same");
        when(visibility.isAdmin(userId)).thenReturn(false);

        service.update(userId, recipeId, dto("Same", List.of(p1), null));

        assertThat(recipe.getRevision()).isEqualTo(4);
    }

    @Test
    void adminEditStaysApproved() {
        UUID recipeId = UUID.randomUUID();
        Recipe recipe = existing(recipeId, RecipeStatus.APPROVED);
        recipe.setEverApproved(true);
        recipe.setApprovedTitle("Old title");
        when(visibility.isAdmin(userId)).thenReturn(true);

        service.update(userId, recipeId, dto("Renamed"));

        assertThat(recipe.getStatus()).isEqualTo(RecipeStatus.APPROVED);
        assertThat(recipe.getRevision()).isEqualTo(4);
        assertThat(recipe.getApprovedTitle()).isEqualTo("Renamed");
        assertThat(recipe.getReviewedBy()).isSameAs(owner);
        assertThat(recipe.getReviewedAt()).isNotNull();
    }

    @Test
    void rejectedEditLeavesRevisionAndStatusUntouched() {
        UUID recipeId = UUID.randomUUID();
        Recipe recipe = existing(recipeId, RecipeStatus.APPROVED);
        recipe.setEverApproved(true);
        when(visibility.isAdmin(userId)).thenReturn(false);

        assertThatThrownBy(() -> service.update(userId, recipeId, dto("Bad", List.of(), List.of("Atlantis"))))
                .isInstanceOf(InvalidCuisineException.class);

        assertThat(recipe.getRevision()).isEqualTo(3);
        assertThat(recipe.getStatus()).isEqualTo(RecipeStatus.APPROVED);
        assertThat(recipe.getSubmittedAt()).isEqualTo(longAgo);
    }
}
