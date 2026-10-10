package mk.ukim.finki.wp.recipeappbackend.service;

import mk.ukim.finki.wp.recipeappbackend.cuisine.CuisineCatalog;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.CuisineInfoDto;
import mk.ukim.finki.wp.recipeappbackend.model.projection.CuisineCount;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class CuisineService {

    private final RecipeRepository recipeRepository;
    private final CuisineCatalog catalog;

    public CuisineService(RecipeRepository recipeRepository, CuisineCatalog catalog) {
        this.recipeRepository = recipeRepository;
        this.catalog = catalog;
    }

    /** Every catalogue cuisine with its live recipe count (0 if none), most used first. */
    @Transactional(readOnly = true)
    public List<CuisineInfoDto> listWithCounts() {
        Map<String, Long> counts = new HashMap<>();
        for (CuisineCount c : recipeRepository.countCuisines()) {
            counts.put(c.getKey(), c.getCount());
        }
        return catalog.all().stream()
                .map(e -> new CuisineInfoDto(e.key(), e.label(), e.region().key(),
                        e.mediterranean(), counts.getOrDefault(e.key(), 0L)))
                .sorted(Comparator.comparingLong(CuisineInfoDto::count).reversed()
                        .thenComparing(CuisineInfoDto::label))
                .toList();
    }
}
