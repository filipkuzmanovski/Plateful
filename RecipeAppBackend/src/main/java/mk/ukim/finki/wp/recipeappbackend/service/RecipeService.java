package mk.ukim.finki.wp.recipeappbackend.service;

import mk.ukim.finki.wp.recipeappbackend.cuisine.CuisineCatalog;
import mk.ukim.finki.wp.recipeappbackend.cuisine.InvalidCuisineException;
import mk.ukim.finki.wp.recipeappbackend.exception.ForbiddenOperationException;
import mk.ukim.finki.wp.recipeappbackend.exception.ResourceNotFoundException;
import mk.ukim.finki.wp.recipeappbackend.model.RecipeFilter;
import mk.ukim.finki.wp.recipeappbackend.model.RecipeSort;
import mk.ukim.finki.wp.recipeappbackend.model.RecipeStatus;
import mk.ukim.finki.wp.recipeappbackend.model.createDTO.RecipeCreateDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.RecipeDisplayDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.RecipeIngredientDisplayDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.RecipeReviewDto;
import mk.ukim.finki.wp.recipeappbackend.model.entities.Recipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.RecipeIngredient;
import mk.ukim.finki.wp.recipeappbackend.model.entities.RecipePhoto;
import mk.ukim.finki.wp.recipeappbackend.model.entities.User;
import mk.ukim.finki.wp.recipeappbackend.model.projection.RecipeCommentCount;
import mk.ukim.finki.wp.recipeappbackend.model.projection.RecipeRatingSummary;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeCommentRepository;
import mk.ukim.finki.wp.recipeappbackend.photo.RecipePhotoPaths;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeIngredientRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipePhotoRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeRatingRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.UserRepository;
import mk.ukim.finki.wp.recipeappbackend.specification.RecipeSpecifications;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * All recipe use cases. Two rules hold everywhere:
 * <p>
 * 1. userId parameters are ALWAYS the JWT "sub" extracted by the controller —
 *    never anything from a request body.
 * 2. Ownership checks live HERE (see assertOwnedBy). The backend's DB
 *    connection bypasses RLS, so these checks are the only authorization
 *    layer this app has. Every mutating method must call one.
 */
@Service
public class RecipeService {

    private static final int MAX_CUISINES = 10;

    private final RecipeRepository recipeRepository;
    private final RecipeIngredientRepository ingredientRepository;
    private final RecipeRatingRepository ratingRepository;
    private final RecipeCommentRepository commentRepository;
    private final UserRepository userRepository;
    private final CuisineCatalog cuisineCatalog;
    private final RecipePhotoRepository photoRepository;
    private final RecipePhotoPaths photoPaths;
    private final RecipeVisibility visibility;

    public RecipeService(RecipeRepository recipeRepository,
                         RecipeIngredientRepository ingredientRepository,
                         RecipeRatingRepository ratingRepository,
                         RecipeCommentRepository commentRepository,
                         UserRepository userRepository,
                         CuisineCatalog cuisineCatalog,
                         RecipePhotoRepository photoRepository,
                         RecipePhotoPaths photoPaths,
                         RecipeVisibility visibility) {
        this.recipeRepository = recipeRepository;
        this.ingredientRepository = ingredientRepository;
        this.ratingRepository = ratingRepository;
        this.commentRepository = commentRepository;
        this.userRepository = userRepository;
        this.cuisineCatalog = cuisineCatalog;
        this.photoRepository = photoRepository;
        this.photoPaths = photoPaths;
        this.visibility = visibility;
    }

