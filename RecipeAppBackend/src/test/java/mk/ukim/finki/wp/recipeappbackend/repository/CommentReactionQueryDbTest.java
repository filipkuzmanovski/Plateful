package mk.ukim.finki.wp.recipeappbackend.repository;

import mk.ukim.finki.wp.recipeappbackend.model.entities.CommentReaction;
import mk.ukim.finki.wp.recipeappbackend.model.projection.MyCommentReaction;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

/**
 * Runs the "my reactions on this recipe" JPQL against the real Postgres. Opt-in: skipped unless
 * SUPABASE_DB_PASSWORD is set. Read-only; Flyway is disabled so a test can never migrate the real
 * database.
 */
@Tag("db")
@EnabledIfEnvironmentVariable(named = "SUPABASE_DB_PASSWORD", matches = ".+")
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
// Same config as RecipeCuisineQueryDbTest on purpose: Spring then reuses one cached test context
// (one connection pool). A second context can exceed the Supabase session pooler's 15-client cap.
@TestPropertySource(properties = "spring.flyway.enabled=false")
class CommentReactionQueryDbTest {

    @Autowired
    private CommentReactionRepository reactionRepository;

    @Test
    void everyStoredReactionIsFoundForItsUserAndRecipe() {
        List<CommentReaction> all = reactionRepository.findAll();

        for (CommentReaction r : all) {
            List<MyCommentReaction> mine = reactionRepository.findMyReactionsForRecipe(
                    r.getComment().getRecipe().getId(), r.getUser().getId());

            assertThat(mine).contains(new MyCommentReaction(r.getComment().getId(), r.isLike()));
        }
    }

    @Test
    void unknownUserHasNoReactions() {
        assertThat(reactionRepository.findMyReactionsForRecipe(UUID.randomUUID(), UUID.randomUUID()))
                .isEmpty();
    }
}
