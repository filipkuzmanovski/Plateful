package mk.ukim.finki.wp.recipeappbackend.model.createDTO;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * An admin's decision. revision is the one the review page was rendered with (a stale one is refused);
 * reason is required for a rejection (checked in the service, after trimming) and ignored for an approval.
 */
public record ReviewDecisionDto(
        @NotNull Integer revision,
        @Size(max = 500) String reason
) {
}
