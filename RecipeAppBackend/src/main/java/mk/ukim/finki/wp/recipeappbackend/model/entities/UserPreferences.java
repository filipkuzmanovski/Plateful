package mk.ukim.finki.wp.recipeappbackend.model.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Maps to public.user_preferences (V8). A row means the user has answered the food questions; empty
 * lists then mean "none". The id is the user id, assigned by the service rather than generated.
 */
@Entity
@Table(name = "user_preferences")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserPreferences {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Builder.Default
    @Column(name = "allergens", nullable = false, columnDefinition = "text[]")
    private List<String> allergens = List.of();

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Builder.Default
    @Column(name = "diets", nullable = false, columnDefinition = "text[]")
    private List<String> diets = List.of();

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Builder.Default
    @Column(name = "disliked_ingredients", nullable = false, columnDefinition = "text[]")
    private List<String> dislikedIngredients = List.of();

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Builder.Default
    @Column(name = "liked_ingredients", nullable = false, columnDefinition = "text[]")
    private List<String> likedIngredients = List.of();

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Builder.Default
    @Column(name = "liked_cuisines", nullable = false, columnDefinition = "text[]")
    private List<String> likedCuisines = List.of();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
