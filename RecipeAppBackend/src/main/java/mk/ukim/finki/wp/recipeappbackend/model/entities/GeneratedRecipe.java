package mk.ukim.finki.wp.recipeappbackend.model.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Maps to public.generated_recipes (V9): a recipe written by an AI provider for one user. The
 * {@code recipe} and {@code request} documents are plain JSON maps; the service converts them to and
 * from its own records.
 */
@Entity
@Table(name = "generated_recipes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GeneratedRecipe {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** {@code cook}, {@code daily} or {@code post}. */
    @Column(name = "kind", nullable = false)
    private String kind;

    /** {@code breakfast}, {@code lunch}, {@code dinner} or null. */
    @Column(name = "meal_type")
    private String mealType;

    @Column(name = "title", nullable = false)
    private String title;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "recipe", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> recipe;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> request;

    @Column(name = "provider", nullable = false)
    private String provider;

    @Column(name = "model", nullable = false)
    private String model;

    @Column(name = "novelty_score", nullable = false, precision = 4, scale = 3)
    private BigDecimal noveltyScore;

    /** The RecipeNLG ids that were sent to the model as examples. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Builder.Default
    @Column(name = "reference_ids", nullable = false, columnDefinition = "bigint[]")
    private Long[] referenceIds = new Long[0];

    /** Null means not saved by the user. */
    @Column(name = "saved_at")
    private OffsetDateTime savedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
