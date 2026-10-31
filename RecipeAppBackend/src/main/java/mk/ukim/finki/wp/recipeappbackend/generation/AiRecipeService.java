package mk.ukim.finki.wp.recipeappbackend.generation;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import mk.ukim.finki.wp.recipeappbackend.exception.ResourceNotFoundException;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.GeneratedRecipeDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.GeneratedRecipeSummaryDto;
import mk.ukim.finki.wp.recipeappbackend.model.entities.GeneratedRecipe;
import mk.ukim.finki.wp.recipeappbackend.repository.GeneratedRecipeRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reading, listing, saving and unsaving the user's own AI recipes (spec section 7.1). Every lookup is scoped
 * to the owner, so another user's id is a 404, never a 403. No AI call happens here, so the transactions are
 * short.
 */
@Service
public class AiRecipeService {

    private final GeneratedRecipeRepository recipes;
    private final GeneratedRecipeMapper mapper;
    private final Clock clock;

    @Autowired
    public AiRecipeService(GeneratedRecipeRepository recipes, GeneratedRecipeMapper mapper) {
        this(recipes, mapper, Clock.systemUTC());
    }

    AiRecipeService(GeneratedRecipeRepository recipes, GeneratedRecipeMapper mapper, Clock clock) {
        this.recipes = recipes;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** One of the user's AI recipes, saved or not. */
    @Transactional(readOnly = true)
    public GeneratedRecipeDto get(UUID userId, UUID id) {
        return mapper.toDto(owned(userId, id));
    }

    /** The user's saved recipes, newest saved first whatever sort the client sent. */
    @Transactional(readOnly = true)
    public Page<GeneratedRecipeSummaryDto> saved(UUID userId, Pageable pageable) {
        Pageable newestSavedFirst = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "savedAt"));
        return recipes.findByUserIdAndSavedAtIsNotNull(userId, newestSavedFirst).map(mapper::toSummary);
    }

    /** Idempotent: a recipe that is already saved keeps its first {@code saved_at}. */
    @Transactional
    public void save(UUID userId, UUID id) {
        GeneratedRecipe row = owned(userId, id);
        if (row.getSavedAt() == null) {
            row.setSavedAt(OffsetDateTime.now(clock));
            recipes.save(row);
        }
    }

    /** Idempotent: clearing a recipe that is not saved is a no-op. */
    @Transactional
    public void unsave(UUID userId, UUID id) {
        GeneratedRecipe row = owned(userId, id);
        if (row.getSavedAt() != null) {
            row.setSavedAt(null);
            recipes.save(row);
        }
    }

    private GeneratedRecipe owned(UUID userId, UUID id) {
        return recipes.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("AI recipe", id));
    }
}
