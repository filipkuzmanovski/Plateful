package mk.ukim.finki.wp.recipeappbackend.service;

import mk.ukim.finki.wp.recipeappbackend.cuisine.CuisineCatalog;
import mk.ukim.finki.wp.recipeappbackend.exception.RecipeHiddenException;
import mk.ukim.finki.wp.recipeappbackend.exception.ResourceNotFoundException;
import mk.ukim.finki.wp.recipeappbackend.model.RecipeStatus;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.SavedItemDto;
import mk.ukim.finki.wp.recipeappbackend.model.entities.Recipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.User;
import mk.ukim.finki.wp.recipeappbackend.photo.RecipePhotoPaths;
import mk.ukim.finki.wp.recipeappbackend.photo.RecipeVideoPaths;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
        UserRepository userRepository = mock(UserRepository.class); // nobody is an admin
        RecipeVisibility visibility = new RecipeVisibility(userRepository);
        // A real RecipeService over mocked repositories, so list() really assembles DTOs.
        RecipeService recipeService = new RecipeService(
                recipeRepository,
                mock(RecipeIngredientRepository.class),
                mock(RecipeRatingRepository.class),
                mock(RecipeCommentRepository.class),
                userRepository,
                new CuisineCatalog(),
                mock(RecipePhotoRepository.class),
                new RecipePhotoPaths("https://x/recipe-photos"),
                new RecipeVideoPaths("https://x/recipe-videos"),
                visibility);
        service = new SavedRecipeService(savedRepository, recipeRepository, recipeService, visibility);
    }

    private static Recipe recipe(UUID id, RecipeStatus status) {
        return Recipe.builder().id(id).title("Title " + id).status(status).build();
    }

    /** A recipe that was approved once under {@code approvedTitle} and has since been edited. */
    private static Recipe reEdited(UUID id, RecipeStatus status, String approvedTitle) {
        Recipe r = recipe(id, status);
        r.setTitle("Edited " + approvedTitle);
        r.setEverApproved(true);
        r.setApprovedTitle(approvedTitle);
        return r;
    }

    @Test
    void saveMissingRecipeThrows404() {
        UUID recipeId = UUID.randomUUID();
        when(recipeRepository.findById(recipeId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.save(user, recipeId))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(savedRepository, never()).insertIgnore(user, recipeId);
    }

    @Test
    void saveCallsInsertIgnore() {
        UUID recipeId = UUID.randomUUID();
        when(recipeRepository.findById(recipeId)).thenReturn(Optional.of(recipe(recipeId, RecipeStatus.APPROVED)));

        service.save(user, recipeId);

        verify(savedRepository).insertIgnore(user, recipeId);
    }

    @Test
    void saveHiddenRecipeIs404() {
        UUID everApprovedId = UUID.randomUUID();
        UUID neverApprovedId = UUID.randomUUID();
        when(recipeRepository.findById(everApprovedId))
                .thenReturn(Optional.of(reEdited(everApprovedId, RecipeStatus.PENDING, "Old Title")));
        when(recipeRepository.findById(neverApprovedId))
                .thenReturn(Optional.of(recipe(neverApprovedId, RecipeStatus.PENDING)));

        // Both are 404s: the first carries the approved title, the second is a plain not-found.
        assertThatThrownBy(() -> service.save(user, everApprovedId)).isInstanceOf(RecipeHiddenException.class);
        assertThatThrownBy(() -> service.save(user, neverApprovedId)).isInstanceOf(ResourceNotFoundException.class);
        verify(savedRepository, never()).insertIgnore(any(), any());
    }

    @Test
    void authorCanSaveOwnPendingRecipe() {
        UUID recipeId = UUID.randomUUID();
        Recipe own = recipe(recipeId, RecipeStatus.PENDING);
        own.setUser(User.builder().id(user).build());
        when(recipeRepository.findById(recipeId)).thenReturn(Optional.of(own));

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
                recipe(r1, RecipeStatus.APPROVED),
                recipe(r3, RecipeStatus.APPROVED)));

        Page<SavedItemDto> result = service.list(user, pageable);

        assertThat(result.getContent()).extracting(SavedItemDto::kind).containsOnly("recipe");
        assertThat(result.getContent()).extracting(i -> i.recipe().id()).containsExactly(r3, r1);
        assertThat(result.getTotalElements()).isEqualTo(3);
    }

    @Test
    void listMixesCardsAndStubsInSavedOrder() {
        UUID e = UUID.randomUUID(); // approved once, edited, now pending
        UUID p = UUID.randomUUID(); // approved
        UUID r = UUID.randomUUID(); // approved once, edited, now rejected
        UUID never = UUID.randomUUID(); // never approved, not the saver's own
        Pageable pageable = PageRequest.of(0, 4);
        when(savedRepository.findRecipeIdsByUserId(user, pageable))
                .thenReturn(new PageImpl<>(List.of(e, p, r, never), pageable, 4));
        when(recipeRepository.findAllById(List.of(e, p, r, never))).thenReturn(List.of(
                reEdited(r, RecipeStatus.REJECTED, "Approved title R"),
                recipe(never, RecipeStatus.PENDING),
                recipe(p, RecipeStatus.APPROVED),
                reEdited(e, RecipeStatus.PENDING, "Approved title E")));

        Page<SavedItemDto> result = service.list(user, pageable);

        assertThat(result.getContent()).extracting(SavedItemDto::kind).containsExactly("stub", "recipe", "stub");
        SavedItemDto first = result.getContent().get(0);
        assertThat(first.recipe()).isNull();
        assertThat(first.stub().id()).isEqualTo(e);
        assertThat(first.stub().state()).isEqualTo("under_review");
        // The approved title, never the edited one still awaiting review.
        assertThat(first.stub().approvedTitle()).isEqualTo("Approved title E");
        SavedItemDto second = result.getContent().get(1);
        assertThat(second.stub()).isNull();
        assertThat(second.recipe().id()).isEqualTo(p);
        SavedItemDto third = result.getContent().get(2);
        assertThat(third.recipe()).isNull();
        assertThat(third.stub().id()).isEqualTo(r);
        assertThat(third.stub().state()).isEqualTo("unavailable");
        assertThat(third.stub().approvedTitle()).isEqualTo("Approved title R");
        assertThat(result.getTotalElements()).isEqualTo(4);
    }

    @Test
    void authorSeesOwnPendingAsCard() {
        UUID own = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 1);
        when(savedRepository.findRecipeIdsByUserId(user, pageable))
                .thenReturn(new PageImpl<>(List.of(own), pageable, 1));
        Recipe pending = reEdited(own, RecipeStatus.PENDING, "Old title");
        pending.setUser(User.builder().id(user).build());
        when(recipeRepository.findAllById(List.of(own))).thenReturn(List.of(pending));

        Page<SavedItemDto> result = service.list(user, pageable);

        assertThat(result.getContent()).singleElement().satisfies(item -> {
            assertThat(item.kind()).isEqualTo("recipe");
            assertThat(item.stub()).isNull();
            assertThat(item.recipe().id()).isEqualTo(own);
        });
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
