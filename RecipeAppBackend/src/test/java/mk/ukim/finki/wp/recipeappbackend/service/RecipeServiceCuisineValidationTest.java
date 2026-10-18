package mk.ukim.finki.wp.recipeappbackend.service;

import mk.ukim.finki.wp.recipeappbackend.cuisine.CuisineCatalog;
import mk.ukim.finki.wp.recipeappbackend.cuisine.InvalidCuisineException;
import mk.ukim.finki.wp.recipeappbackend.model.createDTO.RecipeCreateDto;
import mk.ukim.finki.wp.recipeappbackend.model.createDTO.RecipeIngredientCreateDto;
import mk.ukim.finki.wp.recipeappbackend.model.entities.Recipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.User;
import mk.ukim.finki.wp.recipeappbackend.photo.RecipePhotoPaths;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeCommentRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipePhotoRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeIngredientRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeRatingRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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

class RecipeServiceCuisineValidationTest {

    private final UUID userId = UUID.randomUUID();
    private final CuisineCatalog catalog = new CuisineCatalog();
    private RecipeRepository recipeRepository;
    private RecipeIngredientRepository ingredientRepository;
    private UserRepository userRepository;
    private RecipeService service;
    private User owner;

    @BeforeEach
    void setUp() {
        recipeRepository = mock(RecipeRepository.class);
        ingredientRepository = mock(RecipeIngredientRepository.class);
        userRepository = mock(UserRepository.class);
        service = new RecipeService(
                recipeRepository,
                ingredientRepository,
                mock(RecipeRatingRepository.class),
                mock(RecipeCommentRepository.class),
                userRepository,
                catalog,
                mock(RecipePhotoRepository.class),
                new RecipePhotoPaths("https://x/recipe-photos"),
                mock(RecipeVisibility.class));
        owner = User.builder().id(userId).build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(owner));
        when(recipeRepository.save(any(Recipe.class))).thenAnswer(inv -> inv.getArgument(0));
        when(ingredientRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static RecipeCreateDto dto(List<String> cuisines) {
        return new RecipeCreateDto("Title", 2, null, null, null, null, null,
                false, false, false, false, cuisines, null,
                List.of(new RecipeIngredientCreateDto("salt", "1 tsp salt")));
    }

    @Test
    void createNormalizesAndDedupes() {
        service.create(userId, dto(List.of("  Italian ", "ITALIAN", "greek")));

        ArgumentCaptor<Recipe> captor = ArgumentCaptor.forClass(Recipe.class);
        verify(recipeRepository).save(captor.capture());
        assertThat(captor.getValue().getCuisines()).containsExactly("italian", "greek");
    }

    @Test
    void createRejectsUnknown() {
        assertThatThrownBy(() -> service.create(userId, dto(List.of("italian", "lol"))))
                .isInstanceOf(InvalidCuisineException.class)
                .hasMessageContaining("lol");

        verify(recipeRepository, never()).save(any(Recipe.class));
    }

    @Test
    void updateRejectsUnknown() {
        UUID recipeId = UUID.randomUUID();
        Recipe existing = Recipe.builder().id(recipeId).user(owner).title("Old").build();
        when(recipeRepository.findById(recipeId)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.update(userId, recipeId, dto(List.of("italian", "lol"))))
                .isInstanceOf(InvalidCuisineException.class)
                .hasMessageContaining("lol");

        verify(ingredientRepository, never()).deleteByRecipeId(any());
    }

    @Test
    void moreThanTenAfterDedupeRejected() {
        List<String> eleven = catalog.all().stream().limit(11).map(e -> e.key()).toList();
        assertThat(eleven).hasSize(11);

        assertThatThrownBy(() -> service.create(userId, dto(eleven)))
                .isInstanceOf(InvalidCuisineException.class)
                .hasMessageContaining("At most 10");

        verify(recipeRepository, never()).save(any(Recipe.class));
    }
}
