package mk.ukim.finki.wp.recipeappbackend.repository;

import mk.ukim.finki.wp.recipeappbackend.model.entities.ContentReport;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ContentReportRepository extends JpaRepository<ContentReport, UUID> {

    String COUNT_REPORTED_ITEMS = "(SELECT count(DISTINCT recipe_id) FROM content_reports "
            + "WHERE state = 'open' AND recipe_id IS NOT NULL) + "
            + "(SELECT count(DISTINCT comment_id) FROM content_reports "
            + "WHERE state = 'open' AND comment_id IS NOT NULL)";

    // ---- admin queue ----

    /** One queue row: an item (recipe or comment) with at least one open report, and how many it has. */
    interface QueueRow {
        String getKind();

        UUID getId();

        long getOpenCount();
    }

    /**
     * Items with open reports, recipes and comments mixed: most open reports first, then the one whose oldest
     * open report is oldest, then id. The pageable must be unsorted (the order is fixed here).
     */
    @Query(value = """
            SELECT q.kind AS kind, q.id AS id, q.open_count AS openCount
            FROM (
                SELECT CAST('recipe' AS text) AS kind, recipe_id AS id, count(*) AS open_count, min(created_at) AS oldest
                FROM content_reports WHERE state = 'open' AND recipe_id IS NOT NULL GROUP BY recipe_id
                UNION ALL
                SELECT CAST('comment' AS text) AS kind, comment_id AS id, count(*) AS open_count, min(created_at) AS oldest
                FROM content_reports WHERE state = 'open' AND comment_id IS NOT NULL GROUP BY comment_id
            ) q
            ORDER BY q.open_count DESC, q.oldest ASC, q.id ASC
            """,
            countQuery = "SELECT " + COUNT_REPORTED_ITEMS,
            nativeQuery = true)
    Page<QueueRow> findQueue(Pageable pageable);

    /** Items (recipes plus comments) with at least one open report. */
    @Query(value = "SELECT " + COUNT_REPORTED_ITEMS, nativeQuery = true)
    long countReportedItems();

    /** The open reports of these recipes, oldest first. */
    @Query("""
            SELECT r FROM ContentReport r
            WHERE r.recipeId IN :ids AND r.state = 'open'
            ORDER BY r.createdAt ASC, r.id ASC
            """)
    List<ContentReport> findOpenForRecipes(@Param("ids") Collection<UUID> ids);

    /** The open reports of these comments, oldest first. */
    @Query("""
            SELECT r FROM ContentReport r
            WHERE r.commentId IN :ids AND r.state = 'open'
            ORDER BY r.createdAt ASC, r.id ASC
            """)
    List<ContentReport> findOpenForComments(@Param("ids") Collection<UUID> ids);

    /**
     * Closes the recipe's open reports and returns how many it closed. Does not clear the persistence context:
     * the caller holds the recipe row locked and still changes it afterwards.
     */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE ContentReport r SET r.state = 'dismissed' WHERE r.recipeId = :recipeId AND r.state = 'open'")
    int dismissOpenForRecipe(@Param("recipeId") UUID recipeId);

    /** Closes the comment's open reports and returns how many it closed. Same contract as the recipe one. */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE ContentReport r SET r.state = 'dismissed' WHERE r.commentId = :commentId AND r.state = 'open'")
    int dismissOpenForComment(@Param("commentId") UUID commentId);

    /** Every report by this person since {@code since}, open or dismissed: the rolling daily limit. */
    long countByReporterIdAndCreatedAtAfter(UUID reporterId, OffsetDateTime since);

    /** Any state counts: a dismissed report still means "already reported". */
    boolean existsByReporterIdAndRecipeId(UUID reporterId, UUID recipeId);

    /** Any state counts: a dismissed report still means "already reported". */
    boolean existsByReporterIdAndCommentId(UUID reporterId, UUID commentId);

    /** People with an open report on the recipe; at 3 it is hidden. */
    @Query("""
            SELECT COUNT(DISTINCT r.reporterId) FROM ContentReport r
            WHERE r.recipeId = :recipeId AND r.state = 'open'
            """)
    long countOpenDistinctReportersForRecipe(@Param("recipeId") UUID recipeId);

    /** People with an open report on the comment; at 3 it is hidden. */
    @Query("""
            SELECT COUNT(DISTINCT r.reporterId) FROM ContentReport r
            WHERE r.commentId = :commentId AND r.state = 'open'
            """)
    long countOpenDistinctReportersForComment(@Param("commentId") UUID commentId);

    /** Which of {@code commentIds} this person has reported, in any state. */
    @Query("""
            SELECT r.commentId FROM ContentReport r
            WHERE r.reporterId = :reporterId AND r.commentId IN :commentIds
            """)
    List<UUID> findReportedCommentIds(@Param("reporterId") UUID reporterId,
                                      @Param("commentIds") Collection<UUID> commentIds);
}
