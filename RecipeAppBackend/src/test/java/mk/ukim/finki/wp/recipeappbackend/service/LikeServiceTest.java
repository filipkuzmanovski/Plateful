package mk.ukim.finki.wp.recipeappbackend.service;

import mk.ukim.finki.wp.recipeappbackend.nutrition.NutritionEstimates;
import mk.ukim.finki.wp.recipeappbackend.cuisine.CuisineCatalog;
import mk.ukim.finki.wp.recipeappbackend.exception.RecipeHiddenException;
import mk.ukim.finki.wp.recipeappbackend.exception.RecipeNotApprovedException;
import mk.ukim.finki.wp.recipeappbackend.exception.ResourceNotFoundException;
import mk.ukim.finki.wp.recipeappbackend.model.RecipeStatus;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.SavedItemDto;
import mk.ukim.finki.wp.recipeappbackend.model.entities.Recipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.User;
import mk.ukim.finki.wp.recipeappbackend.photo.RecipePhotoPaths;
import mk.ukim.finki.wp.recipeappbackend.photo.RecipeVideoPaths;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeCommentRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeIngredientRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeLikeRepository;
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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LikeServiceTest {

    private RecipeRepository recipeRepository;
    private RecipeLikeRepository likeRepository;
    private LikeService service;

    private final UUID user = UUID.randomUUID();
    private final UUID recipeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        recipeRepository = mock(RecipeRepository.class);
        likeRepository = mock(RecipeLikeRepository.class);
        UserRepository userRepository = mock(UserRepository.class); // nobody is an admin
        RecipeVisibility visibility = new RecipeVisibility(userRepository);
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
                visibility,
                likeRepository,
                mock(NutritionEstimates.class));
        SavedRecipeService saved = new SavedRecipeService(mock(SavedRecipeRepository.class), recipeRepository,
                recipeService, visibility);
        service = new LikeService(likeRepository, recipeRepository, visibility, saved);
    }

    private Recipe recipe(UUID id, RecipeStatus status) {
        return Recipe.builder().id(id).title("Title " + id).status(status).build();
    }

    @Test
    void like_approvedRecipe_inserts() {
        when(recipeRepository.findById(recipeId)).thenReturn(Optional.of(recipe(recipeId, RecipeStatus.APPROVED)));
        when(likeRepository.insertIgnore(user, recipeId)).thenReturn(1);

        service.like(user, recipeId);

        verify(likeRepository).insertIgnore(user, recipeId);
    }

    @Test
    void like_missingRecipe_throws404() {
        when(recipeRepository.findById(recipeId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.like(user, recipeId)).isInstanceOf(ResourceNotFoundException.class);
        verify(likeRepository, never()).insertIgnore(any(), any());
    }

    @Test
    void like_ownPendingRecipe_throwsNotApproved() {
        Recipe own = Recipe.builder().id(recipeId).title("Mine").status(RecipeStatus.PENDING)
                .user(User.builder().id(user).build()).build();
        when(recipeRepository.findById(recipeId)).thenReturn(Optional.of(own));

        assertThatThrownBy(() -> service.like(user, recipeId)).isInstanceOf(RecipeNotApprovedException.class);
        verify(likeRepository, never()).insertIgnore(any(), any());
    }

    @Test
    void like_hiddenRecipe_throwsHidden() {
        Recipe hidden = Recipe.builder().id(recipeId).title("New").status(RecipeStatus.PENDING)
                .user(User.builder().id(UUID.randomUUID()).build()).everApproved(true).approvedTitle("Old").build();
        when(recipeRepository.findById(recipeId)).thenReturn(Optional.of(hidden));

        assertThatThrownBy(() -> service.like(user, recipeId)).isInstanceOf(RecipeHiddenException.class);
        verify(likeRepository, never()).insertIgnore(any(), any());
    }

    @Test
    void like_recipeDeletedDuringInsert_throws404() {
        when(recipeRepository.findById(recipeId)).thenReturn(Optional.of(recipe(recipeId, RecipeStatus.APPROVED)));
        when(likeRepository.insertIgnore(user, recipeId)).thenReturn(0);
        when(recipeRepository.existsById(recipeId)).thenReturn(false);

        assertThatThrownBy(() -> service.like(user, recipeId)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void like_alreadyLiked_isNoOp() {
        when(recipeRepository.findById(recipeId)).thenReturn(Optional.of(recipe(recipeId, RecipeStatus.APPROVED)));
        when(likeRepository.insertIgnore(user, recipeId)).thenReturn(0);
        when(recipeRepository.existsById(recipeId)).thenReturn(true);

        assertThatCode(() -> service.like(user, recipeId)).doesNotThrowAnyException();
    }

    @Test
    void unlike_alwaysDeletes() {
        service.unlike(user, recipeId);

        verify(likeRepository).deleteByIdUserIdAndIdRecipeId(user, recipeId);
        verify(recipeRepository, never()).findById(any());
    }

    @Test
    void list_keepsLikeOrderWithStubs() {
        UUID approved = UUID.randomUUID();
        UUID hidden = UUID.randomUUID();
        UUID neverApproved = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 12);
        when(likeRepository.findRecipeIdsByUserId(any(), any()))
                .thenReturn(new PageImpl<>(List.of(hidden, approved, neverApproved), pageable, 3));
        when(recipeRepository.findAllById(any())).thenReturn(List.of(
                recipe(approved, RecipeStatus.APPROVED),
                Recipe.builder().id(hidden).title("New").status(RecipeStatus.PENDING)
                        .user(User.builder().id(UUID.randomUUID()).build()).everApproved(true).approvedTitle("Old").build(),
                recipe(neverApproved, RecipeStatus.PENDING)));

        Page<SavedItemDto> page = service.list(user, pageable);

        assertThat(page.getContent()).extracting(SavedItemDto::kind).containsExactly("stub", "recipe");
        assertThat(page.getContent().get(0).stub().approvedTitle()).isEqualTo("Old");
        assertThat(page.getContent().get(1).recipe().id()).isEqualTo(approved);
    }

    @Test
    void list_ownPendingRecipe_carriesItsReviewBlock() {
        UUID own = UUID.randomUUID();
        UUID others = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 12);
        when(likeRepository.findRecipeIdsByUserId(any(), any()))
                .thenReturn(new PageImpl<>(List.of(own, others), pageable, 2));
        when(recipeRepository.findAllById(any())).thenReturn(List.of(
                Recipe.builder().id(own).title("Mine").status(RecipeStatus.PENDING)
                        .user(User.builder().id(user).build()).build(),
                recipe(others, RecipeStatus.APPROVED)));

        List<SavedItemDto> items = service.list(user, pageable).getContent();

        assertThat(items.get(0).recipe().review()).isNotNull();
        assertThat(items.get(0).recipe().review().status()).isEqualTo("pending");
        assertThat(items.get(1).recipe().review()).isNull();
    }

    @Test
    void ids_comeFromTheRepository() {
        when(likeRepository.findAllRecipeIdsByUserId(user)).thenReturn(List.of(recipeId));

        assertThat(service.ids(user)).containsExactly(recipeId);
    }
}