    /**
     * Create a user-submitted recipe. One transaction on purpose: the recipe
     * INSERT and its ingredient INSERTs either all succeed or all roll back —
     * a crash halfway can't leave an ingredient-less recipe behind.
     */
    @Transactional
    public RecipeDisplayDto create(UUID userId, RecipeCreateDto dto) {
        User owner = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));

        // Save parent first so it has an id for the ingredients to reference
        // (relationships are one-directional child -> parent by design).
        Recipe toSave = dto.toEntity(owner);
        toSave.setCuisines(validatedCuisines(dto));
        List<String> photos = photoPaths.validate(userId, dto.photos());
        toSave.setImage(coverUrl(photos));
        // An admin's own recipes skip the queue; everyone else's wait for review.
        if (visibility.isAdmin(userId)) {
            markApproved(toSave, owner);
        } else {
            enterPending(toSave);
        }
        Recipe saved = recipeRepository.save(toSave);
        List<RecipeIngredient> ingredients =
                ingredientRepository.saveAll(dto.toIngredientEntities(saved));
        List<RecipePhoto> savedPhotos = photos.isEmpty()
                ? List.of()
                : photoRepository.saveAll(toPhotoEntities(saved, photos));

        return toDisplayDto(saved, ingredients, savedPhotos);
    }

    /**
     * Recipe detail page: recipe + ordered ingredients + aggregates. viewerId is null for an
     * anonymous request. A recipe the viewer may not see is a 404 (see RecipeVisibility); the
     * review block is attached only for the author and admins.
     */
    @Transactional(readOnly = true)
    public RecipeDisplayDto getById(UUID recipeId, UUID viewerId) {
        Recipe recipe = recipeRepository.findById(recipeId)
                .orElseThrow(() -> new ResourceNotFoundException("Recipe", recipeId));
        visibility.requireVisible(recipe, viewerId);
        RecipeDisplayDto dto = toDetailDto(recipe);
        return visibility.showsReview(recipe, viewerId) ? dto.withReview(RecipeReviewDto.of(recipe)) : dto;
    }

    /**
     * The detail page of any recipe, whatever its status, always with the review block. For the admin
     * review API only: the caller must already have passed the admin guard.
     */
    @Transactional(readOnly = true)
    public RecipeDisplayDto getWithReview(UUID recipeId) {
        Recipe recipe = recipeRepository.findById(recipeId)
                .orElseThrow(() -> new ResourceNotFoundException("Recipe", recipeId));
        return toDetailDto(recipe).withReview(RecipeReviewDto.of(recipe));
    }

    /**
     * Browse with the optional multi-criteria filter, paginated.
     * Assembles a page of full display DTOs using the BATCHED queries —
     * regardless of page size this is a fixed number of queries (recipes,
     * users, ingredients, rating summaries, comment counts), never
     * queries-per-recipe.
     */
    @Transactional(readOnly = true)
    public Page<RecipeDisplayDto> browse(RecipeFilter filter, RecipeSort sort, Pageable pageable) {
        RecipeFilter effective = filter != null ? filter
                : new RecipeFilter(null, null, null, null, null, null, null, null, null, null);
        Optional<Set<String>> cuisineKeys = effective.isEmpty()
                ? Optional.empty()
                : cuisineCatalog.resolveFilter(effective.cuisine(), effective.region(), effective.sea());
        if (cuisineKeys.isPresent() && cuisineKeys.get().isEmpty()) {
            // Cuisines/regions/seas were requested but none are known: nothing can match.
            return Page.empty(pageable);
        }
        // The order lives in the Specification (see RecipeSpecifications.withSort), so the page
        // request must carry no sort of its own. Always the Specification path, so the sort
        // applies with or without a filter.
        Pageable unsorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
        return toDisplayPage(recipeRepository.findAll(
                RecipeSpecifications.approvedOnly()
                        .and(RecipeSpecifications.withFilter(effective, cuisineKeys))
                        .and(RecipeSpecifications.withSort(sort)),
                unsorted));
    }

    /**
     * "Recipes by this user" — profile page, same batched assembly. The author and admins see every
     * status, each with its review block; everyone else (viewerId may be null) sees approved only.
     */
    @Transactional(readOnly = true)
    public Page<RecipeDisplayDto> getByAuthor(UUID authorId, UUID viewerId, Pageable pageable) {
        boolean seesAll = authorId.equals(viewerId) || visibility.isAdmin(viewerId);
        if (!seesAll) {
            return toDisplayPage(recipeRepository.findByUserIdAndStatus(authorId, RecipeStatus.APPROVED, pageable));
        }
        return toDisplayPage(recipeRepository.findByUserId(authorId, pageable), true);
    }

    /**
     * Full edit: update the recipe's fields and REPLACE the ingredient list,
     * in one transaction. Replace (delete-all + insert-new) rather than
     * diffing rows — with a dozen freeform lines per recipe, diffing buys
     * nothing and is much easier to get wrong.
     */
    @Transactional
    public RecipeDisplayDto update(UUID userId, UUID recipeId, RecipeCreateDto dto) {
        // Row lock, shared with approve/reject: an edit and a review of the same recipe serialize, so
        // neither overwrites the other's columns (Recipe has no @Version, every column is written on flush).
        Recipe recipe = recipeRepository.findForReviewById(recipeId)
                .orElseThrow(() -> new ResourceNotFoundException("Recipe", recipeId));
        assertOwnedBy(recipe, userId);
        // Validate before mutating anything, so a rejected edit leaves the entity untouched.
        List<String> cuisines = validatedCuisines(dto);
        List<String> photos = photoPaths.validate(userId, dto.photos());

        recipe.setTitle(dto.title());
        recipe.setServings(dto.servings());
        recipe.setReadyInMinutes(dto.readyInMinutes());
        recipe.setCookingMinutes(dto.cookingMinutes());
        recipe.setPreparationMinutes(dto.preparationMinutes());
        // Users can no longer set these; clear whatever an older client or import left behind.
        recipe.setSourceName(null);
        recipe.setSourceUrl(null);
        recipe.setInstructions(dto.instructions());
        recipe.setInstructionsSteps(dto.instructionsSteps() != null ? dto.instructionsSteps() : List.of());
        recipe.setDairyFree(dto.dairyFree());
        recipe.setGlutenFree(dto.glutenFree());
        recipe.setVegan(dto.vegan());
        recipe.setVegetarian(dto.vegetarian());
        recipe.setCuisines(cuisines);
        // Every successful edit changes what a reviewer would see, so it bumps the revision
        // (a stale approve/reject is then refused), and it re-enters review unless the author is an admin.
        recipe.setRevision(recipe.getRevision() + 1);
        if (visibility.isAdmin(userId)) {
            markApproved(recipe, recipe.getUser());
        } else if (recipe.getStatus() != RecipeStatus.PENDING) {
            enterPending(recipe);
        }
        // NOT touched: source, spoonacularId, user, timestamps — server-owned.
        // @UpdateTimestamp refreshes updated_at automatically on flush.

        ingredientRepository.deleteByRecipeId(recipeId);
        List<RecipeIngredient> ingredients =
                ingredientRepository.saveAll(dto.toIngredientEntities(recipe));

        List<RecipePhoto> savedPhotos;
        if (photos.isEmpty() && !photoRepository.existsByRecipeId(recipeId)) {
            // Legacy recipe whose cover is a linked image URL (no photo rows): leave it alone.
            savedPhotos = List.of();
        } else {
            photoRepository.deleteAllByRecipeId(recipeId);
            savedPhotos = photos.isEmpty()
                    ? List.of()
                    : photoRepository.saveAll(toPhotoEntities(recipe, photos));
            recipe.setImage(coverUrl(photos));
        }

        return toDisplayDto(recipe, ingredients, savedPhotos);
    }

    /** Delete own recipe. Ratings/comments/ingredients go via ON DELETE CASCADE. */
    @Transactional
    public void delete(UUID userId, UUID recipeId) {
        // Same row lock as update and the review decisions.
        Recipe recipe = recipeRepository.findForReviewById(recipeId)
                .orElseThrow(() -> new ResourceNotFoundException("Recipe", recipeId));
        assertOwnedBy(recipe, userId);
        recipeRepository.delete(recipe);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /**
     * Approve a recipe: an admin creating or editing their own recipe, or an admin approving
     * another author's. approvedTitle always follows the current title, so an ever-approved recipe
     * never has a null one.
     */
    void markApproved(Recipe recipe, User reviewer) {
        recipe.setStatus(RecipeStatus.APPROVED);
        recipe.setEverApproved(true);
        recipe.setApprovedTitle(recipe.getTitle());
        recipe.setRejectionReason(null);
        recipe.setReviewedBy(reviewer);
        recipe.setReviewedAt(OffsetDateTime.now());
    }

    /** (Re-)enter the review queue at the back; any earlier rejection reason no longer applies. */
    private void enterPending(Recipe recipe) {
        recipe.setStatus(RecipeStatus.PENDING);
        recipe.setSubmittedAt(OffsetDateTime.now());
        recipe.setRejectionReason(null);
    }

    /** Normalize against the curated catalogue; the 10-cap applies after dedupe. */
    private List<String> validatedCuisines(RecipeCreateDto dto) {
        List<String> cuisines = cuisineCatalog.normalizeAndValidate(dto.cuisines());
        if (cuisines.size() > MAX_CUISINES) {
            throw new InvalidCuisineException("At most " + MAX_CUISINES + " cuisines");
        }
        return cuisines;
    }

    /**
     * THE authorization check (see class javadoc). Imported recipes have no
     * owner (user is null) and are therefore editable by nobody — only a
     * future admin concept could touch them.
     */
    private void assertOwnedBy(Recipe recipe, UUID userId) {
        if (recipe.getUser() == null || !recipe.getUser().getId().equals(userId)) {
            throw new ForbiddenOperationException("You don't own this recipe");
        }
    }

    /** Detail-page assembly for one recipe, without the review block. */
    private RecipeDisplayDto toDetailDto(Recipe recipe) {
        return toDisplayDto(recipe,
                ingredientRepository.findByRecipeIdOrderBySortOrderAsc(recipe.getId()),
                photoRepository.findByRecipeIdOrderByPositionAsc(recipe.getId()));
    }

    /** Detail-page assembly: per-recipe aggregate queries are fine for ONE recipe. */
    private RecipeDisplayDto toDisplayDto(Recipe recipe, List<RecipeIngredient> ingredients,
                                          List<RecipePhoto> photos) {
        return RecipeDisplayDto.fromEntity(
                recipe,
                ingredients.stream().map(RecipeIngredientDisplayDto::fromEntity).toList(),
                photoUrls(recipe, photos),
                ratingRepository.findAverageRatingByRecipeId(recipe.getId()),
                ratingRepository.countByRecipeId(recipe.getId()),
                commentRepository.countByRecipeId(recipe.getId())
        );
    }

    /** The cover (position 0) as a public URL, or null when there are no photos. */
    private String coverUrl(List<String> photos) {
        return photos.isEmpty() ? null : photoPaths.publicUrl(photos.get(0));
    }

    private List<RecipePhoto> toPhotoEntities(Recipe recipe, List<String> paths) {
        List<RecipePhoto> result = new ArrayList<>();
        for (int i = 0; i < paths.size(); i++) {
            result.add(RecipePhoto.builder().recipe(recipe).path(paths.get(i)).position(i).build());
        }
        return result;
    }

    /**
     * Public URLs of the (already position-ordered) photo rows. Recipes without rows fall back to
     * their linked image (imported or legacy recipes), so clients only ever read {@code photos}.
     */
    private List<String> photoUrls(Recipe recipe, List<RecipePhoto> photos) {
        if (photos.isEmpty()) {
            return recipe.getImage() != null ? List.of(recipe.getImage()) : List.of();
        }
        return photos.stream().map(p -> photoPaths.publicUrl(p.getPath())).toList();
    }

    /**
     * List-page assembly with a FIXED number of queries (the whole point of
     * the batched repository methods — see the N+1 BUGFIX comments there).
     */
    public Page<RecipeDisplayDto> toDisplayPage(Page<Recipe> page) {
        return toDisplayPage(page, false);
    }

    /** As above, with each recipe's review block attached. For the admin review API only. */
    public Page<RecipeDisplayDto> toDisplayPageWithReview(Page<Recipe> page) {
        return toDisplayPage(page, true);
    }

    /** As above; withReview attaches each recipe's review block (only for the author's or an admin's view). */
    private Page<RecipeDisplayDto> toDisplayPage(Page<Recipe> page, boolean withReview) {
        List<UUID> ids = page.getContent().stream().map(Recipe::getId).toList();
        if (ids.isEmpty()) {
            return page.map(r -> null); // empty page, keep pagination metadata
        }

        // Warm up lazy user proxies in ONE query: asking a proxy for its id
        // doesn't hit the DB, and findAllById loads all those users into the
        // persistence context, so the getUser().getFirstName() calls inside
        // fromEntity() are served from memory instead of one query per recipe.
        List<UUID> authorIds = page.getContent().stream()
                .filter(r -> r.getUser() != null)
                .map(r -> r.getUser().getId())
                .distinct()
                .toList();
        if (!authorIds.isEmpty()) {
            userRepository.findAllById(authorIds);
        }

        // One query each: ingredients, photos, rating summaries, comment counts.
        Map<UUID, List<RecipeIngredient>> ingredientsByRecipe =
                ingredientRepository.findByRecipeIdInOrderBySortOrderAsc(ids).stream()
                        .collect(Collectors.groupingBy(i -> i.getRecipe().getId()));
        Map<UUID, List<RecipePhoto>> photosByRecipe =
                photoRepository.findByRecipeIdInOrderByPositionAsc(ids).stream()
                        .collect(Collectors.groupingBy(p -> p.getRecipe().getId()));
        Map<UUID, RecipeRatingSummary> ratingsByRecipe =
                ratingRepository.findRatingSummaries(ids).stream()
                        .collect(Collectors.toMap(RecipeRatingSummary::recipeId, Function.identity()));
        Map<UUID, Long> commentCounts =
                commentRepository.findCommentCounts(ids).stream()
                        .collect(Collectors.toMap(RecipeCommentCount::recipeId, RecipeCommentCount::commentCount));

        return page.map(recipe -> {
            RecipeRatingSummary summary = ratingsByRecipe.get(recipe.getId());
            RecipeDisplayDto dto = RecipeDisplayDto.fromEntity(
                    recipe,
                    ingredientsByRecipe.getOrDefault(recipe.getId(), List.of()).stream()
                            .map(RecipeIngredientDisplayDto::fromEntity).toList(),
                    photoUrls(recipe, photosByRecipe.getOrDefault(recipe.getId(), List.of())),
                    // absent from GROUP BY result = zero ratings/comments
                    summary != null ? summary.averageRating() : null,
                    summary != null ? summary.ratingCount() : 0L,
                    commentCounts.getOrDefault(recipe.getId(), 0L)
            );
            return withReview ? dto.withReview(RecipeReviewDto.of(recipe)) : dto;
        });
    }
}
