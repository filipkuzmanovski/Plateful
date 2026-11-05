package mk.ukim.finki.wp.recipeappbackend.model.entities;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * Maps to public.recipe_likes: one user's public like of a recipe. Rows are written through
 * {@code RecipeLikeRepository.insertIgnore} (an idempotent native insert), so {@code created_at} is always
 * set by the database and never by Hibernate.
 */
@Entity
@Table(name = "recipe_likes")
@Getter
@Setter
@NoArgsConstructor
public class RecipeLike {

    @EmbeddedId
    private RecipeLikeId id;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
