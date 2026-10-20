package mk.ukim.finki.wp.recipeappbackend.controller;

import mk.ukim.finki.wp.recipeappbackend.model.createDTO.RemovalDto;
import mk.ukim.finki.wp.recipeappbackend.security.CurrentUserId;
import mk.ukim.finki.wp.recipeappbackend.service.AdminModerationService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Admin removal of recipes and comments. AdminGuardInterceptor (see WebConfig) guards every route under /api/admin. */
@RestController
@RequestMapping("/api/admin")
public class AdminModerationController {

    private final AdminModerationService moderationService;

    public AdminModerationController(AdminModerationService moderationService) {
        this.moderationService = moderationService;
    }

    @PostMapping("/recipes/{id}/remove")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeRecipe(@CurrentUserId UUID adminId, @PathVariable UUID id, @RequestBody RemovalDto body) {
        moderationService.removeRecipe(adminId, id, body.reason());
    }

    @PostMapping("/comments/{id}/remove")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeComment(@CurrentUserId UUID adminId, @PathVariable UUID id, @RequestBody RemovalDto body) {
        moderationService.removeComment(adminId, id, body.reason());
    }
}
