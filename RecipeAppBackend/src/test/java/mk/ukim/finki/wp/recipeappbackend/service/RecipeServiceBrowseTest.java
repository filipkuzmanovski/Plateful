package mk.ukim.finki.wp.recipeappbackend.service;

import mk.ukim.finki.wp.recipeappbackend.cuisine.CuisineCatalog;
import mk.ukim.finki.wp.recipeappbackend.model.RecipeFilter;
import mk.ukim.finki.wp.recipeappbackend.model.RecipeSort;
import mk.ukim.finki.wp.recipeappbackend.model.RecipeStatus;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.RecipeDisplayDto;
import mk.ukim.finki.wp.recipeappbackend.model.entities.Recipe;
import mk.ukim.finki.wp.recipeappbackend.photo.RecipePhotoPaths;
import mk.ukim.finki.wp.recipeappbackend.photo.RecipeVideoPaths;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecipeServiceBrowseTest {

    private RecipeRepository recipeRepository;
    private RecipeService service;
    private RecipeVisibility visibility;
    private final Pageable pageable = PageRequest.of(0, 12);

    @BeforeEach
    void setUp() {
        recipeRepository = mock(RecipeRepository.class);
        visibility = mock(RecipeVisibility.class);
        service = new RecipeService(
                recipeRepository,
                mock(RecipeIngredientRepository.class),
                mock(RecipeRatingRepository.class),
                mock(RecipeCommentRepository.class),
                mock(UserRepository.class),
                new CuisineCatalog(),
                mock(RecipePhotoRepository.class),
                new RecipePhotoPaths("https://x/recipe-photos"),
                new RecipeVideoPaths("https://x/recipe-videos"),
                visibility);
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

    @Test
    void getByAuthorBranchesOnViewer() {
        UUID authorId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        when(visibility.isAdmin(adminId)).thenReturn(true);
        Recipe pending = Recipe.builder().id(UUID.randomUUID()).title("P")
                .status(RecipeStatus.PENDING).revision(2).build();
        Recipe approved = Recipe.builder().id(UUID.randomUUID()).title("A")
                .status(RecipeStatus.APPROVED).build();
        Page<Recipe> all = new PageImpl<>(List.of(pending, approved), pageable, 2);
        Page<Recipe> approvedOnly = new PageImpl<>(List.of(approved), pageable, 1);
        when(recipeRepository.findByUserId(authorId, pageable)).thenReturn(all);
        when(recipeRepository.findByUserIdAndStatus(authorId, RecipeStatus.APPROVED, pageable))
                .thenReturn(approvedOnly);

        for (UUID viewer : new UUID[]{authorId, adminId}) {
            Page<RecipeDisplayDto> page = service.getByAuthor(authorId, viewer, pageable);

            assertThat(page.getContent()).hasSize(2);
            assertThat(page.getContent()).allSatisfy(dto -> assertThat(dto.review()).isNotNull());
            assertThat(page.getContent().get(0).review().status()).isEqualTo("pending");
            assertThat(page.getContent().get(0).review().revision()).isEqualTo(2);
            assertThat(page.getContent().get(1).review().status()).isEqualTo("approved");
        }
        verify(recipeRepository, never())
                .findByUserIdAndStatus(any(), any(), any());

        for (UUID viewer : new UUID[]{otherId, null}) {
            Page<RecipeDisplayDto> page = service.getByAuthor(authorId, viewer, pageable);

            assertThat(page.getContent()).hasSize(1);
            assertThat(page.getContent().get(0).review()).isNull();
        }
        verify(recipeRepository, times(2))
                .findByUserIdAndStatus(eq(authorId), eq(RecipeStatus.APPROVED), eq(pageable));
        verify(recipeRepository, times(2)).findByUserId(authorId, pageable);
    }
}
