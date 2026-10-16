package mk.ukim.finki.wp.recipeappbackend.repository;

import jakarta.persistence.EntityManager;
import mk.ukim.finki.wp.recipeappbackend.model.entities.Recipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.User;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

/**
 * Runs the saved-recipes queries against the real Postgres. Opt-in: skipped unless
 * SUPABASE_DB_PASSWORD is set. Writes only inside the rolled-back test transaction; Flyway is
 * disabled so a test can never migrate the real database (V4 and V5 must already be applied by the app).
 */
@Tag("db")
@EnabledIfEnvironmentVariable(named = "SUPABASE_DB_PASSWORD", matches = ".+")
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@TestPropertySource(properties = "spring.flyway.enabled=false")
class SavedRecipeRepositoryDbTest {

    @Autowired
    private SavedRecipeRepository savedRepository;
    @Autowired
    private RecipeRepository recipeRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private EntityManager em;

    // About 19 years in minutes: pushes the seeded rows far into the future so they sort ahead of
    // anything the borrowed user really has saved.
    private static final int FAR = 10_000_000;

    private UUID anyUserId() {
        List<User> users = userRepository.findAll(PageRequest.of(0, 1)).getContent();
        Assumptions.assumeTrue(!users.isEmpty(), "Needs one existing user");
        return users.get(0).getId();
    }

    // users.id references auth.users(id), so a throwaway user needs a row in both; the enclosing
    // test transaction rolls both back.
    private UUID newUser(String name) {
        UUID id = UUID.randomUUID();
        em.createNativeQuery("INSERT INTO auth.users (id) VALUES (:id)").setParameter("id", id).executeUpdate();
        userRepository.saveAndFlush(User.builder()
                .id(id).firstName(name).lastName("Saver").email(name + "-" + id + "@example.test").build());
        return id;
    }

    private Recipe seed(String title) {
        return recipeRepository.saveAndFlush(Recipe.builder().title(title).build());
    }

    // now() is constant inside one transaction, so force distinct, ordered save times.
    private void setSavedAt(UUID userId, UUID recipeId, int minutesAgo) {
        em.createNativeQuery("UPDATE saved_recipes SET created_at = now() - make_interval(mins => :m) "
                        + "WHERE user_id = :u AND recipe_id = :r")
                .setParameter("m", minutesAgo)
                .setParameter("u", userId)
                .setParameter("r", recipeId)
                .executeUpdate();
    }

    @Test
    void insertIgnoreIsIdempotent() {
        UUID u = anyUserId();
        Recipe r1 = seed("saved-r1");

        savedRepository.insertIgnore(u, r1.getId());
        savedRepository.insertIgnore(u, r1.getId());
        em.flush();
        em.clear();

        assertThat(savedRepository.findAllRecipeIdsByUserId(u)).containsOnlyOnce(r1.getId());
        Number rows = (Number) em.createNativeQuery(
                        "SELECT count(*) FROM saved_recipes WHERE user_id = :u AND recipe_id = :r")
                .setParameter("u", u).setParameter("r", r1.getId()).getSingleResult();
        assertThat(rows.longValue()).isEqualTo(1);
    }

    @Test
    void newestFirstThenRecipeId() {
        UUID u = anyUserId();
        Recipe r1 = seed("saved-r1");
        Recipe r2 = seed("saved-r2");
        Recipe r3 = seed("saved-r3");
        savedRepository.insertIgnore(u, r1.getId());
        savedRepository.insertIgnore(u, r2.getId());
        savedRepository.insertIgnore(u, r3.getId());
        // r1 oldest, r2 middle, r3 newest. Negative offsets are in the future, so all three sort
        // ahead of any rows the borrowed user already has saved.
        setSavedAt(u, r1.getId(), -FAR - 1);
        setSavedAt(u, r2.getId(), -FAR - 2);
        setSavedAt(u, r3.getId(), -FAR - 3);
        em.flush();
        em.clear();

        Page<UUID> page = savedRepository.findRecipeIdsByUserId(u, PageRequest.of(0, 2));

        assertThat(page.getContent()).containsExactly(r3.getId(), r2.getId());
        assertThat(page.getTotalElements()).isGreaterThanOrEqualTo(3);
        assertThat(savedRepository.findAllRecipeIdsByUserId(u))
                .containsSubsequence(r3.getId(), r2.getId(), r1.getId());
    }

