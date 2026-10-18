package mk.ukim.finki.wp.recipeappbackend.service;

import mk.ukim.finki.wp.recipeappbackend.exception.InvalidReviewReasonException;
import mk.ukim.finki.wp.recipeappbackend.exception.ResourceNotFoundException;
import mk.ukim.finki.wp.recipeappbackend.exception.ReviewConflictException;
import mk.ukim.finki.wp.recipeappbackend.model.RecipeStatus;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.RecipeDisplayDto;
import mk.ukim.finki.wp.recipeappbackend.model.entities.Recipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.User;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The review queue and the approve / reject decisions. The caller has already passed
 * {@link mk.ukim.finki.wp.recipeappbackend.security.AdminGuard}. A decision locks the recipe row, so
 * two admins cannot both decide it, and checks the status and then the revision under that lock.
 */
@Service
public class AdminReviewService {

    static final int MAX_REASON = 500;

    private final RecipeRepository recipeRepository;
    private final UserRepository userRepository;
    private final RecipeService recipeService;
    private final ApplicationEventPublisher events;

    public AdminReviewService(RecipeRepository recipeRepository,
                              UserRepository userRepository,
                              RecipeService recipeService,
                              ApplicationEventPublisher events) {
        this.recipeRepository = recipeRepository;
        this.userRepository = userRepository;
        this.recipeService = recipeService;
        this.events = events;
    }

    /** Pending recipes, oldest submission first (the order is fixed by the query, whatever the caller sent). */
    @Transactional(readOnly = true)
    public Page<RecipeDisplayDto> pending(Pageable pageable) {
        Pageable unsorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
        return recipeService.toDisplayPageWithReview(
                recipeRepository.findByStatusOrderBySubmittedAtAscIdAsc(RecipeStatus.PENDING, unsorted));
    }

    @Transactional(readOnly = true)
    public long pendingCount() {
        return recipeRepository.countByStatus(RecipeStatus.PENDING);
    }

    /** The full recipe with its review block, whatever its status. */
    public RecipeDisplayDto get(UUID recipeId) {
        return recipeService.getWithReview(recipeId);
    }

    @Transactional
    public void approve(UUID adminId, UUID recipeId, int revision) {
        Recipe recipe = lockPending(recipeId, revision);
        recipeService.markApproved(recipe, admin(adminId));
        events.publishEvent(event(recipe, true, null, adminId));
    }

    @Transactional
    public void reject(UUID adminId, UUID recipeId, int revision, String reason) {
        String trimmed = validReason(reason);
        Recipe recipe = lockPending(recipeId, revision);
        // Unlike an approval, a rejection leaves everApproved and approvedTitle alone: the last public title stays.
        recipe.setStatus(RecipeStatus.REJECTED);
        recipe.setRejectionReason(trimmed);
        recipe.setReviewedBy(admin(adminId));
        recipe.setReviewedAt(OffsetDateTime.now());
        events.publishEvent(event(recipe, false, trimmed, adminId));
    }

    private static String validReason(String reason) {
        String trimmed = reason == null ? "" : reason.trim();
        if (trimmed.isEmpty()) {
            throw new InvalidReviewReasonException("A reason is required");
        }
        if (trimmed.length() > MAX_REASON) {
            throw new InvalidReviewReasonException("The reason must be at most " + MAX_REASON + " characters");
        }
        return trimmed;
    }

    /** Locks the row, then checks it is still pending and still at the revision the admin looked at. */
    private Recipe lockPending(UUID recipeId, int revision) {
        Recipe recipe = recipeRepository.findForReviewById(recipeId)
                .orElseThrow(() -> new ResourceNotFoundException("Recipe", recipeId));
        if (recipe.getStatus() != RecipeStatus.PENDING) {
            throw new ReviewConflictException("not_pending");
        }
        if (recipe.getRevision() != revision) {
            throw new ReviewConflictException("recipe_changed");
        }
        return recipe;
    }

    private User admin(UUID adminId) {
        return userRepository.findById(adminId)
                .orElseThrow(() -> new ResourceNotFoundException("User", adminId));
    }

    private static RecipeReviewedEvent event(Recipe recipe, boolean approved, String reason, UUID adminId) {
        User author = recipe.getUser();
        return new RecipeReviewedEvent(
                recipe.getId(),
                approved,
                recipe.getTitle(),
                reason,
                author != null ? author.getEmail() : null,
                author != null ? author.getId() : null,
                adminId);
    }
}
