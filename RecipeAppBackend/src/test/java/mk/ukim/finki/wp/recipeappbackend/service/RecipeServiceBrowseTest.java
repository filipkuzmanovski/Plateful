package mk.ukim.finki.wp.recipeappbackend.service;

import mk.ukim.finki.wp.recipeappbackend.cuisine.CuisineCatalog;
import mk.ukim.finki.wp.recipeappbackend.model.RecipeFilter;
import mk.ukim.finki.wp.recipeappbackend.model.RecipeSort;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.RecipeDisplayDto;
import mk.ukim.finki.wp.recipeappbackend.model.entities.Recipe;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecipeServiceBrowseTest {

    private RecipeRepository recipeRepository;
    private RecipeService service;
    private final Pageable pageable = PageRequest.of(0, 12);

    @BeforeEach
    void setUp() {
        recipeRepository = mock(RecipeRepository.class);
        service = new RecipeService(
                recipeRepository,
                mock(RecipeIngredientRepository.class),
                mock(RecipeRatingRepository.class),
                mock(RecipeCommentRepository.class),
                mock(UserRepository.class),
                new CuisineCatalog(),
                mock(RecipePhotoRepository.class),
                new RecipePhotoPaths("https://x/recipe-photos"));
    }

    private static RecipeFilter cuisineFilter(String... cuisines) {
        return new RecipeFilter(List.of(cuisines), null, null, null, null, null, null, null, null, null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void onlyUnknownCuisinesReturnsEmptyPageWithoutQuerying() {
        Page<RecipeDisplayDto> result = service.browse(cuisineFilter("lol"), RecipeSort.NEWEST, pageable);

        assertThat(result.getContent()).isEmpty();
        verify(recipeRepository, never()).findAll(any(Specification.class), any(Pageable.class));
        verify(recipeRepository, never()).findAll(any(Pageable.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void knownCuisineQueriesWithSpecification() {
        when(recipeRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<Recipe>(List.of(), pageable, 0));

        service.browse(cuisineFilter("italian"), RecipeSort.NEWEST, pageable);

        verify(recipeRepository, times(1)).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void emptyFilterStillUsesSpecificationPath() {
        when(recipeRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<Recipe>(List.of(), pageable, 0));

        service.browse(new RecipeFilter(null, null, null, null, null, null, null, null, null, null),
                RecipeSort.MOST_RATED, pageable);

        verify(recipeRepository, times(1)).findAll(any(Specification.class), any(Pageable.class));
        verify(recipeRepository, never()).findAll(any(Pageable.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void pageRequestIsUnsorted() {
        when(recipeRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<Recipe>(List.of(), pageable, 0));
        Pageable sorted = PageRequest.of(2, 12, org.springframework.data.domain.Sort.by("title"));

        service.browse(null, RecipeSort.TOP_RATED, sorted);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(recipeRepository).findAll(any(Specification.class), captor.capture());
        assertThat(captor.getValue().getSort().isUnsorted()).isTrue();
        assertThat(captor.getValue().getPageNumber()).isEqualTo(2);
        assertThat(captor.getValue().getPageSize()).isEqualTo(12);
    }
}
