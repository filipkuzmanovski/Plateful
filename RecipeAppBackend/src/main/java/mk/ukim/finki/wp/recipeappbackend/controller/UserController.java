package mk.ukim.finki.wp.recipeappbackend.controller;

import jakarta.validation.Valid;
import mk.ukim.finki.wp.recipeappbackend.model.createDTO.UserCreateDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.RecipeDisplayDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.UserDisplayDto;
import mk.ukim.finki.wp.recipeappbackend.security.CurrentUserId;
import mk.ukim.finki.wp.recipeappbackend.service.RecipeService;
import mk.ukim.finki.wp.recipeappbackend.service.UserService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;
    private final RecipeService recipeService;

    public UserController(UserService userService, RecipeService recipeService) {
        this.userService = userService;
        this.recipeService = recipeService;
    }

    /**
     * Get-or-create my profile after Supabase signup. Idempotent — safe to
     * retry, returns the existing profile if it's already there. id and
     * email come from the verified JWT, never the body.
     */
    @PostMapping("/me")
    public UserDisplayDto createMyProfile(@CurrentUserId UUID userId,
                                          @AuthenticationPrincipal Jwt jwt,
                                          @Valid @RequestBody UserCreateDto dto) {
        return userService.getOrCreate(userId, jwt.getClaimAsString("email"), dto);
    }

    /** 404 here = "profile not provisioned yet" — the frontend's cue to collect names. */
    @GetMapping("/me")
    public UserDisplayDto myProfile(@CurrentUserId UUID userId) {
        return userService.getById(userId);
    }

    @PutMapping("/me")
    public UserDisplayDto updateMyProfile(@CurrentUserId UUID userId,
                                          @Valid @RequestBody UserCreateDto dto) {
        return userService.updateNames(userId, dto.firstName(), dto.lastName());
    }

    @GetMapping("/{id}")
    public UserDisplayDto publicProfile(@PathVariable UUID id) {
        return userService.getById(id);
    }

    @GetMapping("/{id}/recipes")
    public Page<RecipeDisplayDto> recipesByUser(@PathVariable UUID id, Pageable pageable) {
        return recipeService.getByAuthor(id, pageable);
    }
}
