package mk.ukim.finki.wp.recipeappbackend.repository;

import jakarta.persistence.EntityManager;
import mk.ukim.finki.wp.recipeappbackend.model.entities.Recipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.User;
import mk.ukim.finki.wp.recipeappbackend.model.projection.RecipeLikeCount;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

/**
 * The like queries against the real Postgres. Opt-in: skipped unless SUPABASE_DB_PASSWORD is set, and until
 * V12 is live. Writes only inside the rolled-back test transaction; Flyway is disabled so a test can never
 * migrate the real database.
 */
@Tag("db")
@EnabledIfEnvironmentVariable(named = "SUPABASE_DB_PASSWORD", matches = ".+")
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@TestPropertySource(properties = "spring.flyway.enabled=false")
class RecipeLikeRepositoryDbTest {

    @Autowired
    private RecipeLikeRepository likes;
    @Autowired
    private RecipeRepository recipes;
    @Autowired
    private UserRepository users;
    @Autowired
    private EntityManager em;

    @BeforeEach
    void needsV12() {
        Object table = em.createNativeQuery("SELECT to_regclass('public.recipe_likes')::text").getSingleResult();
        Assumptions.assumeTrue(table != null, "V12 is not live yet");
    }

    // users.id references auth.users(id), so a throwaway user needs a row in both; the test transaction rolls both back.
    private UUID newUser(String name) {
        UUID id = UUID.randomUUID();
        em.createNativeQuery("INSERT INTO auth.users (id) VALUES (:id)").setParameter("id", id).executeUpdate();
        users.saveAndFlush(User.builder()
                .id(id).firstName(name).lastName("Liker").email(name + "-" + id + "@example.test").build());
        return id;
    }

    private Recipe seed(String title) {
        return recipes.saveAndFlush(Recipe.builder().title(title).build());
    }

    private void setLikedAt(UUID userId, UUID recipeId, int minutesAgo) {
        em.createNativeQuery("UPDATE recipe_likes SET created_at = now() - make_interval(mins => :m) "
                        + "WHERE user_id = :u AND recipe_id = :r")
                .setParameter("m", minutesAgo).setParameter("u", userId).setParameter("r", recipeId)
                .executeUpdate();
    }

    @Test
    void insertIgnore_twice_keepsOneRow() {
        UUID u = newUser("twice");
        Recipe r = seed("like-twice");

        assertThat(likes.insertIgnore(u, r.getId())).isEqualTo(1);
        assertThat(likes.insertIgnore(u, r.getId())).isEqualTo(0);

        assertThat(likes.findAllRecipeIdsByUserId(u)).containsExactly(r.getId());
        assertThat(likes.countByIdRecipeId(r.getId())).isEqualTo(1);
    }

    @Test
    void insertIgnore_missingRecipe_insertsNothing() {
        UUID u = newUser("missing");

        assertThat(likes.insertIgnore(u, UUID.randomUUID())).isEqualTo(0);

        assertThat(likes.findAllRecipeIdsByUserId(u)).isEmpty();
    }

    @Test
    void deleteRecipe_cascadesToLikes() {
        UUID u = newUser("cascade");
        Recipe r = seed("like-cascade");
        likes.insertIgnore(u, r.getId());

        em.createNativeQuery("DELETE FROM recipes WHERE id = :id").setParameter("id", r.getId()).executeUpdate();
        em.clear();

        assertThat(likes.findAllRecipeIdsByUserId(u)).isEmpty();
    }

    @Test
    void findLikeCounts_omitsRecipesWithNoLikes() {
        UUID a = newUser("a");
        UUID b = newUser("b");
        Recipe liked = seed("like-two");
        Recipe unliked = seed("like-none");
        likes.insertIgnore(a, liked.getId());
        likes.insertIgnore(b, liked.getId());

        List<RecipeLikeCount> counts = likes.findLikeCounts(List.of(liked.getId(), unliked.getId()));

        assertThat(counts).containsExactly(new RecipeLikeCount(liked.getId(), 2L));
    }

    @Test
    void findRecipeIdsByUserId_isNewestFirst() {
        UUID u = newUser("order");
        Recipe oldest = seed("like-old");
        Recipe newest = seed("like-new");
        likes.insertIgnore(u, oldest.getId());
        likes.insertIgnore(u, newest.getId());
        setLikedAt(u, oldest.getId(), 10);
        setLikedAt(u, newest.getId(), 1);
        em.clear();

        assertThat(likes.findRecipeIdsByUserId(u, PageRequest.of(0, 10)).getContent())
                .containsExactly(newest.getId(), oldest.getId());
        assertThat(likes.findAllRecipeIdsByUserId(u)).containsExactly(newest.getId(), oldest.getId());
    }

    @Test
    void findLikedAmong_returnsOnlyTheUsersLikes() {
        UUID mine = newUser("mine");
        UUID other = newUser("other");
        Recipe a = seed("like-a");
        Recipe b = seed("like-b");
        likes.insertIgnore(mine, a.getId());
        likes.insertIgnore(other, b.getId());

        assertThat(likes.findLikedAmong(mine, List.of(a.getId(), b.getId()))).containsExactly(a.getId());
    }
}
