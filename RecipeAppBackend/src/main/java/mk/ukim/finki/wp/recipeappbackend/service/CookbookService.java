package mk.ukim.finki.wp.recipeappbackend.service;

import mk.ukim.finki.wp.recipeappbackend.exception.CookbookConflictException;
import mk.ukim.finki.wp.recipeappbackend.exception.InvalidCookbookException;
import mk.ukim.finki.wp.recipeappbackend.exception.ResourceNotFoundException;
import mk.ukim.finki.wp.recipeappbackend.model.RecipeStatus;
import mk.ukim.finki.wp.recipeappbackend.model.createDTO.CookbookCreateDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.CookbookDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.CookbookSummaryDto;
import mk.ukim.finki.wp.recipeappbackend.model.entities.Cookbook;
import mk.ukim.finki.wp.recipeappbackend.model.entities.CookbookRecipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.CookbookRecipeId;
import mk.ukim.finki.wp.recipeappbackend.model.entities.Recipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.User;
import mk.ukim.finki.wp.recipeappbackend.model.projection.CookbookRecipeCount;
import mk.ukim.finki.wp.recipeappbackend.repository.CookbookRecipeRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.CookbookRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeLikeRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Cookbooks made from a user's liked recipes. Private to their owner: another user's cookbook is a 404, the
 * same as a missing id. No AI is involved. Identity always arrives as a parameter from the JWT.
 */
@Service
public class CookbookService {

    static final int MIN_RECIPES = 5;
    static final int MAX_RECIPES = 10;
    static final int MAX_COOKBOOKS = 20;
    static final int MAX_SUBTITLE = 80;
    static final String CAP_MESSAGE = "You can have up to 20 cookbooks.";
    static final String UNAVAILABLE_MESSAGE = "Some of these recipes are no longer available.";

    private final CookbookRepository cookbooks;
    private final CookbookRecipeRepository chapters;
    private final RecipeLikeRepository likes;
    private final RecipeRepository recipes;
    private final UserRepository users;
    private final SavedRecipeService savedRecipeService;

    public CookbookService(CookbookRepository cookbooks, CookbookRecipeRepository chapters,
                           RecipeLikeRepository likes, RecipeRepository recipes, UserRepository users,
                           SavedRecipeService savedRecipeService) {
        this.cookbooks = cookbooks;
        this.chapters = chapters;
        this.likes = likes;
        this.recipes = recipes;
        this.users = users;
        this.savedRecipeService = savedRecipeService;
    }

    /** Newest volume first, with each book's recipe count from one batched query. */
    @Transactional(readOnly = true)
    public List<CookbookSummaryDto> list(UUID userId) {
        List<Cookbook> books = cookbooks.findByUserIdOrderByVolumeDesc(userId);
        if (books.isEmpty()) {
            return List.of();
        }
        Map<UUID, Long> counts = chapters.countByCookbookIds(books.stream().map(Cookbook::getId).toList()).stream()
                .collect(Collectors.toMap(CookbookRecipeCount::cookbookId, CookbookRecipeCount::recipeCount));
        return books.stream()
                .map(book -> summary(book, counts.getOrDefault(book.getId(), 0L).intValue()))
                .toList();
    }

    /**
     * The body is checked before the user's row is locked. The lock makes the cap and the volume number safe when
     * two creates arrive at once; UNIQUE (user_id, volume) is the backstop.
     */
    @Transactional
    public CookbookSummaryDto create(UUID userId, CookbookCreateDto body) {
        String subtitle = checkedSubtitle(body.subtitle());
        List<UUID> recipeIds = checkedRecipeIds(body.recipeIds());

        users.findForUpdateById(userId).orElseThrow(() -> new ResourceNotFoundException("User", userId));
        if (cookbooks.countByUserId(userId) >= MAX_COOKBOOKS) {
            throw new CookbookConflictException(CAP_MESSAGE);
        }
        requireLikedAndApproved(userId, recipeIds);

        Cookbook book = cookbooks.saveAndFlush(Cookbook.builder()
                .userId(userId)
                .volume(cookbooks.findMaxVolume(userId) + 1)
                .subtitle(subtitle)
                .build());
        List<CookbookRecipe> rows = new ArrayList<>(recipeIds.size());
        for (int i = 0; i < recipeIds.size(); i++) {
            rows.add(CookbookRecipe.builder()
                    .id(new CookbookRecipeId(book.getId(), recipeIds.get(i)))
                    .position((short) (i + 1))
                    .build());
        }
        chapters.saveAll(rows);
        return summary(book, rows.size());
    }

    @Transactional(readOnly = true)
    public CookbookDto get(UUID userId, UUID cookbookId) {
        Cookbook book = owned(userId, cookbookId);
        User owner = users.findById(userId).orElseThrow(() -> new ResourceNotFoundException("User", userId));
        return new CookbookDto(book.getId(), book.getVolume(), book.getSubtitle(), book.getCreatedAt(),
                owner.getFirstName(), owner.getLastName(),
                savedRecipeService.assembleItems(chapters.findRecipeIdsInOrder(cookbookId), userId));
    }

    /** The database removes the book's chapter rows. */
    @Transactional
    public void delete(UUID userId, UUID cookbookId) {
        cookbooks.delete(owned(userId, cookbookId));
    }

    private Cookbook owned(UUID userId, UUID cookbookId) {
        return cookbooks.findByIdAndUserId(cookbookId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Cookbook", cookbookId));
    }

    private static CookbookSummaryDto summary(Cookbook book, int recipeCount) {
        return new CookbookSummaryDto(book.getId(), book.getVolume(), book.getSubtitle(), book.getCreatedAt(),
                recipeCount);
    }

    /** Trimmed; blank becomes null. */
    private static String checkedSubtitle(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String subtitle = raw.strip();
        if (subtitle.length() > MAX_SUBTITLE) {
            throw new InvalidCookbookException("The subtitle can be at most " + MAX_SUBTITLE + " characters.");
        }
        if (subtitle.chars().anyMatch(Character::isISOControl)) {
            throw new InvalidCookbookException("The subtitle has characters that are not allowed.");
        }
        return subtitle;
    }

    private static List<UUID> checkedRecipeIds(List<UUID> ids) {
        boolean valid = ids != null
                && ids.size() >= MIN_RECIPES && ids.size() <= MAX_RECIPES
                && ids.stream().allMatch(Objects::nonNull)
                && new HashSet<>(ids).size() == ids.size();
        if (!valid) {
            throw new InvalidCookbookException(
                    "Choose between " + MIN_RECIPES + " and " + MAX_RECIPES + " different recipes.");
        }
        return ids;
    }

    private void requireLikedAndApproved(UUID userId, List<UUID> recipeIds) {
        long liked = likes.findLikedAmong(userId, recipeIds).size();
        long approved = recipes.findAllById(recipeIds).stream()
                .map(Recipe::getStatus)
                .filter(status -> status == RecipeStatus.APPROVED)
                .count();
        if (liked != recipeIds.size() || approved != recipeIds.size()) {
            throw new CookbookConflictException(UNAVAILABLE_MESSAGE);
        }
    }
}
