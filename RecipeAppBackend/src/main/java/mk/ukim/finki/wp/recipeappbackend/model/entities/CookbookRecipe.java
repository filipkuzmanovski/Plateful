package mk.ukim.finki.wp.recipeappbackend.model.entities;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Maps to public.cookbook_recipes (V12): one chapter of a cookbook. Not a child collection of {@link Cookbook}:
 * the database cascades a deleted cookbook or recipe, and gaps in {@code position} are harmless.
 */
@Entity
@Table(name = "cookbook_recipes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CookbookRecipe {

    @EmbeddedId
    private CookbookRecipeId id;

    @Column(name = "position", nullable = false)
    private short position;
}
