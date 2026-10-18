package mk.ukim.finki.wp.recipeappbackend.service;

import mk.ukim.finki.wp.recipeappbackend.cuisine.CuisineCatalog;
import mk.ukim.finki.wp.recipeappbackend.exception.ResourceNotFoundException;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.RecipeDisplayDto;
import mk.ukim.finki.wp.recipeappbackend.model.entities.Recipe;
import mk.ukim.finki.wp.recipeappbackend.photo.RecipePhotoPaths;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeCommentRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeIngredientRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipePhotoRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeRatingRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.SavedRecipeRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SavedRecipeServiceTest {

    private RecipeRepository recipeRepository;
    private SavedRecipeRepository savedRepository;
    private SavedRecipeService service;

    private final UUID user = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        recipeRepository = mock(RecipeRepository.class);
        savedRepository = mock(SavedRecipeRepository.class);
        // A real RecipeService over mocked repositories, so list() really assembles DTOs.
        RecipeService recipeService = new RecipeService(
                recipeRepository,
                mock(RecipeIngredientRepository.class),
                mock(RecipeRatingRepository.class),
                mock(RecipeCommentRepository.class),
                mock(UserRepository.class),
                new CuisineCatalog(),
                mock(RecipePhotoRepository.class),
                new RecipePhotoPaths("https://x/recipe-photos"),
                mock(RecipeVisibility.class));
        service = new SavedRecipeService(savedRepository, recipeRepository, recipeService);
    }

    @Test
    void saveMissingRecipeThrows404() {
        UUID recipeId = UUID.randomUUID();
        when(recipeRepository.existsById(recipeId)).thenReturn(false);

        assertThatThrownBy(() -> service.save(user, recipeId))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(savedRepository, never()).insertIgnore(user, recipeId);
    }

    @Test
    void saveCallsInsertIgnore() {
        UUID recipeId = UUID.randomUUID();
        when(recipeRepository.existsById(recipeId)).thenReturn(true);

        service.save(user, recipeId);

        verify(savedRepository).insertIgnore(user, recipeId);
    }

    @Test
    void unsaveDeletes() {
        UUID recipeId = UUID.randomUUID();

        service.unsave(user, recipeId);

        verify(savedRepository).deleteByIdUserIdAndIdRecipeId(user, recipeId);
    }

    @Test
    void listKeepsSavedOrderAndSkipsMissing() {
        UUID r1 = UUID.randomUUID();
        UUID r3 = UUID.randomUUID();
        UUID gone = UUID.randomUUID();
        // A full page (size 3), so PageImpl keeps the id page's total instead of trimming it.
        Pageable pageable = PageRequest.of(0, 3);
        when(savedRepository.findRecipeIdsByUserId(user, pageable))
                .thenReturn(new PageImpl<>(List.of(r3, r1, gone), pageable, 3));
        // Returned in a different order than the saved order, and without the deleted recipe.
        when(recipeRepository.findAllById(List.of(r3, r1, gone))).thenReturn(List.of(
                Recipe.builder().id(r1).title("One").build(),
                Recipe.builder().id(r3).title("Three").build()));

        Page<RecipeDisplayDto> result = service.list(user, pageable);

        assertThat(result.getContent()).extracting(RecipeDisplayDto::id).containsExactly(r3, r1);
        assertThat(result.getTotalElements()).isEqualTo(3);
    }

    @Test
    void listUsesAnUnsortedPageRequest() {
        Pageable sorted = PageRequest.of(2, 5, Sort.by("title"));
        Pageable unsorted = PageRequest.of(2, 5);
        when(savedRepository.findRecipeIdsByUserId(user, unsorted))
                .thenReturn(new PageImpl<>(List.of(), unsorted, 0));

        service.list(user, sorted);

        // The repository query owns the ordering; a client-supplied Sort must never reach it.
        verify(savedRepository).findRecipeIdsByUserId(user, unsorted);
    }

    @Test
    void idsReturnsOnlyCallersIds() {
        UUID a = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        when(savedRepository.findAllRecipeIdsByUserId(user)).thenReturn(List.of(a));

        assertThat(service.ids(user)).containsExactly(a);
        verify(savedRepository).findAllRecipeIdsByUserId(user);
        verify(savedRepository, never()).findAllRecipeIdsByUserId(other);
    }
}