    @Test
    void equalTimestampsBreakTiesByRecipeIdAscending() {
        UUID u = anyUserId();
        Recipe a = seed("saved-tie-a");
        Recipe b = seed("saved-tie-b");
        savedRepository.insertIgnore(u, a.getId());
        savedRepository.insertIgnore(u, b.getId());
        // Same instant for both, in the future so they sort ahead of any existing rows.
        setSavedAt(u, a.getId(), -FAR - 10);
        setSavedAt(u, b.getId(), -FAR - 10);
        em.flush();
        em.clear();

        // Postgres orders uuids as unsigned bytes, which matches the lowercase hex string order;
        // UUID.compareTo compares signed longs and would disagree.
        List<UUID> expected = List.of(a.getId(), b.getId()).stream()
                .sorted(java.util.Comparator.comparing(UUID::toString)).toList();
        Page<UUID> page = savedRepository.findRecipeIdsByUserId(u, PageRequest.of(0, 2));

        assertThat(page.getContent()).containsExactlyElementsOf(expected);
    }

    @Test
    void deletingRecipeCascades() {
        UUID u = anyUserId();
        Recipe r1 = seed("saved-r1");
        Recipe r2 = seed("saved-r2");
        savedRepository.insertIgnore(u, r1.getId());
        savedRepository.insertIgnore(u, r2.getId());
        em.flush();
        assertThat(savedRepository.findAllRecipeIdsByUserId(u)).contains(r1.getId(), r2.getId());

        recipeRepository.deleteById(r2.getId());
        recipeRepository.flush();
        em.clear();

        List<UUID> ids = savedRepository.findAllRecipeIdsByUserId(u);
        assertThat(ids).contains(r1.getId());
        assertThat(ids).doesNotContain(r2.getId());
    }

    @Test
    void deleteRemovesOnlyThatPair() {
        UUID u = anyUserId();
        Recipe r1 = seed("saved-r1");
        Recipe r2 = seed("saved-r2");
        savedRepository.insertIgnore(u, r1.getId());
        savedRepository.insertIgnore(u, r2.getId());
        em.flush();

        savedRepository.deleteByIdUserIdAndIdRecipeId(u, r1.getId());
        em.flush();
        em.clear();

        List<UUID> ids = savedRepository.findAllRecipeIdsByUserId(u);
        assertThat(ids).doesNotContain(r1.getId());
        assertThat(ids).contains(r2.getId());
    }

    @Test
    void savesAreIsolatedPerUser() {
        UUID a = newUser("a");
        UUID b = newUser("b");
        Recipe shared = seed("saved-shared");
        Recipe onlyA = seed("saved-only-a");
        Recipe onlyB = seed("saved-only-b");
        savedRepository.insertIgnore(a, shared.getId());
        savedRepository.insertIgnore(a, onlyA.getId());
        savedRepository.insertIgnore(b, shared.getId());
        savedRepository.insertIgnore(b, onlyB.getId());
        // now() is constant inside one transaction, so force a distinct, ordered save time.
        setSavedAt(a, shared.getId(), 1);
        setSavedAt(a, onlyA.getId(), 2);
        setSavedAt(b, shared.getId(), 1);
        setSavedAt(b, onlyB.getId(), 2);
        em.flush();
        em.clear();

        // The users are brand new, so each list holds exactly their own saves, newest first.
        Page<UUID> pageA = savedRepository.findRecipeIdsByUserId(a, PageRequest.of(0, 10));
        assertThat(pageA.getContent()).containsExactly(shared.getId(), onlyA.getId());
        assertThat(pageA.getTotalElements()).isEqualTo(2);
        Page<UUID> pageB = savedRepository.findRecipeIdsByUserId(b, PageRequest.of(0, 10));
        assertThat(pageB.getContent()).containsExactly(shared.getId(), onlyB.getId());
        assertThat(pageB.getTotalElements()).isEqualTo(2);
        assertThat(savedRepository.findAllRecipeIdsByUserId(a)).containsExactly(shared.getId(), onlyA.getId());
        assertThat(savedRepository.findAllRecipeIdsByUserId(b)).containsExactly(shared.getId(), onlyB.getId());

        // A unsaving the shared recipe leaves B's save of it alone.
        savedRepository.deleteByIdUserIdAndIdRecipeId(a, shared.getId());
        em.flush();
        em.clear();

        assertThat(savedRepository.findAllRecipeIdsByUserId(a)).containsExactly(onlyA.getId());
        assertThat(savedRepository.findAllRecipeIdsByUserId(b)).containsExactly(shared.getId(), onlyB.getId());
    }

    // V5: without row level security the public anon key could read or delete everyone's saves.
    @Test
    void savedRecipesHasRowLevelSecurityEnabled() {
        Object enabled = em.createNativeQuery(
                        "SELECT relrowsecurity FROM pg_class WHERE relname = 'saved_recipes'")
                .getSingleResult();

        assertThat(enabled).isEqualTo(true);
    }
}
