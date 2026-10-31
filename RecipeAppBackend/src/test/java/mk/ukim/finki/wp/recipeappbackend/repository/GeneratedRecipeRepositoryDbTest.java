package mk.ukim.finki.wp.recipeappbackend.repository;

import jakarta.persistence.EntityManager;
import mk.ukim.finki.wp.recipeappbackend.model.entities.GeneratedRecipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.User;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

/**
 * Runs the generated-recipe queries against the real Postgres. Opt-in: skipped unless
 * SUPABASE_DB_PASSWORD is set, and skipped while V9 is not applied (the backend applies it on its
 * next start; Flyway is disabled here so a test can never migrate the real database). Writes stay
 * in the rolled-back test transaction.
 */
@Tag("db")
@EnabledIfEnvironmentVariable(named = "SUPABASE_DB_PASSWORD", matches = ".+")
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@TestPropertySource(properties = "spring.flyway.enabled=false")
class GeneratedRecipeRepositoryDbTest {

    @Autowired
    private GeneratedRecipeRepository repository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private EntityManager em;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void requireV9() {
        Boolean exists = jdbc.queryForObject("SELECT to_regclass('public.generated_recipes') IS NOT NULL",
                Boolean.class);
        Assumptions.assumeTrue(Boolean.TRUE.equals(exists), "V9 is not applied yet (restart the backend)");
    }

    private User newUser(String name) {
        UUID id = UUID.randomUUID();
        em.createNativeQuery("INSERT INTO auth.users (id) VALUES (:id)").setParameter("id", id).executeUpdate();
        return userRepository.saveAndFlush(User.builder()
                .id(id).firstName(name).lastName("Gen").email(name + "-" + id + "@example.test").build());
    }

    private GeneratedRecipe save(User user, String kind, String title, OffsetDateTime savedAt) {
        return repository.saveAndFlush(GeneratedRecipe.builder()
                .user(user)
                .kind(kind)
                .mealType("dinner")
                .title(title)
                .recipe(Map.of("title", title, "servings", 2))
                .request(Map.of("ingredients", List.of("egg", "rice")))
                .provider("test")
                .model("test-model")
                .noveltyScore(new BigDecimal("0.250"))
                .referenceIds(new Long[]{1L, 2L})
                .savedAt(savedAt)
                .build());
    }

    @Test
    void roundTripsJsonAndArray() {
        User u = newUser("rt");
        GeneratedRecipe saved = save(u, "cook", "Round trip", null);
        em.clear();

        GeneratedRecipe found = repository.findById(saved.getId()).orElseThrow();

        assertThat(found.getTitle()).isEqualTo("Round trip");
        assertThat(found.getRecipe()).containsEntry("title", "Round trip");
        assertThat(found.getRequest()).containsKey("ingredients");
        assertThat(found.getReferenceIds()).containsExactly(1L, 2L);
        assertThat(found.getNoveltyScore()).isEqualByComparingTo("0.250");
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getSavedAt()).isNull();
    }

    @Test
    void findByIdAndUserId_otherUser_isEmpty() {
        User owner = newUser("owner");
        User other = newUser("other");
        GeneratedRecipe recipe = save(owner, "cook", "Mine", null);
        em.clear();

        assertThat(repository.findByIdAndUserId(recipe.getId(), owner.getId())).isPresent();
        Optional<GeneratedRecipe> asOther = repository.findByIdAndUserId(recipe.getId(), other.getId());
        assertThat(asOther).isEmpty();
    }

    @Test
    void savedPage_onlySaved_newestFirst() {
        User u = newUser("saver");
        OffsetDateTime now = OffsetDateTime.now();
        GeneratedRecipe oldest = save(u, "cook", "Oldest", now.minusDays(3));
        GeneratedRecipe newest = save(u, "cook", "Newest", now.minusDays(1));
        GeneratedRecipe middle = save(u, "daily", "Middle", now.minusDays(2));
        save(u, "cook", "Never saved", null);
        User other = newUser("someone");
        save(other, "cook", "Not mine", now);
        em.clear();

        Page<GeneratedRecipe> page = repository.findByUserIdAndSavedAtIsNotNull(u.getId(),
                PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "savedAt")));

        assertThat(page.getContent()).extracting(GeneratedRecipe::getId)
                .containsExactly(newest.getId(), middle.getId());
        assertThat(page.getTotalElements()).isEqualTo(3);
        Page<GeneratedRecipe> second = repository.findByUserIdAndSavedAtIsNotNull(u.getId(),
                PageRequest.of(1, 2, Sort.by(Sort.Direction.DESC, "savedAt")));
        assertThat(second.getContent()).extracting(GeneratedRecipe::getId).containsExactly(oldest.getId());
    }

    @Test
    void recentDailyTitles_onlyDailyOfThatUserSince() {
        User u = newUser("titles");
        User other = newUser("titles-other");
        save(u, "daily", "Daily one", null);
        save(u, "cook", "A cook recipe", null);
        save(other, "daily", "Another user's daily", null);
        em.flush();
        em.clear();

        List<String> titles = repository.recentDailyTitles(u.getId(), OffsetDateTime.now().minusDays(1));
        assertThat(titles).containsExactly("Daily one");
        assertThat(repository.recentDailyTitles(u.getId(), OffsetDateTime.now().plusDays(1))).isEmpty();
    }
}
