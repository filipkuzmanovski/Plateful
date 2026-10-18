package mk.ukim.finki.wp.recipeappbackend.service;

import mk.ukim.finki.wp.recipeappbackend.cuisine.CuisineCatalog;
import mk.ukim.finki.wp.recipeappbackend.exception.InvalidReviewReasonException;
import mk.ukim.finki.wp.recipeappbackend.exception.ResourceNotFoundException;
import mk.ukim.finki.wp.recipeappbackend.exception.ReviewConflictException;
import mk.ukim.finki.wp.recipeappbackend.model.RecipeStatus;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.RecipeDisplayDto;
import mk.ukim.finki.wp.recipeappbackend.model.entities.Recipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.User;
import mk.ukim.finki.wp.recipeappbackend.photo.RecipePhotoPaths;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeCommentRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeIngredientRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipePhotoRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeRatingRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

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

class AdminReviewServiceTest {

    private RecipeRepository recipeRepository;
    private UserRepository userRepository;
    private ApplicationEventPublisher publisher;
    private AdminReviewService service;

    private final UUID adminId = UUID.randomUUID();
    private final UUID authorId = UUID.randomUUID();
    private final UUID recipeId = UUID.randomUUID();
    private User admin;
    private User author;

    @BeforeEach
    void setUp() {
        recipeRepository = mock(RecipeRepository.class);
        userRepository = mock(UserRepository.class);
        publisher = mock(ApplicationEventPublisher.class);
        RecipeVisibility visibility = new RecipeVisibility(userRepository);
        // A real RecipeService over mocked repositories, so markApproved and the display assembly are the real ones.
        RecipeService recipeService = new RecipeService(
                recipeRepository,
                mock(RecipeIngredientRepository.class),
                mock(RecipeRatingRepository.class),
                mock(RecipeCommentRepository.class),
                userRepository,
                new CuisineCatalog(),
                mock(RecipePhotoRepository.class),
                new RecipePhotoPaths("https://x/recipe-photos"),
                visibility);
        service = new AdminReviewService(recipeRepository, userRepository, recipeService, publisher);

        admin = User.builder().id(adminId).firstName("Ada").lastName("Admin").email("ada@example.test").build();
        author = User.builder().id(authorId).firstName("Al").lastName("Author").email("al@example.test").build();
        when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));
    }

    private Recipe pending(User owner, int revision) {
        return Recipe.builder().id(recipeId).title("Soup").user(owner).status(RecipeStatus.PENDING)
                .revision(revision).build();
    }

    private void locked(Recipe recipe) {
        when(recipeRepository.findForReviewById(recipeId)).thenReturn(Optional.of(recipe));
    }

    private RecipeReviewedEvent publishedEvent() {
        ArgumentCaptor<RecipeReviewedEvent> captor = ArgumentCaptor.forClass(RecipeReviewedEvent.class);
        verify(publisher).publishEvent(captor.capture());
        return captor.getValue();
    }

    @Test
    void approveSetsApprovedAndPublishesEvent() {
        Recipe recipe = pending(author, 3);
        locked(recipe);

        service.approve(adminId, recipeId, 3);

        assertThat(recipe.getStatus()).isEqualTo(RecipeStatus.APPROVED);
        assertThat(recipe.isEverApproved()).isTrue();
        assertThat(recipe.getApprovedTitle()).isEqualTo("Soup");
        assertThat(recipe.getReviewedBy()).isSameAs(admin);
        assertThat(recipe.getReviewedAt()).isNotNull();
        RecipeReviewedEvent event = publishedEvent();
        assertThat(event.approved()).isTrue();
        assertThat(event.recipeId()).isEqualTo(recipeId);
        assertThat(event.title()).isEqualTo("Soup");
        assertThat(event.reason()).isNull();
        assertThat(event.authorEmail()).isEqualTo("al@example.test");
        assertThat(event.authorId()).isEqualTo(authorId);
        assertThat(event.adminId()).isEqualTo(adminId);
    }

    @Test
    void rejectStoresTrimmedReasonAndPublishes() {
        Recipe recipe = pending(author, 1);
        locked(recipe);

        service.reject(adminId, recipeId, 1, "  Too short  ");

        assertThat(recipe.getStatus()).isEqualTo(RecipeStatus.REJECTED);
        assertThat(recipe.getRejectionReason()).isEqualTo("Too short");
        assertThat(recipe.getReviewedBy()).isSameAs(admin);
        assertThat(recipe.getReviewedAt()).isNotNull();
        RecipeReviewedEvent event = publishedEvent();
        assertThat(event.approved()).isFalse();
        assertThat(event.reason()).isEqualTo("Too short");
        assertThat(event.authorEmail()).isEqualTo("al@example.test");
    }

    @Test
    void rejectLeavesApprovalHistoryAlone() {
        Recipe recipe = pending(author, 2);
        recipe.setEverApproved(true);
        recipe.setApprovedTitle("Old title");
        locked(recipe);

        service.reject(adminId, recipeId, 2, "Not enough detail");

        assertThat(recipe.isEverApproved()).isTrue();
        assertThat(recipe.getApprovedTitle()).isEqualTo("Old title");
    }

    @Test
    void blankReasonIs400() {
        for (String reason : new String[]{"   ", "", null}) {
            assertThatThrownBy(() -> service.reject(adminId, recipeId, 1, reason))
                    .isInstanceOf(InvalidReviewReasonException.class);
        }
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void reasonOver500AfterTrimIs400() {
        assertThatThrownBy(() -> service.reject(adminId, recipeId, 1, "x".repeat(501)))
                .isInstanceOf(InvalidReviewReasonException.class);

        // 500 characters plus padding is fine once trimmed.
        Recipe recipe = pending(author, 1);
        locked(recipe);
        service.reject(adminId, recipeId, 1, "  " + "x".repeat(500) + "  ");
        assertThat(recipe.getRejectionReason()).hasSize(500);
    }

    @Test
    void staleRevisionIsRecipeChanged() {
        Recipe recipe = pending(author, 4);
        locked(recipe);

        assertThatThrownBy(() -> service.approve(adminId, recipeId, 3))
                .isInstanceOfSatisfying(ReviewConflictException.class,
                        e -> assertThat(e.getCode()).isEqualTo("recipe_changed"));
        assertThatThrownBy(() -> service.reject(adminId, recipeId, 3, "Reason"))
                .isInstanceOfSatisfying(ReviewConflictException.class,
                        e -> assertThat(e.getCode()).isEqualTo("recipe_changed"));

        assertThat(recipe.getStatus()).isEqualTo(RecipeStatus.PENDING);
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void notPendingIsNotPending() {
        Recipe recipe = pending(author, 1);
        recipe.setStatus(RecipeStatus.APPROVED);
        locked(recipe);

        assertThatThrownBy(() -> service.approve(adminId, recipeId, 1))
                .isInstanceOfSatisfying(ReviewConflictException.class,
                        e -> assertThat(e.getCode()).isEqualTo("not_pending"));
        assertThatThrownBy(() -> service.reject(adminId, recipeId, 1, "Reason"))
                .isInstanceOfSatisfying(ReviewConflictException.class,
                        e -> assertThat(e.getCode()).isEqualTo("not_pending"));
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void notPendingWinsOverStaleRevision() {
        Recipe recipe = pending(author, 5);
        recipe.setStatus(RecipeStatus.REJECTED);
        locked(recipe);

        assertThatThrownBy(() -> service.approve(adminId, recipeId, 1))
                .isInstanceOfSatisfying(ReviewConflictException.class,
                        e -> assertThat(e.getCode()).isEqualTo("not_pending"));
    }

    @Test
    void secondDecisionGetsNotPending() {
        locked(pending(author, 2));

        service.approve(adminId, recipeId, 2);

        assertThatThrownBy(() -> service.reject(adminId, recipeId, 2, "Changed my mind"))
                .isInstanceOfSatisfying(ReviewConflictException.class,
                        e -> assertThat(e.getCode()).isEqualTo("not_pending"));
        verify(publisher).publishEvent(any(RecipeReviewedEvent.class)); // only the first decision
    }

    @Test
    void unknownRecipeIs404() {
        when(recipeRepository.findForReviewById(recipeId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approve(adminId, recipeId, 1))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.reject(adminId, recipeId, 1, "Reason"))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.get(recipeId)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void approveAuthorlessRecipeSendsNoEmail() {
        locked(pending(null, 1));

        service.approve(adminId, recipeId, 1);

        RecipeReviewedEvent event = publishedEvent();
        assertThat(event.authorEmail()).isNull();
        assertThat(event.authorId()).isNull();
    }

    @Test
    void selfReviewEventMarksAdmin() {
        locked(pending(admin, 1));

        service.approve(adminId, recipeId, 1);

        RecipeReviewedEvent event = publishedEvent();
        assertThat(event.authorId()).isEqualTo(adminId);
        assertThat(event.adminId()).isEqualTo(adminId);
    }

    @Test
    void getReturnsFullRecipeWithReviewWhateverItsStatus() {
        Recipe recipe = pending(author, 7);
        recipe.setStatus(RecipeStatus.REJECTED);
        recipe.setRejectionReason("Nope");
        when(recipeRepository.findById(recipeId)).thenReturn(Optional.of(recipe));

        RecipeDisplayDto dto = service.get(recipeId);

        assertThat(dto.id()).isEqualTo(recipeId);
        assertThat(dto.review()).isNotNull();
        assertThat(dto.review().status()).isEqualTo("rejected");
        assertThat(dto.review().revision()).isEqualTo(7);
        assertThat(dto.review().rejectionReason()).isEqualTo("Nope");
    }

    @Test
    void pendingAssemblesPageWithReviewBlock() {
        Recipe recipe = pending(author, 2);
        Pageable requested = PageRequest.of(1, 5);
        when(recipeRepository.findByStatusOrderBySubmittedAtAscIdAsc(RecipeStatus.PENDING, requested))
                .thenReturn(new PageImpl<>(List.of(recipe), requested, 6));

        Page<RecipeDisplayDto> page = service.pending(requested);

        assertThat(page.getTotalElements()).isEqualTo(6);
        assertThat(page.getContent()).singleElement().satisfies(dto -> {
            assertThat(dto.id()).isEqualTo(recipeId);
            assertThat(dto.review().status()).isEqualTo("pending");
            assertThat(dto.review().revision()).isEqualTo(2);
        });
    }

    @Test
    void pendingIgnoresCallerSort() {
        Pageable sorted = PageRequest.of(0, 5, Sort.by("title"));
        when(recipeRepository.findByStatusOrderBySubmittedAtAscIdAsc(any(), any())).thenReturn(Page.empty());

        service.pending(sorted);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(recipeRepository).findByStatusOrderBySubmittedAtAscIdAsc(any(), captor.capture());
        assertThat(captor.getValue().getSort().isUnsorted()).isTrue();
        assertThat(captor.getValue().getPageSize()).isEqualTo(5);
    }

    @Test
    void pendingCountCountsPendingRecipes() {
        when(recipeRepository.countByStatus(RecipeStatus.PENDING)).thenReturn(4L);

        assertThat(service.pendingCount()).isEqualTo(4L);
    }
}
