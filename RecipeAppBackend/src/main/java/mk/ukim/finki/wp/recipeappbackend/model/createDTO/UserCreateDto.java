package mk.ukim.finki.wp.recipeappbackend.model.createDTO;


import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import mk.ukim.finki.wp.recipeappbackend.model.entities.User;

import java.util.UUID;

/**
 * What the client sends to create the profile row after a Supabase Auth
 * signup. id is NOT a field here — it comes from the authenticated JWT's
 * "sub" claim in the controller and is passed into toEntity(), so it can't
 * be spoofed to create a profile under someone else's auth id.
 * <p>
 * BUGFIX (review issue #3): email used to be a field in this DTO, i.e.
 * client-supplied. That let a client register a profile email DIFFERENT from
 * the one they actually authenticated with in Supabase, silently desyncing
 * public.users.email from auth.users.email. The email now comes from the
 * verified JWT's "email" claim (same place the id comes from) and is passed
 * into toEntity() by the controller — the request body can no longer lie
 * about it.
 */
public record UserCreateDto(
        @NotBlank @Size(max = 255) String firstName,
        @NotBlank @Size(max = 255) String lastName
) {
    public User toEntity(UUID id, String email) {
        return User.builder()
                .id(id)
                .firstName(firstName)
                .lastName(lastName)
                .email(email)
                .build();
    }
}
