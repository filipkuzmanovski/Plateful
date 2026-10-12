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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * Maps to public.recipe_photos: one row per photo of a recipe. {@code path} is the Supabase Storage
 * object path ({userId}/{uuid}.{ext}), not a URL; the public URL is built from configuration.
 * Position 0 is the cover photo.
 */
@Entity
@Table(name = "recipe_photos")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecipePhoto {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipe_id", nullable = false)
    private Recipe recipe;

    @Column(name = "path", nullable = false, columnDefinition = "text")
    private String path;

    // The column is SMALLINT (0-5). Without the JDBC type hint, ddl-auto=validate expects INTEGER for
    // an Integer field and refuses to start (the same mismatch V2 had to repair for older columns).
    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "position", nullable = false)
    private Integer position;
}
