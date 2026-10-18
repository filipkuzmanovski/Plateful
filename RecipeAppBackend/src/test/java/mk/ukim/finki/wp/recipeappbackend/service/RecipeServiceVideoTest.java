package mk.ukim.finki.wp.recipeappbackend.service;

import mk.ukim.finki.wp.recipeappbackend.cuisine.CuisineCatalog;
import mk.ukim.finki.wp.recipeappbackend.model.RecipeStatus;
import mk.ukim.finki.wp.recipeappbackend.model.createDTO.RecipeCreateDto;
import mk.ukim.finki.wp.recipeappbackend.model.createDTO.RecipeIngredientCreateDto;
import mk.ukim.finki.wp.recipeappbackend.model.createDTO.RecipeVideoCreateDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.RecipeDisplayDto;
import mk.ukim.finki.wp.recipeappbackend.model.entities.Recipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.RecipePhoto;
import mk.ukim.finki.wp.recipeappbackend.model.entities.User;
import mk.ukim.finki.wp.recipeappbackend.photo.InvalidVideoException;
import mk.ukim.finki.wp.recipeappbackend.photo.RecipePhotoPaths;
import mk.ukim.finki.wp.recipeappbackend.photo.RecipeVideoPaths;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeCommentRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeIngredientRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipePhotoRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeRatingRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecipeServiceVideoTest {

    private static final String PHOTO_BASE = "https://x/recipe-photos";
    private static final String VIDEO_BASE = "https://x/recipe-videos";

    private final UUID userId = UUID.randomUUID();
    private final RecipePhotoPaths photoPaths = new RecipePhotoPaths(PHOTO_BASE);
    private final RecipeVideoPaths videoPaths = new RecipeVideoPaths(VIDEO_BASE);
    private final String photo = userId + "/" + UUID.randomUUID() + ".webp";
    private final RecipeVideoCreateDto clipA = clip(42);
    private final RecipeVideoCreateDto clipB = clip(10);

    private RecipeRepository recipeRepository;
    private RecipeIngredientRepository ingredientRepository;
    private RecipePhotoRepository photoRepository;
    private RecipeVisibility visibility;
    private RecipeService service;
    private User owner;

    @BeforeEach
    void setUp() {
        recipeRepository = mock(RecipeRepository.class);
        ingredientRepository = mock(RecipeIngredientRepository.class);
        photoRepository = mock(RecipePhotoRepository.class);
        visibility = mock(RecipeVisibility.class);
        UserRepository userRepository = mock(UserRepository.class);
        service = new RecipeService(
                recipeRepository,
                ingredientRepository,
                mock(RecipeRatingRepository.class),
                mock(RecipeCommentRepository.class),
                userRepository,
                new CuisineCatalog(),
                photoRepository,
                photoPaths,
                videoPaths,
                visibility);
        owner = User.builder().id(userId).build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(owner));
        when(recipeRepository.save(any(Recipe.class))).thenAnswer(inv -> inv.getArgument(0));
        when(ingredientRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(photoRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private RecipeVideoCreateDto clip(int seconds) {
        return new RecipeVideoCreateDto(
                userId + "/" + UUID.randomUUID() + ".mp4",
                userId + "/" + UUID.randomUUID() + ".jpg",
                seconds);
    }

    private static RecipeCreateDto dto(List<String> photos, RecipeVideoCreateDto video) {
        return new RecipeCreateDto("Title", 2, null, null, null, null, null,
                false, false, false, false, null, photos,
                List.of(new RecipeIngredientCreateDto("salt", "1 tsp salt")), video);
    }

    private Recipe existing(UUID recipeId) {
        Recipe r = Recipe.builder().id(recipeId).user(owner).title("Old").revision(3)
                .status(RecipeStatus.APPROVED).build();
        when(recipeRepository.findForReviewById(recipeId)).thenReturn(Optional.of(r));
        return r;
    }

    @Test
    void createWithVideoStoresAndReturnsIt() {
        RecipeDisplayDto result = service.create(userId, dto(List.of(photo), clipA));

        ArgumentCaptor<Recipe> saved = ArgumentCaptor.forClass(Recipe.class);
        verify(recipeRepository).save(saved.capture());
        assertThat(saved.getValue().getVideoPath()).isEqualTo(clipA.path());
        assertThat(saved.getValue().getVideoPosterPath()).isEqualTo(clipA.posterPath());
        assertThat(saved.getValue().getVideoDurationSeconds()).isEqualTo(42);
        assertThat(result.video().url()).isEqualTo(VIDEO_BASE + "/" + clipA.path());
        assertThat(result.video().posterUrl()).isEqualTo(VIDEO_BASE + "/" + clipA.posterPath());
        assertThat(result.video().durationSeconds()).isEqualTo(42);
    }

    @Test
    void createWithoutVideoReturnsNullVideo() {
        RecipeDisplayDto result = service.create(userId, dto(List.of(photo), null));

        ArgumentCaptor<Recipe> saved = ArgumentCaptor.forClass(Recipe.class);
        verify(recipeRepository).save(saved.capture());
        assertThat(saved.getValue().getVideoPath()).isNull();
        assertThat(result.video()).isNull();
    }

    @Test
    void updateAddsReplacesAndRemovesVideo() {
        UUID recipeId = UUID.randomUUID();
        Recipe recipe = existing(recipeId);
        when(photoRepository.existsByRecipeId(recipeId)).thenReturn(true);

        RecipeDisplayDto added = service.update(userId, recipeId, dto(List.of(photo), clipA));
        assertThat(recipe.getVideoPath()).isEqualTo(clipA.path());
        assertThat(added.video().durationSeconds()).isEqualTo(42);

        RecipeDisplayDto replaced = service.update(userId, recipeId, dto(List.of(photo), clipB));
        assertThat(recipe.getVideoPath()).isEqualTo(clipB.path());
        assertThat(recipe.getVideoPosterPath()).isEqualTo(clipB.posterPath());
        assertThat(recipe.getVideoDurationSeconds()).isEqualTo(10);
        assertThat(replaced.video().url()).isEqualTo(VIDEO_BASE + "/" + clipB.path());

        RecipeDisplayDto removed = service.update(userId, recipeId, dto(List.of(photo), null));
        assertThat(recipe.getVideoPath()).isNull();
        assertThat(recipe.getVideoPosterPath()).isNull();
        assertThat(recipe.getVideoDurationSeconds()).isNull();
        assertThat(removed.video()).isNull();
    }

    @Test
    void videoOnlyEditGoesBackToReview() {
        UUID recipeId = UUID.randomUUID();
        Recipe recipe = existing(recipeId);
        when(photoRepository.existsByRecipeId(recipeId)).thenReturn(true);
        when(visibility.isAdmin(userId)).thenReturn(false);

        service.update(userId, recipeId, dto(List.of(), clipA));

        assertThat(recipe.getRevision()).isEqualTo(4);
        assertThat(recipe.getStatus()).isEqualTo(RecipeStatus.PENDING);
    }

    @Test
    void noPhotosWithVideoUsesPosterAsImage() {
        RecipeDisplayDto created = service.create(userId, dto(List.of(), clipA));

        ArgumentCaptor<Recipe> saved = ArgumentCaptor.forClass(Recipe.class);
        verify(recipeRepository).save(saved.capture());
        String posterUrl = VIDEO_BASE + "/" + clipA.posterPath();
        assertThat(saved.getValue().getImage()).isEqualTo(posterUrl);
        assertThat(created.image()).isEqualTo(posterUrl);
        // The poster is the card image, not a photo: the collage must not show it twice.
        assertThat(created.photos()).isEmpty();
    }

    @Test
    void legacyLinkedImageIsReplacedByPosterWhenVideoIsSet() {
        UUID recipeId = UUID.randomUUID();
        Recipe recipe = existing(recipeId);
        recipe.setImage("https://old.example/p.jpg");
        when(photoRepository.existsByRecipeId(recipeId)).thenReturn(false);

        service.update(userId, recipeId, dto(List.of(), clipA));

        assertThat(recipe.getImage()).isEqualTo(VIDEO_BASE + "/" + clipA.posterPath());
        verify(photoRepository, never()).deleteAllByRecipeId(any());
    }

    @Test
    void legacyLinkedImageIsKeptWithoutVideo() {
        UUID recipeId = UUID.randomUUID();
        Recipe recipe = existing(recipeId);
        recipe.setImage("https://old.example/p.jpg");
        when(photoRepository.existsByRecipeId(recipeId)).thenReturn(false);

        RecipeDisplayDto result = service.update(userId, recipeId, dto(List.of(), null));

        assertThat(recipe.getImage()).isEqualTo("https://old.example/p.jpg");
        assertThat(result.photos()).containsExactly("https://old.example/p.jpg");
    }

    @Test
    void removingLastPhotoFallsBackToPoster() {
        UUID recipeId = UUID.randomUUID();
        Recipe recipe = existing(recipeId);
        recipe.setImage(photoPaths.publicUrl(photo));
        recipe.setVideoPath(clipA.path());
        recipe.setVideoPosterPath(clipA.posterPath());
        recipe.setVideoDurationSeconds(42);
        when(photoRepository.existsByRecipeId(recipeId)).thenReturn(true);

        service.update(userId, recipeId, dto(List.of(), clipA));

        verify(photoRepository).deleteAllByRecipeId(recipeId);
        assertThat(recipe.getImage()).isEqualTo(VIDEO_BASE + "/" + clipA.posterPath());
    }

    @Test
    void removingTheVideoOfAPhotolessRecipeClearsThePosterImage() {
        UUID recipeId = UUID.randomUUID();
        Recipe recipe = existing(recipeId);
        recipe.setImage(VIDEO_BASE + "/" + clipA.posterPath());
        recipe.setVideoPath(clipA.path());
        recipe.setVideoPosterPath(clipA.posterPath());
        recipe.setVideoDurationSeconds(42);
        when(photoRepository.existsByRecipeId(recipeId)).thenReturn(false);

        RecipeDisplayDto result = service.update(userId, recipeId, dto(List.of(), null));

        assertThat(recipe.getImage()).isNull();
        assertThat(recipe.getVideoPath()).isNull();
        assertThat(result.photos()).isEmpty();
    }

    @Test
    void invalidVideoLeavesRecipeUntouched() {
        UUID recipeId = UUID.randomUUID();
        Recipe recipe = existing(recipeId);
        recipe.setVideoPath(clipB.path());
        recipe.setVideoPosterPath(clipB.posterPath());
        recipe.setVideoDurationSeconds(10);
        RecipeVideoCreateDto foreign = new RecipeVideoCreateDto(
                UUID.randomUUID() + "/" + UUID.randomUUID() + ".mp4", clipA.posterPath(), 5);

        assertThatThrownBy(() -> service.update(userId, recipeId, dto(List.of(photo), foreign)))
                .isInstanceOf(InvalidVideoException.class);

        assertThat(recipe.getRevision()).isEqualTo(3);
        assertThat(recipe.getTitle()).isEqualTo("Old");
        assertThat(recipe.getStatus()).isEqualTo(RecipeStatus.APPROVED);
        assertThat(recipe.getVideoPath()).isEqualTo(clipB.path());
        assertThat(recipe.getVideoPosterPath()).isEqualTo(clipB.posterPath());
        assertThat(recipe.getVideoDurationSeconds()).isEqualTo(10);
        verify(ingredientRepository, never()).deleteByRecipeId(any());
    }

    @Test
    void createRejectsInvalidVideoBeforeSaving() {
        RecipeVideoCreateDto tooLong = new RecipeVideoCreateDto(clipA.path(), clipA.posterPath(), 61);

        assertThatThrownBy(() -> service.create(userId, dto(List.of(photo), tooLong)))
                .isInstanceOf(InvalidVideoException.class);

        verify(recipeRepository, never()).save(any(Recipe.class));
    }

    @Test
    void detailViewCarriesVideo() {
        UUID recipeId = UUID.randomUUID();
        Recipe recipe = Recipe.builder().id(recipeId).title("A")
                .videoPath(clipA.path()).videoPosterPath(clipA.posterPath()).videoDurationSeconds(42).build();
        when(recipeRepository.findById(recipeId)).thenReturn(Optional.of(recipe));

        RecipeDisplayDto result = service.getById(recipeId, null);

        assertThat(result.video().url()).isEqualTo(VIDEO_BASE + "/" + clipA.path());
        assertThat(result.video().durationSeconds()).isEqualTo(42);
    }

    @Test
    void pageAssemblyCarriesVideo() {
        Recipe withVideo = Recipe.builder().id(UUID.randomUUID()).title("A")
                .videoPath(clipA.path()).videoPosterPath(clipA.posterPath()).videoDurationSeconds(42).build();
        Recipe withoutVideo = Recipe.builder().id(UUID.randomUUID()).title("B").build();
        Pageable pageable = PageRequest.of(0, 12);
        when(photoRepository.findByRecipeIdInOrderByPositionAsc(anyCollection())).thenReturn(List.<RecipePhoto>of());

        List<RecipeDisplayDto> content = service
                .toDisplayPage(new PageImpl<>(List.of(withVideo, withoutVideo), pageable, 2)).getContent();

        assertThat(content.get(0).video().url()).isEqualTo(VIDEO_BASE + "/" + clipA.path());
        assertThat(content.get(0).video().posterUrl()).isEqualTo(VIDEO_BASE + "/" + clipA.posterPath());
        assertThat(content.get(0).video().durationSeconds()).isEqualTo(42);
        assertThat(content.get(1).video()).isNull();
        // The columns live on recipes: carrying the video costs no extra query.
        verify(photoRepository, times(1)).findByRecipeIdInOrderByPositionAsc(anyCollection());
        verify(ingredientRepository, times(1)).findByRecipeIdInOrderBySortOrderAsc(anyCollection());
    }
}
