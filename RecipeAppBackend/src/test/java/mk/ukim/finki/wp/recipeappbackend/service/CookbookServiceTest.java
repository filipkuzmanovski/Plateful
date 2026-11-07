package mk.ukim.finki.wp.recipeappbackend.service;

import mk.ukim.finki.wp.recipeappbackend.nutrition.NutritionEstimates;
import mk.ukim.finki.wp.recipeappbackend.cuisine.CuisineCatalog;
import mk.ukim.finki.wp.recipeappbackend.exception.CookbookConflictException;
import mk.ukim.finki.wp.recipeappbackend.exception.InvalidCookbookException;
import mk.ukim.finki.wp.recipeappbackend.exception.ResourceNotFoundException;
import mk.ukim.finki.wp.recipeappbackend.model.RecipeStatus;
import mk.ukim.finki.wp.recipeappbackend.model.createDTO.CookbookCreateDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.CookbookDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.CookbookSummaryDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.SavedItemDto;
import mk.ukim.finki.wp.recipeappbackend.model.entities.Cookbook;
import mk.ukim.finki.wp.recipeappbackend.model.entities.CookbookRecipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.Recipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.User;
import mk.ukim.finki.wp.recipeappbackend.model.projection.CookbookRecipeCount;
import mk.ukim.finki.wp.recipeappbackend.photo.RecipePhotoPaths;
import mk.ukim.finki.wp.recipeappbackend.photo.RecipeVideoPaths;
import mk.ukim.finki.wp.recipeappbackend.repository.CookbookRecipeRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.CookbookRepository;
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
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CookbookServiceTest {

    private CookbookRepository cookbooks;
    private CookbookRecipeRepository chapters;
    private RecipeLikeRepository likes;
    private RecipeRepository recipes;
    private UserRepository users;
    private CookbookService service;

    private final UUID user = UUID.randomUUID();
    private final User owner = User.builder().id(user).firstName("Ana").lastName("Petrovska").build();

    @BeforeEach
    void setUp() {
        cookbooks = mock(CookbookRepository.class);
        chapters = mock(CookbookRecipeRepository.class);
        likes = mock(RecipeLikeRepository.class);
        recipes = mock(RecipeRepository.class);
        users = mock(UserRepository.class);
        RecipeVisibility visibility = new RecipeVisibility(users);
        RecipeService recipeService = new RecipeService(
                recipes,
                mock(RecipeIngredientRepository.class),
                mock(RecipeRatingRepository.class),
                mock(RecipeCommentRepository.class),
                users,
                new CuisineCatalog(),
                mock(RecipePhotoRepository.class),
                new RecipePhotoPaths("https://x/recipe-photos"),
                new RecipeVideoPaths("https://x/recipe-videos"),
                visibility,
                likes,
                mock(NutritionEstimates.class));
        SavedRecipeService saved = new SavedRecipeService(mock(SavedRecipeRepository.class), recipes, recipeService,
                visibility);
        service = new CookbookService(cookbooks, chapters, likes, recipes, users, saved);

        when(users.findForUpdateById(user)).thenReturn(Optional.of(owner));
        when(users.findById(user)).thenReturn(Optional.of(owner));
        when(cookbooks.saveAndFlush(any())).thenAnswer(call -> {
            Cookbook book = call.getArgument(0);
            book.setId(UUID.randomUUID());
            return book;
        });
    }

    private static List<UUID> ids(int count) {
        return IntStream.range(0, count).mapToObj(i -> UUID.randomUUID()).toList();
    }

    /** Every id is liked by the user and approved. */
    private void allLikedAndApproved(List<UUID> ids) {
        when(likes.findLikedAmong(user, ids)).thenReturn(ids);
        when(recipes.findAllById(ids)).thenReturn(ids.stream()
                .map(id -> Recipe.builder().id(id).title("T").status(RecipeStatus.APPROVED).build()).toList());
    }

    private Cookbook savedBook() {
        ArgumentCaptor<Cookbook> captor = ArgumentCaptor.forClass(Cookbook.class);
        verify(cookbooks).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    @Test
    void create_blankSubtitle_storesNull() {
        List<UUID> five = ids(5);
        allLikedAndApproved(five);

        CookbookSummaryDto result = service.create(user, new CookbookCreateDto(five, "   "));

        assertThat(savedBook().getSubtitle()).isNull();
        assertThat(result.subtitle()).isNull();
    }

    @Test
    void create_trimsSubtitle() {
        List<UUID> five = ids(5);
        allLikedAndApproved(five);

        service.create(user, new CookbookCreateDto(five, "  Weeknight dinners "));

        assertThat(savedBook().getSubtitle()).isEqualTo("Weeknight dinners");
    }

    @Test
    void create_subtitle80Chars_allowed() {
        List<UUID> five = ids(5);
        allLikedAndApproved(five);

        service.create(user, new CookbookCreateDto(five, "x".repeat(80)));

        assertThat(savedBook().getSubtitle()).hasSize(80);
    }

    @Test
    void create_subtitle81Chars_rejected() {
        assertThatThrownBy(() -> service.create(user, new CookbookCreateDto(ids(5), "x".repeat(81))))
                .isInstanceOf(InvalidCookbookException.class);
    }

    @Test
    void create_subtitleWithControlChar_rejected() {
        assertThatThrownBy(() -> service.create(user, new CookbookCreateDto(ids(5), "bad\u0007title")))
                .isInstanceOf(InvalidCookbookException.class);
    }

    @Test
    void create_nullIds_rejected() {
        assertThatThrownBy(() -> service.create(user, new CookbookCreateDto(null, null)))
                .isInstanceOf(InvalidCookbookException.class);
    }

    @Test
    void create_nullIdInList_rejected() {
        List<UUID> withNull = new ArrayList<>(ids(4));
        withNull.add(null);
        assertThatThrownBy(() -> service.create(user, new CookbookCreateDto(withNull, null)))
                .isInstanceOf(InvalidCookbookException.class);
    }

    @Test
    void create_fourIds_rejected() {
        assertThatThrownBy(() -> service.create(user, new CookbookCreateDto(ids(4), null)))
                .isInstanceOf(InvalidCookbookException.class);
    }

    @Test
    void create_elevenIds_rejected() {
        assertThatThrownBy(() -> service.create(user, new CookbookCreateDto(ids(11), null)))
                .isInstanceOf(InvalidCookbookException.class);
    }

    @Test
    void create_duplicateIds_rejected() {
        List<UUID> four = ids(4);
        List<UUID> repeated = new ArrayList<>(four);
        repeated.add(four.get(0));
        assertThatThrownBy(() -> service.create(user, new CookbookCreateDto(repeated, null)))
                .isInstanceOf(InvalidCookbookException.class);
    }

    @Test
    void create_validates_beforeLockingTheUser() {
        assertThatThrownBy(() -> service.create(user, new CookbookCreateDto(ids(4), null)))
                .isInstanceOf(InvalidCookbookException.class);

        verify(users, never()).findForUpdateById(any());
    }

    @Test
    void create_at20Cookbooks_conflict() {
        when(cookbooks.countByUserId(user)).thenReturn(20L);

        assertThatThrownBy(() -> service.create(user, new CookbookCreateDto(ids(5), null)))
                .isInstanceOf(CookbookConflictException.class)
                .hasMessage("You can have up to 20 cookbooks.");
        verify(cookbooks, never()).saveAndFlush(any());
    }

    @Test
    void create_recipeNotLiked_conflict() {
        List<UUID> five = ids(5);
        allLikedAndApproved(five);
        when(likes.findLikedAmong(user, five)).thenReturn(five.subList(0, 4));

        assertThatThrownBy(() -> service.create(user, new CookbookCreateDto(five, null)))
                .isInstanceOf(CookbookConflictException.class)
                .hasMessage("Some of these recipes are no longer available.");
        verify(cookbooks, never()).saveAndFlush(any());
    }

    @Test
    void hiddenRecipe_cannotGoInABook() {
        List<UUID> five = ids(5);
        when(likes.findLikedAmong(user, five)).thenReturn(five);
        List<Recipe> found = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Recipe r = Recipe.builder().id(five.get(i)).title("T").status(RecipeStatus.APPROVED).build();
            if (i == 0) {
                r.setHiddenAt(java.time.OffsetDateTime.now());
            }
            found.add(r);
        }
        when(recipes.findAllById(five)).thenReturn(found);

        assertThatThrownBy(() -> service.create(user, new CookbookCreateDto(five, null)))
                .isInstanceOf(CookbookConflictException.class);
        verify(cookbooks, never()).saveAndFlush(any());
    }

    @Test
    void create_recipeNotApproved_conflict() {
        List<UUID> five = ids(5);
        when(likes.findLikedAmong(user, five)).thenReturn(five);
        List<Recipe> found = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            found.add(Recipe.builder().id(five.get(i)).title("T")
                    .status(i == 0 ? RecipeStatus.PENDING : RecipeStatus.APPROVED).build());
        }
        when(recipes.findAllById(five)).thenReturn(found);

        assertThatThrownBy(() -> service.create(user, new CookbookCreateDto(five, null)))
                .isInstanceOf(CookbookConflictException.class)
                .hasMessage("Some of these recipes are no longer available.");
        verify(cookbooks, never()).saveAndFlush(any());
    }

    @Test
    void create_recipeDeleted_conflict() {
        List<UUID> five = ids(5);
        when(likes.findLikedAmong(user, five)).thenReturn(five);
        when(recipes.findAllById(five)).thenReturn(five.stream().skip(1)
                .map(id -> Recipe.builder().id(id).title("T").status(RecipeStatus.APPROVED).build()).toList());

        assertThatThrownBy(() -> service.create(user, new CookbookCreateDto(five, null)))
                .isInstanceOf(CookbookConflictException.class);
    }

    @Test
    void create_volumeIsMaxPlusOne() {
        List<UUID> five = ids(5);
        allLikedAndApproved(five);
        when(cookbooks.findMaxVolume(user)).thenReturn(3);

        CookbookSummaryDto result = service.create(user, new CookbookCreateDto(five, null));

        assertThat(savedBook().getVolume()).isEqualTo(4);
        assertThat(result.volume()).isEqualTo(4);
        assertThat(result.recipeCount()).isEqualTo(5);
    }

    @Test
    void create_firstCookbook_isVolumeOne() {
        List<UUID> five = ids(5);
        allLikedAndApproved(five);

        service.create(user, new CookbookCreateDto(five, null));

        assertThat(savedBook().getVolume()).isEqualTo(1);
        assertThat(savedBook().getUserId()).isEqualTo(user);
        assertThat(savedBook().getKind()).isEqualTo("liked");
    }

    @Test
    @SuppressWarnings("unchecked")
    void create_positionsFollowRequestOrder() {
        List<UUID> five = ids(5);
        allLikedAndApproved(five);

        service.create(user, new CookbookCreateDto(five, null));

        ArgumentCaptor<Iterable<CookbookRecipe>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(chapters).saveAll(captor.capture());
        List<CookbookRecipe> rows = new ArrayList<>();
        captor.getValue().forEach(rows::add);
        assertThat(rows).extracting(r -> r.getId().getRecipeId()).containsExactlyElementsOf(five);
        assertThat(rows).extracting(CookbookRecipe::getPosition).containsExactly(
                (short) 1, (short) 2, (short) 3, (short) 4, (short) 5);
    }

    @Test
    void get_otherUsersCookbook_404() {
        UUID id = UUID.randomUUID();
        when(cookbooks.findByIdAndUserId(id, user)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(user, id)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void delete_otherUsersCookbook_404() {
        UUID id = UUID.randomUUID();
        when(cookbooks.findByIdAndUserId(id, user)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(user, id)).isInstanceOf(ResourceNotFoundException.class);
        verify(cookbooks, never()).delete(any());
    }

    @Test
    void delete_ownCookbook_deletes() {
        UUID id = UUID.randomUUID();
        Cookbook book = Cookbook.builder().id(id).userId(user).volume(1).build();
        when(cookbooks.findByIdAndUserId(id, user)).thenReturn(Optional.of(book));

        service.delete(user, id);

        verify(cookbooks).delete(book);
    }

    @Test
    void get_returnsChaptersInPositionOrder_andLiveOwnerName() {
        UUID id = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(cookbooks.findByIdAndUserId(id, user)).thenReturn(Optional.of(
                Cookbook.builder().id(id).userId(user).volume(2).subtitle("Sub").build()));
        when(chapters.findRecipeIdsInOrder(id)).thenReturn(List.of(first, second));
        when(recipes.findAllById(List.of(first, second))).thenReturn(List.of(
                Recipe.builder().id(second).title("Second").status(RecipeStatus.APPROVED).build(),
                Recipe.builder().id(first).title("First").status(RecipeStatus.APPROVED).build()));

        CookbookDto book = service.get(user, id);

        assertThat(book.ownerFirstName()).isEqualTo("Ana");
        assertThat(book.ownerLastName()).isEqualTo("Petrovska");
        assertThat(book.volume()).isEqualTo(2);
        assertThat(book.subtitle()).isEqualTo("Sub");
        assertThat(book.chapters()).extracting(SavedItemDto::kind).containsExactly("recipe", "recipe");
        assertThat(book.chapters()).extracting(c -> c.recipe().id()).containsExactly(first, second);
    }

    @Test
    void get_hiddenChapter_isAStub() {
        UUID id = UUID.randomUUID();
        UUID hidden = UUID.randomUUID();
        when(cookbooks.findByIdAndUserId(id, user)).thenReturn(Optional.of(
                Cookbook.builder().id(id).userId(user).volume(1).build()));
        when(chapters.findRecipeIdsInOrder(id)).thenReturn(List.of(hidden));
        when(recipes.findAllById(List.of(hidden))).thenReturn(List.of(
                Recipe.builder().id(hidden).title("New").status(RecipeStatus.PENDING)
                        .user(User.builder().id(UUID.randomUUID()).build())
                        .everApproved(true).approvedTitle("Old").build()));

        CookbookDto book = service.get(user, id);

        assertThat(book.chapters()).hasSize(1);
        assertThat(book.chapters().get(0).stub().approvedTitle()).isEqualTo("Old");
        assertThat(book.chapters().get(0).stub().state()).isEqualTo("under_review");
    }

    @Test
    void list_fillsRecipeCounts_newestVolumeFirst() {
        UUID older = UUID.randomUUID();
        UUID newer = UUID.randomUUID();
        when(cookbooks.findByUserIdOrderByVolumeDesc(user)).thenReturn(List.of(
                Cookbook.builder().id(newer).userId(user).volume(2).build(),
                Cookbook.builder().id(older).userId(user).volume(1).build()));
        when(chapters.countByCookbookIds(Arrays.asList(newer, older)))
                .thenReturn(List.of(new CookbookRecipeCount(newer, 7L)));

        List<CookbookSummaryDto> list = service.list(user);

        assertThat(list).extracting(CookbookSummaryDto::id).containsExactly(newer, older);
        assertThat(list).extracting(CookbookSummaryDto::recipeCount).containsExactly(7, 0);
    }
}
