package mk.ukim.finki.wp.recipeappbackend.service;

import mk.ukim.finki.wp.recipeappbackend.cuisine.CuisineCatalog;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.CuisineInfoDto;
import mk.ukim.finki.wp.recipeappbackend.model.projection.CuisineCount;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CuisineServiceTest {

    private final CuisineCatalog catalog = new CuisineCatalog();
    private CuisineService service;

    private static CuisineCount count(String key, long count) {
        return new CuisineCount() {
            @Override
            public String getKey() {
                return key;
            }

            @Override
            public long getCount() {
                return count;
            }
        };
    }

    @BeforeEach
    void setUp() {
        RecipeRepository repo = mock(RecipeRepository.class);
        when(repo.countCuisines()).thenReturn(List.of(
                count("italian", 12), count("greek", 3), count("lol", 5)));
        service = new CuisineService(repo, catalog);
    }

    @Test
    void sortedByCountDescendingThenLabel() {
        List<CuisineInfoDto> result = service.listWithCounts();

        assertThat(result.get(0).key()).isEqualTo("italian");
        assertThat(result.get(0).count()).isEqualTo(12);
        assertThat(result.get(1).key()).isEqualTo("greek");
        assertThat(result.get(1).count()).isEqualTo(3);
        // zero-count tail is ordered by label ascending
        List<String> tailLabels = result.subList(2, result.size()).stream().map(CuisineInfoDto::label).toList();
        assertThat(tailLabels).isSortedAccordingTo(String::compareTo);
    }

    @Test
    void everyCatalogueEntryIsPresent() {
        List<CuisineInfoDto> result = service.listWithCounts();

        assertThat(result).hasSameSizeAs(catalog.all());
        assertThat(result.stream().map(CuisineInfoDto::key))
                .containsExactlyInAnyOrderElementsOf(catalog.all().stream().map(e -> e.key()).toList());
    }

    @Test
    void unknownDbKeysAreIgnored() {
        assertThat(service.listWithCounts()).noneMatch(c -> c.key().equals("lol"));
    }

    @Test
    void entriesWithoutCountsHaveZero() {
        List<CuisineInfoDto> result = service.listWithCounts();

        assertThat(result.subList(2, result.size())).allMatch(c -> c.count() == 0);
    }
}
