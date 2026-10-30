package mk.ukim.finki.wp.recipeappbackend.service;

import mk.ukim.finki.wp.recipeappbackend.cuisine.CuisineCatalog;
import mk.ukim.finki.wp.recipeappbackend.cuisine.InvalidCuisineException;
import mk.ukim.finki.wp.recipeappbackend.exception.InvalidPreferencesException;
import mk.ukim.finki.wp.recipeappbackend.exception.ResourceNotFoundException;
import mk.ukim.finki.wp.recipeappbackend.food.FoodRules;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.PreferencesDto;
import mk.ukim.finki.wp.recipeappbackend.model.entities.UserPreferences;
import mk.ukim.finki.wp.recipeappbackend.repository.UserPreferencesRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PreferencesServiceTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private UserPreferencesRepository repository;
    private PreferencesService service;

    @BeforeEach
    void setUp() {
        repository = mock(UserPreferencesRepository.class);
        when(repository.save(any(UserPreferences.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new PreferencesService(repository, new FoodRules(), new CuisineCatalog());
    }

    private static PreferencesDto dto(List<String> allergens, List<String> diets, List<String> disliked,
                                      List<String> liked, List<String> cuisines) {
        return new PreferencesDto(allergens, diets, disliked, liked, cuisines);
    }

    private static PreferencesDto onlyDisliked(List<String> disliked) {
        return dto(List.of(), List.of(), disliked, List.of(), List.of());
    }

    /** n distinct names that survive normalisation (letters only). */
    private static List<String> distinctNames(int n) {
        return IntStream.range(0, n)
                .mapToObj(i -> "item " + (char) ('a' + i / 26) + (char) ('a' + i % 26))
                .toList();
    }

    @Test
    void getWithoutRowThrowsNotFound() {
        when(repository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(USER_ID)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getReturnsTheStoredPreferences() {
        UserPreferences stored = UserPreferences.builder().userId(USER_ID)
                .allergens(List.of("milk")).diets(List.of("vegan")).dislikedIngredients(List.of("mushroom"))
                .likedIngredients(List.of("garlic")).likedCuisines(List.of("italian")).build();
        when(repository.findById(USER_ID)).thenReturn(Optional.of(stored));

        assertThat(service.get(USER_ID)).isEqualTo(
                new PreferencesDto(List.of("milk"), List.of("vegan"), List.of("mushroom"),
                        List.of("garlic"), List.of("italian")));
    }

    @Test
    void saveNormalisesAndDeduplicatesIngredientNames() {
        when(repository.findById(USER_ID)).thenReturn(Optional.empty());

        PreferencesDto saved = service.save(USER_ID,
                dto(List.of(), List.of(), List.of("Mushrooms", "mushroom", "  "),
                        List.of("Garlic Cloves", "garlic clove"), List.of()));

        assertThat(saved.dislikedIngredients()).containsExactly("mushroom");
        assertThat(saved.likedIngredients()).containsExactly("garlic clove");
    }

    @Test
    void saveStoresAllergensAndDietsDeduplicatedInRequestOrder() {
        when(repository.findById(USER_ID)).thenReturn(Optional.empty());

        PreferencesDto saved = service.save(USER_ID,
                dto(List.of("peanuts", "milk", "peanuts"), List.of("vegan", "gluten_free", "vegan"),
                        List.of(), List.of(), List.of()));

        assertThat(saved.allergens()).containsExactly("peanuts", "milk");
        assertThat(saved.diets()).containsExactly("vegan", "gluten_free");
    }

    @Test
    void saveNormalisesCuisineKeys() {
        when(repository.findById(USER_ID)).thenReturn(Optional.empty());

        PreferencesDto saved = service.save(USER_ID,
                dto(List.of(), List.of(), List.of(), List.of(), List.of(" Italian ", "ITALIAN", "greek")));

        assertThat(saved.likedCuisines()).containsExactly("italian", "greek");
    }

    @Test
    void nullListsAreTreatedAsEmpty() {
        when(repository.findById(USER_ID)).thenReturn(Optional.empty());

        PreferencesDto saved = service.save(USER_ID, new PreferencesDto(null, null, null, null, null));

        assertThat(saved).isEqualTo(new PreferencesDto(List.of(), List.of(), List.of(), List.of(), List.of()));
    }

    @Test
    void unknownAllergenIsRejected() {
        assertThatThrownBy(() -> service.save(USER_ID,
                dto(List.of("nuts"), List.of(), List.of(), List.of(), List.of())))
                .isInstanceOf(InvalidPreferencesException.class)
                .hasMessage("Unknown allergen: nuts");
    }

    @Test
    void unknownDietIsRejected() {
        assertThatThrownBy(() -> service.save(USER_ID,
                dto(List.of(), List.of("keto"), List.of(), List.of(), List.of())))
                .isInstanceOf(InvalidPreferencesException.class)
                .hasMessage("Unknown diet: keto");
    }

    @Test
    void moreThanThirtyDislikesAreRejected() {
        assertThatThrownBy(() -> service.save(USER_ID, onlyDisliked(distinctNames(31))))
                .isInstanceOf(InvalidPreferencesException.class)
                .hasMessage("At most 30 disliked ingredients");
    }

    @Test
    void exactlyThirtyDislikesAreAccepted() {
        when(repository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThat(service.save(USER_ID, onlyDisliked(distinctNames(30))).dislikedIngredients()).hasSize(30);
    }

    @Test
    void duplicatesDoNotCountTowardsTheLimit() {
        when(repository.findById(USER_ID)).thenReturn(Optional.empty());
        List<String> names = new ArrayList<>(distinctNames(30));
        names.add("Item AA");

        assertThat(service.save(USER_ID, onlyDisliked(names)).dislikedIngredients()).hasSize(30);
    }

    @Test
    void moreThanThirtyLikesAreRejected() {
        assertThatThrownBy(() -> service.save(USER_ID,
                dto(List.of(), List.of(), List.of(), distinctNames(31), List.of())))
                .isInstanceOf(InvalidPreferencesException.class)
                .hasMessage("At most 30 liked ingredients");
    }

    @Test
    void moreThanTwentyCuisinesAreRejected() {
        List<String> keys = new CuisineCatalog().all().stream().limit(21).map(e -> e.key()).toList();

        assertThatThrownBy(() -> service.save(USER_ID, dto(List.of(), List.of(), List.of(), List.of(), keys)))
                .isInstanceOf(InvalidPreferencesException.class)
                .hasMessage("At most 20 cuisines");
    }

    @Test
    void unknownCuisinePassesThroughInvalidCuisineException() {
        assertThatThrownBy(() -> service.save(USER_ID,
                dto(List.of(), List.of(), List.of(), List.of(), List.of("atlantis"))))
                .isInstanceOf(InvalidCuisineException.class);
    }

    @Test
    void invalidInputIsNeverSaved() {
        assertThatThrownBy(() -> service.save(USER_ID,
                dto(List.of("nuts"), List.of(), List.of(), List.of(), List.of())))
                .isInstanceOf(InvalidPreferencesException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void savingTwiceUpdatesTheSameEntity() {
        UserPreferences existing = UserPreferences.builder().userId(USER_ID).build();
        when(repository.findById(USER_ID)).thenReturn(Optional.of(existing));

        service.save(USER_ID, onlyDisliked(List.of("olives")));
        service.save(USER_ID, onlyDisliked(List.of("capers")));

        verify(repository, times(2)).save(existing);
        assertThat(existing.getDislikedIngredients()).containsExactly("caper");
        assertThat(existing.getUserId()).isEqualTo(USER_ID);
    }

    @Test
    void savingAnExistingRowReusesItAndSavesOnce() {
        UserPreferences existing = UserPreferences.builder().userId(USER_ID).allergens(List.of("milk")).build();
        when(repository.findById(USER_ID)).thenReturn(Optional.of(existing));

        PreferencesDto saved = service.save(USER_ID,
                dto(List.of("eggs"), List.of(), List.of(), List.of(), List.of()));

        verify(repository, times(1)).save(existing);
        assertThat(existing.getAllergens()).containsExactly("eggs");
        assertThat(saved.allergens()).containsExactly("eggs");
    }
}
