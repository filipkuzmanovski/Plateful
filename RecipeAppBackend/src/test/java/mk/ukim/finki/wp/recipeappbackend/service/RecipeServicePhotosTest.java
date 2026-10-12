package mk.ukim.finki.wp.recipeappbackend.service;

import mk.ukim.finki.wp.recipeappbackend.cuisine.CuisineCatalog;
import mk.ukim.finki.wp.recipeappbackend.exception.ForbiddenOperationException;
import mk.ukim.finki.wp.recipeappbackend.model.createDTO.RecipeCreateDto;
import mk.ukim.finki.wp.recipeappbackend.model.createDTO.RecipeIngredientCreateDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.RecipeDisplayDto;
import mk.ukim.finki.wp.recipeappbackend.model.entities.Recipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.RecipePhoto;
import mk.ukim.finki.wp.recipeappbackend.model.entities.User;
import mk.ukim.finki.wp.recipeappbackend.photo.InvalidPhotoException;
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
import org.mockito.InOrder;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecipeServicePhotosTest {

    private static final String BASE = "https://x/recipe-photos";

    private final UUID userId = UUID.randomUUID();
    private final RecipePhotoPaths paths = new RecipePhotoPaths(BASE);
    private final String p1 = userId + "/" + UUID.randomUUID() + ".webp";
    private final String p2 = userId + "/" + UUID.randomUUID() + ".jpg";

    private RecipeRepository recipeRepository;
    private RecipeIngredientRepository ingredientRepository;
    private RecipePhotoRepository photoRepository;
    private UserRepository userRepository;
    private RecipeService service;
    private User owner;

    @BeforeEach
    void setUp() {
        recipeRepository = mock(RecipeRepository.class);
        ingredientRepository = mock(RecipeIngredientRepository.class);
        photoRepository = mock(RecipePhotoRepository.class);
        userRepository = mock(UserRepository.class);
        service = new RecipeService(
                recipeRepository,
                ingredientRepository,
                mock(RecipeRatingRepository.class),
                mock(RecipeCommentRepository.class),
                userRepository,
                new CuisineCatalog(),
                photoRepository,
                paths);
        owner = User.builder().id(userId).build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(owner));
        when(recipeRepository.save(any(Recipe.class))).thenAnswer(inv -> inv.getArgument(0));
        when(ingredientRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(photoRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static RecipeCreateDto dto(List<String> photos) {
        return new RecipeCreateDto("Title", 2, null, null, null, null, null,
                false, false, false, false, null, photos,
                List.of(new RecipeIngredientCreateDto("salt", "1 tsp salt")));
    }

    private Recipe existing(UUID recipeId, String image) {
        Recipe r = Recipe.builder().id(recipeId).user(owner).title("Old").image(image).build();
        when(recipeRepository.findById(recipeId)).thenReturn(Optional.of(r));
        return r;
    }

    @SuppressWarnings("unchecked")
    private List<RecipePhoto> savedPhotos() {
        ArgumentCaptor<List<RecipePhoto>> captor = ArgumentCaptor.forClass(List.class);
        verify(photoRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    @Test
    void createStoresPhotosInOrderAndSetsCover() {
        RecipeDisplayDto result = service.create(userId, dto(List.of(p1, p2)));

        List<RecipePhoto> saved = savedPhotos();
        assertThat(saved).extracting(RecipePhoto::getPosition).containsExactly(0, 1);
        assertThat(saved).extracting(RecipePhoto::getPath).containsExactly(p1, p2);
        ArgumentCaptor<Recipe> recipe = ArgumentCaptor.forClass(Recipe.class);
        verify(recipeRepository).save(recipe.capture());
        assertThat(recipe.getValue().getImage()).isEqualTo(paths.publicUrl(p1));
        assertThat(result.photos()).containsExactly(paths.publicUrl(p1), paths.publicUrl(p2));
    }

    @Test
    void createLeavesSourceFieldsNull() {
        service.create(userId, dto(List.of(p1)));

        ArgumentCaptor<Recipe> recipe = ArgumentCaptor.forClass(Recipe.class);
        verify(recipeRepository).save(recipe.capture());
        assertThat(recipe.getValue().getSourceName()).isNull();
        assertThat(recipe.getValue().getSourceUrl()).isNull();
    }

    @Test
    void createRejectsForeignPath() {
        String foreign = UUID.randomUUID() + "/" + UUID.randomUUID() + ".webp";

        assertThatThrownBy(() -> service.create(userId, dto(List.of(foreign))))
                .isInstanceOf(InvalidPhotoException.class);

        verify(recipeRepository, never()).save(any(Recipe.class));
    }

    @Test
    void updateReplacesPhotos() {
        UUID recipeId = UUID.randomUUID();
        Recipe recipe = existing(recipeId, "https://old/c.webp");
        when(photoRepository.existsByRecipeId(recipeId)).thenReturn(true);

        service.update(userId, recipeId, dto(List.of(p2, p1)));

        InOrder order = inOrder(photoRepository);
        order.verify(photoRepository).deleteAllByRecipeId(recipeId);
        order.verify(photoRepository).saveAll(any());
        assertThat(recipe.getImage()).isEqualTo(paths.publicUrl(p2));
        assertThat(recipe.getSourceName()).isNull();
        assertThat(recipe.getSourceUrl()).isNull();
    }

    @Test
    void updateWithEmptyListClearsImageWhenRecipeHadPhotos() {
        UUID recipeId = UUID.randomUUID();
        Recipe recipe = existing(recipeId, paths.publicUrl(p1));
        when(photoRepository.existsByRecipeId(recipeId)).thenReturn(true);

        service.update(userId, recipeId, dto(List.of()));

        verify(photoRepository).deleteAllByRecipeId(recipeId);
        assertThat(recipe.getImage()).isNull();
    }

    @Test
    void updateKeepsLegacyLinkedImage() {
        UUID recipeId = UUID.randomUUID();
        Recipe recipe = existing(recipeId, "https://old.example/p.jpg");
        when(photoRepository.existsByRecipeId(recipeId)).thenReturn(false);

        RecipeDisplayDto result = service.update(userId, recipeId, dto(List.of()));

        assertThat(recipe.getImage()).isEqualTo("https://old.example/p.jpg");
        verify(photoRepository, never()).deleteAllByRecipeId(any());
        assertThat(result.photos()).containsExactly("https://old.example/p.jpg");
    }

    @Test
    void updateByNonOwnerIs403BeforePhotoValidation() {
        UUID recipeId = UUID.randomUUID();
        existing(recipeId, null);

        assertThatThrownBy(() -> service.update(UUID.randomUUID(), recipeId, dto(List.of("bad/path"))))
                .isInstanceOf(ForbiddenOperationException.class);
    }

    @Test
    void displayFallsBackToImageForImportedRecipe() {
        UUID withImage = UUID.randomUUID();
        UUID without = UUID.randomUUID();
        when(recipeRepository.findById(withImage))
                .thenReturn(Optional.of(Recipe.builder().id(withImage).title("A").image("https://img/x.jpg").build()));
        when(recipeRepository.findById(without))
                .thenReturn(Optional.of(Recipe.builder().id(without).title("B").build()));
        when(photoRepository.findByRecipeIdOrderByPositionAsc(any())).thenReturn(List.of());

        assertThat(service.getById(withImage).photos()).containsExactly("https://img/x.jpg");
        assertThat(service.getById(without).photos()).isEmpty();
    }

    @Test
    void browseLoadsPhotosInOneBatch() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        Recipe ra = Recipe.builder().id(a).title("A").build();
        Recipe rb = Recipe.builder().id(b).title("B").build();
        Pageable pageable = PageRequest.of(0, 12);
        when(recipeRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(ra, rb), pageable, 2));
        when(photoRepository.findByRecipeIdInOrderByPositionAsc(anyCollection())).thenReturn(List.of(
                RecipePhoto.builder().recipe(ra).path(p1).position(0).build(),
                RecipePhoto.builder().recipe(ra).path(p2).position(1).build(),
                RecipePhoto.builder().recipe(rb).path(p2).position(0).build()));

        List<RecipeDisplayDto> content = service.browse(null, pageable).getContent();

        verify(photoRepository, times(1)).findByRecipeIdInOrderByPositionAsc(anyCollection());
        verify(photoRepository, never()).findByRecipeIdOrderByPositionAsc(any());
        assertThat(content.get(0).photos()).containsExactly(paths.publicUrl(p1), paths.publicUrl(p2));
        assertThat(content.get(1).photos()).containsExactly(paths.publicUrl(p2));
    }
}
