package mk.ukim.finki.wp.recipeappbackend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import mk.ukim.finki.wp.recipeappbackend.cuisine.CuisineCatalog;
import mk.ukim.finki.wp.recipeappbackend.model.RecipeStatus;
import mk.ukim.finki.wp.recipeappbackend.model.createDTO.RecipeCreateDto;
import mk.ukim.finki.wp.recipeappbackend.model.createDTO.RecipeIngredientCreateDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.NutritionEstimateDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.RecipeDisplayDto;
import mk.ukim.finki.wp.recipeappbackend.model.entities.Recipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.User;
import mk.ukim.finki.wp.recipeappbackend.nutrition.NutritionEstimates;
import mk.ukim.finki.wp.recipeappbackend.photo.RecipePhotoPaths;
import mk.ukim.finki.wp.recipeappbackend.photo.RecipeVideoPaths;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeCommentRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeIngredientRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeLikeRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipePhotoRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeRatingRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;

class RecipeServiceEstimateTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID recipeId = UUID.randomUUID();

    private RecipeVisibility visibility;
    private NutritionEstimates estimates;
    private RecipeService service;
    private Recipe recipe;

    @BeforeEach
    void setUp() {
        RecipeRepository recipeRepository = mock(RecipeRepository.class);
        RecipeIngredientRepository ingredientRepository = mock(RecipeIngredientRepository.class);
        RecipePhotoRepository photoRepository = mock(RecipePhotoRepository.class);
        visibility = mock(RecipeVisibility.class);
        estimates = mock(NutritionEstimates.class);
        service = new RecipeService(recipeRepository, ingredientRepository, mock(RecipeRatingRepository.class),
                mock(RecipeCommentRepository.class), mock(UserRepository.class), new CuisineCatalog(),
                photoRepository, new RecipePhotoPaths("https://x/recipe-photos"),
                new RecipeVideoPaths("https://x/recipe-videos"), visibility, mock(RecipeLikeRepository.class),
                estimates);
        User owner = User.builder().id(userId).build();
        recipe = Recipe.builder().id(recipeId).user(owner).title("Soup").status(RecipeStatus.APPROVED)
                .servings(4).build();
        when(recipeRepository.findById(recipeId)).thenReturn(Optional.of(recipe));
        when(recipeRepository.findForReviewById(recipeId)).thenReturn(Optional.of(recipe));
        when(ingredientRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(photoRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void update_removesAnEstimateThroughTheNewIngredients() {
        RecipeCreateDto dto = new RecipeCreateDto("Soup", 4, null, null, null, null, null, false, false, false,
                false, null, null, List.of(new RecipeIngredientCreateDto("flour", "2 cups flour")), null);

        service.update(userId, recipeId, dto);

        verify(estimates).removeIfIngredientsChanged(eq(recipeId), argThat(list -> list.size() == 1
                && "flour".equals(list.get(0).getIngredientName())
                && "2 cups flour".equals(list.get(0).getOriginalText())));
    }

    @Test
    void getById_carriesTheEstimate() {
        when(estimates.forRecipe(recipe)).thenReturn(NutritionEstimateDto.pending());

        assertThat(service.getById(recipeId, userId).estimate().status()).isEqualTo("pending");
    }

    @Test
    void withReview_keepsTheEstimate() {
        NutritionEstimateDto estimate = new NutritionEstimateDto("ready", 450, 28, 18, 42);
        when(estimates.forRecipe(recipe)).thenReturn(estimate);
        when(visibility.showsReview(recipe, userId)).thenReturn(true);

        RecipeDisplayDto dto = service.getById(recipeId, userId);

        assertThat(dto.review()).isNotNull();
        assertThat(dto.estimate()).isEqualTo(estimate);
    }

    @Test
    void browse_leavesTheEstimateNull() {
        Page<RecipeDisplayDto> page = service.toDisplayPage(new PageImpl<>(List.of(recipe)));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).estimate()).isNull();
        verify(estimates, never()).forRecipe(any());
    }
}
