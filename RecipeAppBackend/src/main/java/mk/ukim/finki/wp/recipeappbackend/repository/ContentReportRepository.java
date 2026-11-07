package mk.ukim.finki.wp.recipeappbackend.repository;

import mk.ukim.finki.wp.recipeappbackend.model.entities.ContentReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ContentReportRepository extends JpaRepository<ContentReport, UUID> {

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
