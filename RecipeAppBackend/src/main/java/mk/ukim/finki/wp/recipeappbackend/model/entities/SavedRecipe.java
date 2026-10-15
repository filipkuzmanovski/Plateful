package mk.ukim.finki.wp.recipeappbackend.model.entities;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * Maps to public.saved_recipes: a user's bookmark of a recipe. Rows are written through
 * {@code SavedRecipeRepository.insertIgnore} (an idempotent native insert), so {@code created_at}
 * is always set by the database and never by Hibernate.
 */
@Entity
@Table(name = "saved_recipes")
@Getter
@Setter
@NoArgsConstructor
public class SavedRecipe {

    @EmbeddedId
    private SavedRecipeId id;

    @MapsId("recipeId")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipe_id", nullable = false)
    private Recipe recipe;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
