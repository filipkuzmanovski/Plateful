package mk.ukim.finki.wp.recipeappbackend.model.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import mk.ukim.finki.wp.recipeappbackend.model.ReportReason;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Maps to public.content_reports (V15): one person's report of one recipe or one comment (exactly one of
 * the two ids is set). Ids are plain columns: the rows are counted and listed, never navigated.
 * A report starts {@link #OPEN}; an admin's dismissal makes it {@link #DISMISSED}. It still counts toward
 * "already reported" then, but no longer toward hiding.
 */
@Entity
@Table(name = "content_reports")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ContentReport {

    public static final String OPEN = "open";
    public static final String DISMISSED = "dismissed";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "reporter_id", nullable = false)
    private UUID reporterId;

    @Column(name = "recipe_id")
    private UUID recipeId;

    @Column(name = "comment_id")
    private UUID commentId;

    @Convert(converter = ReportReason.TextConverter.class)
    @Column(name = "reason", nullable = false)
    private ReportReason reason;

    @Column(name = "note", length = 500)
    private String note;

    @Builder.Default
    @Column(name = "state", nullable = false)
    private String state = OPEN;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
