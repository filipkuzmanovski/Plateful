package mk.ukim.finki.wp.recipeappbackend.model.entities;

import jakarta.persistence.Column;
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
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Maps to public.cookbooks (V12): one user's private cookbook. Its recipes are {@link CookbookRecipe} rows
 * that point at the recipes rather than copying them, so authors and admins stay in control of the content.
 */
@Entity
@Table(name = "cookbooks")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Cookbook {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Only {@code liked} for now; later cookbook types add a value. */
    @Builder.Default
    @Column(name = "kind", nullable = false)
    private String kind = "liked";

    @Column(name = "volume", nullable = false)
    private int volume;

    /** Null when the user left it empty; the frontend then shows its default. */
    @Column(name = "subtitle", length = 80)
    private String subtitle;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
