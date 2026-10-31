package mk.ukim.finki.wp.recipeappbackend.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import mk.ukim.finki.wp.recipeappbackend.food.FoodRules;
import mk.ukim.finki.wp.recipeappbackend.model.projection.Pairing;
import mk.ukim.finki.wp.recipeappbackend.model.projection.ReferenceQuery;
import mk.ukim.finki.wp.recipeappbackend.model.projection.ReferenceRecipe;
import mk.ukim.finki.wp.recipeappbackend.repository.PlatefulComparisonRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.ReferenceDataRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ReferenceFinderTest {

    private final ReferenceDataRepository reference = mock(ReferenceDataRepository.class);
    private final PlatefulComparisonRepository plateful = mock(PlatefulComparisonRepository.class);
    private final SafetyCheck safety = new SafetyCheck(new FoodRules(), new PantryStaples());
    private final ReferenceFinder finder = new ReferenceFinder(reference, plateful, safety);

    private static UserFoodProfile profile(Set<String> allergens, Set<String> diets, Set<String> dislikes,
                                           Set<String> liked) {
        return new UserFoodProfile(allergens, diets, dislikes, liked, List.of());
    }

    private static UserFoodProfile noRestrictions() {
        return profile(Set.of(), Set.of(), Set.of(), Set.of());
    }

    private static ReferenceRecipe recipe(long id, List<String> lines, List<String> names) {
        return new ReferenceRecipe(id, "Recipe " + id, lines, names, List.of("Cook."), List.of("dinner"), 1);
    }

    private static ReferenceRecipe plain(long id) {
        return recipe(id, List.of("1 tomato"), List.of("tomato", "basil"));
    }

    @Test
    void inspiration_passesFiltersAndTheCandidateLimitToTheRepository() {
        when(reference.findClosest(any())).thenReturn(List.of());
        UserFoodProfile p = profile(Set.of("milk"), Set.of("vegetarian"), Set.of("olive"), Set.of());

        finder.inspiration(Set.of("tomato"), "dinner", p, 6);

        ArgumentCaptor<ReferenceQuery> captor = ArgumentCaptor.forClass(ReferenceQuery.class);
        verify(reference).findClosest(captor.capture());
        ReferenceQuery q = captor.getValue();
        assertThat(q.wanted()).containsExactly("tomato");
        assertThat(q.mealType()).isEqualTo("dinner");
        assertThat(q.allergens()).containsExactly("milk");
        assertThat(q.diets()).containsExactly("vegetarian");
        assertThat(q.dislikes()).containsExactly("olive");
        assertThat(q.limit()).isEqualTo(20);
    }

    @Test
    void inspiration_dropsCandidateTheCurrentRulesFlag() {
        // The frozen tags missed the ghee (the SQL filter let it through); the Java re-check must not.
        ReferenceRecipe ghee = recipe(1, List.of("2 tbsp ghee", "1 tomato"), List.of("ghee", "tomato"));
        ReferenceRecipe fine = plain(2);
        when(reference.findClosest(any())).thenReturn(List.of(ghee, fine));

        List<ReferenceRecipe> result = finder.inspiration(Set.of("tomato"), "dinner",
                profile(Set.of("milk"), Set.of(), Set.of(), Set.of()), 6);

        assertThat(result).extracting(ReferenceRecipe::id).containsExactly(2L);
    }

    @Test
    void inspiration_dropsCandidateWhoseIngredientLineHoldsAnAllergenTheTagMissed() {
        // allergens tag empty and the names clean, but the line "1 cup peanuts/almonds" was glued at import.
        ReferenceRecipe mistagged = recipe(1, List.of("1 cup peanuts/almonds", "1 tomato"),
                List.of("tomato", "basil"));
        ReferenceRecipe fine = plain(2);
        when(reference.findClosest(any())).thenReturn(List.of(mistagged, fine));

        List<ReferenceRecipe> result = finder.inspiration(Set.of("tomato"), "dinner",
                profile(Set.of("peanuts"), Set.of(), Set.of(), Set.of()), 6);

        assertThat(result).extracting(ReferenceRecipe::id).containsExactly(2L);
    }

    @Test
    void inspiration_dropsCandidateWithADislikeOrDietHitInALine() {
        ReferenceRecipe disliked = recipe(1, List.of("1 cup chopped olives"), List.of("tomato"));
        ReferenceRecipe meaty = recipe(2, List.of("200 g chicken breast"), List.of("tomato"));
        ReferenceRecipe fine = plain(3);
        when(reference.findClosest(any())).thenReturn(List.of(disliked, meaty, fine));

        List<ReferenceRecipe> result = finder.inspiration(Set.of("tomato"), "dinner",
                profile(Set.of(), Set.of("vegetarian"), Set.of("olive"), Set.of()), 6);

        assertThat(result).extracting(ReferenceRecipe::id).containsExactly(3L);
    }

    @Test
    void inspiration_keepsAtMostSix() {
        List<ReferenceRecipe> twenty = LongStream.rangeClosed(1, 20)
                .mapToObj(ReferenceFinderTest::plain).toList();
        when(reference.findClosest(any())).thenReturn(twenty);

        List<ReferenceRecipe> result = finder.inspiration(Set.of("tomato"), "dinner", noRestrictions(), 6);

        assertThat(result).extracting(ReferenceRecipe::id).containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
    }

    @Test
    void inspiration_failsClosedOnAnUnknownAllergenKey() {
        when(reference.findClosest(any())).thenReturn(List.of(plain(1)));

        assertThatThrownBy(() -> finder.inspiration(Set.of("tomato"), "dinner",
                profile(Set.of("nope"), Set.of(), Set.of(), Set.of()), 6))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dailyInspiration_topsUpWithRandom() {
        UserFoodProfile p = profile(Set.of("milk"), Set.of(), Set.of(), Set.of("tomato"));
        when(reference.findClosest(any())).thenReturn(List.of(plain(1)));
        when(reference.randomForMeal(eq("lunch"), anySet(), anySet(), anySet(), anyInt()))
                .thenReturn(List.of(plain(1), recipe(7, List.of("1 tbsp ghee"), List.of("ghee")), plain(8),
                        plain(9)));

        List<ReferenceRecipe> result = finder.dailyInspiration("lunch", p, 3);

        // One from the liked ingredients, then random ones: the duplicate id 1 and the ghee one are skipped.
        assertThat(result).extracting(ReferenceRecipe::id).containsExactly(1L, 8L, 9L);
        verify(reference).randomForMeal("lunch", Set.of("milk"), Set.of(), Set.of(), 20);
    }

    @Test
    void dailyInspiration_withNoLikedIngredientsIsAllRandomAndSkipsFindClosest() {
        when(reference.randomForMeal(eq("breakfast"), anySet(), anySet(), anySet(), anyInt()))
                .thenReturn(List.of(plain(4), plain(5), plain(6), plain(7)));

        List<ReferenceRecipe> result = finder.dailyInspiration("breakfast", noRestrictions(), 3);

        assertThat(result).extracting(ReferenceRecipe::id).containsExactly(4L, 5L, 6L);
        verify(reference, never()).findClosest(any());
    }

    @Test
    void dailyInspiration_doesNotQueryRandomWhenLikedIngredientsFillTheQuota() {
        UserFoodProfile p = profile(Set.of(), Set.of(), Set.of(), Set.of("tomato"));
        when(reference.findClosest(any())).thenReturn(List.of(plain(1), plain(2), plain(3), plain(4)));

        List<ReferenceRecipe> result = finder.dailyInspiration("dinner", p, 3);

        assertThat(result).extracting(ReferenceRecipe::id).containsExactly(1L, 2L, 3L);
        verify(reference, never()).randomForMeal(anyString(), anySet(), anySet(), anySet(), anyInt());
    }

    @Test
    void pairings_skipForbiddenPartners() {
        when(reference.pairings(eq("tomato"), anyInt())).thenReturn(List.of(
                new Pairing("basil", 40), new Pairing("butter", 30), new Pairing("mozzarella", 25),
                new Pairing("olive", 20)));
        UserFoodProfile p = profile(Set.of("milk"), Set.of(), Set.of("olive"), Set.of());

        Map<String, List<Pairing>> result = finder.pairings(List.of("tomato"), p);

        assertThat(result).containsOnlyKeys("tomato");
        assertThat(result.get("tomato")).extracting(Pairing::partner).containsExactly("basil");
    }

    @Test
    void pairings_limitedToFiveNamesAndEightPartnersEach() {
        List<Pairing> many = IntStream.range(0, 30)
                .mapToObj(i -> new Pairing("partner" + (char) ('a' + i % 26) + i, 50 - i)).toList();
        when(reference.pairings(anyString(), anyInt())).thenReturn(many);

        Map<String, List<Pairing>> result = finder.pairings(
                List.of("a", "b", "c", "d", "e", "f", "g"), noRestrictions());

        assertThat(result).containsOnlyKeys("a", "b", "c", "d", "e");
        assertThat(result.values()).allSatisfy(list -> assertThat(list).hasSize(8));
        assertThat(result.get("a")).isEqualTo(many.subList(0, 8));
    }

    @Test
    void pairings_omitsNamesWithoutPartners() {
        when(reference.pairings(eq("tomato"), anyInt())).thenReturn(List.of(new Pairing("basil", 40)));
        when(reference.pairings(eq("zzz"), anyInt())).thenReturn(List.of());

        Map<String, List<Pairing>> result = finder.pairings(List.of("tomato", "zzz"), noRestrictions());

        assertThat(result).containsOnlyKeys("tomato");
    }

    @Test
    void comparisonSet_combinesExamplesClosestAndPlateful() {
        ReferenceRecipe example = recipe(1, List.of("1 tomato"), List.of("tomato", "basil"));
        ReferenceRecipe closest = recipe(2, List.of("1 egg"), List.of("egg", "tomato"));
        when(reference.findClosest(any())).thenReturn(List.of(closest, example));
        ComparedRecipe mine = new ComparedRecipe("Plateful soup", Set.of("tomato", "onion"));
        when(plateful.closestApproved(anySet(), anyInt())).thenReturn(List.of(mine));
        RecipeDraft draft = new RecipeDraft("Dish", "A dish.", 2, 5, 10, null,
                List.of(new RecipeDraft.Ingredient("Tomatoes", "2"), new RecipeDraft.Ingredient("Egg", "1")),
                List.of("Cook."));

        List<ComparedRecipe> result = finder.comparisonSet(draft, List.of(example));

        // The example repeated by findClosest appears once.
        assertThat(result).containsExactly(
                new ComparedRecipe("Recipe 1", Set.of("tomato", "basil")),
                new ComparedRecipe("Recipe 2", Set.of("egg", "tomato")),
                mine);
        ArgumentCaptor<ReferenceQuery> captor = ArgumentCaptor.forClass(ReferenceQuery.class);
        verify(reference).findClosest(captor.capture());
        ReferenceQuery q = captor.getValue();
        assertThat(q.wanted()).containsExactlyInAnyOrder("tomato", "egg");
        assertThat(q.mealType()).isNull();
        assertThat(q.allergens()).isEmpty();
        assertThat(q.diets()).isEmpty();
        assertThat(q.dislikes()).isEmpty();
        assertThat(q.limit()).isEqualTo(10);
        verify(plateful).closestApproved(Set.of("tomato", "egg"), 20);
    }
}
