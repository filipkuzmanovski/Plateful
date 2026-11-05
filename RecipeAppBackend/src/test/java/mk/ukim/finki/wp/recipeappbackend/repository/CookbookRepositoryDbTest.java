package mk.ukim.finki.wp.recipeappbackend.repository;

import jakarta.persistence.EntityManager;
import mk.ukim.finki.wp.recipeappbackend.model.entities.Cookbook;
import mk.ukim.finki.wp.recipeappbackend.model.entities.CookbookRecipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.CookbookRecipeId;
import mk.ukim.finki.wp.recipeappbackend.model.entities.Recipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.User;
import mk.ukim.finki.wp.recipeappbackend.model.projection.CookbookRecipeCount;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

/**
 * The cookbook tables against the real Postgres. Opt-in: skipped unless SUPABASE_DB_PASSWORD is set, and until
 * V12 is live. Writes only inside the rolled-back test transaction; Flyway is disabled so a test can never
 * migrate the real database.
 */
@Tag("db")
@EnabledIfEnvironmentVariable(named = "SUPABASE_DB_PASSWORD", matches = ".+")
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@TestPropertySource(properties = "spring.flyway.enabled=false")
class CookbookRepositoryDbTest {

    @Autowired
    private CookbookRepository cookbooks;
    @Autowired
    private CookbookRecipeRepository chapters;
    @Autowired
    private RecipeRepository recipes;
    @Autowired
    private UserRepository users;
    @Autowired
    private EntityManager em;

    @BeforeEach
    void needsV12() {
        Object table = em.createNativeQuery("SELECT to_regclass('public.cookbooks')::text").getSingleResult();
        Assumptions.assumeTrue(table != null, "V12 is not live yet");
    }

    // users.id references auth.users(id), so a throwaway user needs a row in both; the test transaction rolls both back.
    private UUID newUser() {
        UUID id = UUID.randomUUID();
        em.createNativeQuery("INSERT INTO auth.users (id) VALUES (:id)").setParameter("id", id).executeUpdate();
        users.saveAndFlush(User.builder()
                .id(id).firstName("Book").lastName("Owner").email("book-" + id + "@example.test").build());
        return id;
    }

    private Recipe seed(String title) {
        return recipes.saveAndFlush(Recipe.builder().title(title).build());
    }

    private Cookbook book(UUID userId, int volume) {
        return cookbooks.saveAndFlush(Cookbook.builder().userId(userId).volume(volume).build());
    }

    private void addChapter(Cookbook book, Recipe recipe, int position) {
        chapters.saveAndFlush(CookbookRecipe.builder()
                .id(new CookbookRecipeId(book.getId(), recipe.getId())).position((short) position).build());
    }

    @Test
    void unique_userAndVolume() {
        UUID u = newUser();
        book(u, 1);

        assertThatThrownBy(() -> book(u, 1)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void kind_defaultsToLiked_andIdIsGenerated() {
        Cookbook saved = book(newUser(), 1);
        em.clear();

        Cookbook read = cookbooks.findById(saved.getId()).orElseThrow();

        assertThat(read.getKind()).isEqualTo("liked");
        assertThat(read.getCreatedAt()).isNotNull();
    }

    @Test
    void findMaxVolume_isZeroWithNoBooks_andTheHighestOtherwise() {
        UUID u = newUser();
        assertThat(cookbooks.findMaxVolume(u)).isZero();

        book(u, 1);
        book(u, 3);

        assertThat(cookbooks.findMaxVolume(u)).isEqualTo(3);
    }

    @Test
    void findByIdAndUserId_isEmptyForAnotherUser() {
        Cookbook mine = book(newUser(), 1);

        assertThat(cookbooks.findByIdAndUserId(mine.getId(), newUser())).isEmpty();
    }

    @Test
    void deleteRecipe_removesItFromCookbook() {
        Cookbook b = book(newUser(), 1);
        Recipe r = seed("book-deleted");
        addChapter(b, r, 1);

        em.createNativeQuery("DELETE FROM recipes WHERE id = :id").setParameter("id", r.getId()).executeUpdate();
        em.clear();

        assertThat(chapters.findRecipeIdsInOrder(b.getId())).isEmpty();
        assertThat(cookbooks.findById(b.getId())).isPresent();
    }

    @Test
    void deleteCookbook_cascadesToItsRecipes() {
        Cookbook b = book(newUser(), 1);
        Recipe r = seed("book-cascade");
        addChapter(b, r, 1);
        em.clear();

        cookbooks.delete(cookbooks.findById(b.getId()).orElseThrow());
        cookbooks.flush();
        em.clear();

        Number rows = (Number) em.createNativeQuery("SELECT count(*) FROM cookbook_recipes WHERE cookbook_id = :id")
                .setParameter("id", b.getId()).getSingleResult();
        assertThat(rows.longValue()).isZero();
    }

    @Test
    void findRecipeIdsInOrder_followsPosition_includingAfterAGap() {
        Cookbook b = book(newUser(), 1);
        Recipe third = seed("book-third");
        Recipe first = seed("book-first");
        addChapter(b, third, 5);
        addChapter(b, first, 2);
        em.clear();

        assertThat(chapters.findRecipeIdsInOrder(b.getId())).containsExactly(first.getId(), third.getId());
    }

    @Test
    void countByCookbookIds_countsEachBook() {
        Cookbook b = book(newUser(), 1);
        addChapter(b, seed("book-a"), 1);
        addChapter(b, seed("book-b"), 2);

        assertThat(chapters.countByCookbookIds(List.of(b.getId())))
                .containsExactly(new CookbookRecipeCount(b.getId(), 2L));
    }
}
