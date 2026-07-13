package mk.ukim.finki.wp.recipeappbackend.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * BUGFIX (review issue #1): the recipes.instructions_steps JSONB column is
 * documented to hold Spoonacular's analyzedInstructions shape —
 * [{number, step, ingredients[], equipment[]}] — i.e. a list of OBJECTS,
 * but the entity/DTOs previously mapped it as List&lt;String&gt;. Writing worked,
 * but reading back any imported recipe would have failed JSON deserialization
 * (objects can't bind into Strings). This record is now the single canonical
 * shape for one step, shared by the entity, the create DTO, and the display
 * DTO so the three can never drift apart again.
 * <p>
 * Used both as a Jackson JSON payload (client &lt;-&gt; API) and as the element
 * type Hibernate serializes into the jsonb column.
 * <p>
 * IMPORTANT — this is a deliberate SIMPLIFICATION of Spoonacular's raw shape
 * (verified against their live docs). The importer must translate:
 * 1. Raw ingredients/equipment are OBJECTS ({id, name, image, temperature?});
 *    we keep names only — map ingredients[].name / equipment[].name here.
 *    (Their per-item images and oven temperatures are intentionally dropped.)
 * 2. The raw response is a list of GROUPS ([{name, steps[]}] — e.g. "For the
 *    sauce"), and step numbers restart per group. The importer must flatten
 *    all groups' steps into this single list and renumber continuously 1..N.
 */
public record InstructionStep(
        Integer number,
        @NotBlank @Size(max = 5_000) String step,
        List<@Size(max = 255) String> ingredients,
        List<@Size(max = 255) String> equipment
) {
}
