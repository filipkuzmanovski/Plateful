package mk.ukim.finki.wp.recipeappbackend.service;

import mk.ukim.finki.wp.recipeappbackend.nutrition.NutritionEstimates;
import mk.ukim.finki.wp.recipeappbackend.cuisine.CuisineCatalog;
import mk.ukim.finki.wp.recipeappbackend.cuisine.InvalidCuisineException;
import mk.ukim.finki.wp.recipeappbackend.exception.ResourceNotFoundException;
import mk.ukim.finki.wp.recipeappbackend.model.RecipeStatus;
import mk.ukim.finki.wp.recipeappbackend.model.createDTO.RecipeCreateDto;
import mk.ukim.finki.wp.recipeappbackend.model.createDTO.RecipeIngredientCreateDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.RecipeDisplayDto;
import mk.ukim.finki.wp.recipeappbackend.model.entities.Recipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.User;
import mk.ukim.finki.wp.recipeappbackend.photo.RecipePhotoPaths;
import mk.ukim.finki.wp.recipeappbackend.photo.RecipeVideoPaths;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeLikeRepository;
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
                new RecipeVideoPaths("https://x/recipe-videos"),
                visibility,
                mock(RecipeLikeRepository.class),
                mock(NutritionEstimates.class));
        owner = User.builder().id(userId).build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(owner));
        when(recipeRepository.save(any(Recipe.class))).thenAnswer(inv -> inv.getArgument(0));
        when(ingredientRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(photoRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static RecipeCreateDto dto(String title, List<String> photos, List<String> cuisines) {
        return new RecipeCreateDto(title, 2, null, null, null, null, null,
                false, false, false, false, cuisines, photos,
                List.of(new RecipeIngredientCreateDto("salt", "1 tsp salt")), null);
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
    void create_byAnyUser_isApprovedAtOnce() {
        service.create(userId, dto("Soup"));

        Recipe saved = savedByCreate();
        assertThat(saved.getStatus()).isEqualTo(RecipeStatus.APPROVED);
        assertThat(saved.isEverApproved()).isTrue();
        assertThat(saved.getApprovedTitle()).isEqualTo("Soup");
        assertThat(saved.getReviewedBy()).isNull();
        assertThat(saved.getReviewedAt()).isNotNull();
        assertThat(saved.getRejectionReason()).isNull();
    }

    @Test
    void createResponseCarriesTheApprovedReviewBlock() {
        RecipeDisplayDto result = service.create(userId, dto("Soup"));

        assertThat(result.review()).isNotNull();
        assertThat(result.review().status()).isEqualTo("approved");
        assertThat(result.review().everApproved()).isTrue();
        assertThat(result.review().hidden()).isFalse();
    }

    @Test
    void update_ofApprovedRecipe_staysApprovedAndFollowsTitle() {
        UUID recipeId = UUID.randomUUID();
        Recipe recipe = existing(recipeId, RecipeStatus.APPROVED);
        recipe.setEverApproved(true);
        recipe.setApprovedTitle("Old title");

        RecipeDisplayDto result = service.update(userId, recipeId, dto("Renamed"));

        assertThat(recipe.getStatus()).isEqualTo(RecipeStatus.APPROVED);
        assertThat(recipe.getApprovedTitle()).isEqualTo("Renamed");
        assertThat(recipe.getReviewedBy()).isNull();
        assertThat(result.review().status()).isEqualTo("approved");
        assertThat(result.review().revision()).isEqualTo(4);
    }

    @Test
    void update_ofRejectedRecipe_publishesIt() {
        UUID recipeId = UUID.randomUUID();
        Recipe recipe = existing(recipeId, RecipeStatus.REJECTED);
        recipe.setRejectionReason("Missing steps");

        service.update(userId, recipeId, dto("Fixed"));

        assertThat(recipe.getStatus()).isEqualTo(RecipeStatus.APPROVED);
        assertThat(recipe.isEverApproved()).isTrue();
        assertThat(recipe.getApprovedTitle()).isEqualTo("Fixed");
        assertThat(recipe.getRejectionReason()).isNull();
    }

    @Test
    void update_keepsHiddenAt() {
        UUID recipeId = UUID.randomUUID();
        Recipe recipe = existing(recipeId, RecipeStatus.APPROVED);
        recipe.setEverApproved(true);
        recipe.setHiddenAt(longAgo);

        RecipeDisplayDto result = service.update(userId, recipeId, dto("Edited"));

        assertThat(recipe.getHiddenAt()).isEqualTo(longAgo);
        assertThat(result.review().hidden()).isTrue();
    }

    @Test
    void update_ofHiddenRecipe_keepsApprovedTitle() {
        UUID recipeId = UUID.randomUUID();
        Recipe recipe = existing(recipeId, RecipeStatus.APPROVED);
        recipe.setEverApproved(true);
        recipe.setApprovedTitle("Old title");
        recipe.setHiddenAt(longAgo);

        service.update(userId, recipeId, dto("Renamed"));

        assertThat(recipe.getApprovedTitle()).isEqualTo("Old title");
        assertThat(recipe.getTitle()).isEqualTo("Renamed");
    }

    @Test
    void update_bumpsRevision() {
        UUID recipeId = UUID.randomUUID();
        Recipe recipe = existing(recipeId, RecipeStatus.APPROVED);

        service.update(userId, recipeId, dto("Same", List.of(p1), null));

        assertThat(recipe.getRevision()).isEqualTo(4);
        assertThat(recipe.getSubmittedAt()).isEqualTo(longAgo);
    }

    @Test
    void rejectedEditLeavesRevisionAndStatusUntouched() {
        UUID recipeId = UUID.randomUUID();
        Recipe recipe = existing(recipeId, RecipeStatus.REJECTED);

        assertThatThrownBy(() -> service.update(userId, recipeId, dto("Bad", List.of(), List.of("Atlantis"))))
                .isInstanceOf(InvalidCuisineException.class);

        assertThat(recipe.getRevision()).isEqualTo(3);
        assertThat(recipe.getStatus()).isEqualTo(RecipeStatus.REJECTED);
    }
}
