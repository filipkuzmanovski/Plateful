package mk.ukim.finki.wp.recipeappbackend.repository;


import mk.ukim.finki.wp.recipeappbackend.model.entities.CommentReaction;
import mk.ukim.finki.wp.recipeappbackend.model.projection.CommentReactionSummary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The like/dislike counts use explicit JPQL rather than a derived method
 * name (e.g. countByCommentIdAndIsLikeTrue) on purpose. Two ambiguities
 * stack up there: Spring Data's method-name parser resolves boolean
 * properties through JavaBean getter-stripping conventions, which get
 * genuinely unclear for a field named isLike (is the derived property
 * "isLike" or "like"?), and "Like" is *also* a reserved keyword in Spring
 * Data's own query DSL (used for SQL LIKE pattern matching) — a collision
 * waiting to happen. Writing the JPQL directly avoids both: r.isLike
 * references the actual entity field name, no parsing ambiguity involved.
 */
public interface CommentReactionRepository extends JpaRepository<CommentReaction, UUID> {

    // Backs the "who reacted" list
    // BUGFIX (review issue #6, N+1): DTO mapping calls getUser() per row —
    // JOIN-fetch the user with the page instead of one SELECT per reaction.
    @EntityGraph(attributePaths = "user")
    Page<CommentReaction> findByCommentId(UUID commentId, Pageable pageable);

    // Still useful for reads (e.g. "did I already react, and how?"), but no
    // longer the write path — see upsertReaction() below.
    Optional<CommentReaction> findByCommentIdAndUserId(UUID commentId, UUID userId);

    // Per-comment versions — fine when you only need one comment's numbers.
    @Query("SELECT COUNT(r) FROM CommentReaction r WHERE r.comment.id = :commentId AND r.isLike = true")
    long countLikesByCommentId(@Param("commentId") UUID commentId);

    @Query("SELECT COUNT(r) FROM CommentReaction r WHERE r.comment.id = :commentId AND r.isLike = false")
    long countDislikesByCommentId(@Param("commentId") UUID commentId);

    // BUGFIX (review issue #6, N+1 aggregates): batched variant — like AND
    // dislike counts for a whole page of comments in ONE GROUP BY query,
    // instead of two COUNT queries per comment (20 comments = 40 queries
    // before, 1 query now). Comments with zero reactions won't appear in
    // the result — treat missing as 0/0 in the service layer.
    @Query("""
            SELECT new mk.ukim.finki.wp.recipeappbackend.model.projection.CommentReactionSummary(
                r.comment.id,
                SUM(CASE WHEN r.isLike = true THEN 1L ELSE 0L END),
                SUM(CASE WHEN r.isLike = false THEN 1L ELSE 0L END))
            FROM CommentReaction r
            WHERE r.comment.id IN :commentIds
            GROUP BY r.comment.id
            """)
    List<CommentReactionSummary> findReactionSummaries(@Param("commentIds") Collection<UUID> commentIds);

    // BUGFIX (review issue #4, upsert race): same reasoning as
    // RecipeRatingRepository.upsertRating() — look-up-then-insert in the
    // service has a race where two concurrent requests both insert and one
    // explodes on UNIQUE(comment_id, user_id). ON CONFLICT is atomic:
    // first reaction inserts, switching like<->dislike updates the same row.
    // NOTE: @Modifying queries need an active transaction — annotate the
    // calling service method with @Transactional.
    @Modifying
    @Query(value = """
            INSERT INTO comment_reactions (comment_id, user_id, is_like)
            VALUES (:commentId, :userId, :isLike)
            ON CONFLICT (comment_id, user_id)
            DO UPDATE SET is_like = EXCLUDED.is_like
            """, nativeQuery = true)
    void upsertReaction(@Param("commentId") UUID commentId,
                        @Param("userId") UUID userId,
                        @Param("isLike") boolean isLike);

    // "Remove my reaction" — scoped to one (comment, user) pair so a user
    // can only ever delete their own row. Needs @Transactional at call site.
    void deleteByCommentIdAndUserId(UUID commentId, UUID userId);
}
