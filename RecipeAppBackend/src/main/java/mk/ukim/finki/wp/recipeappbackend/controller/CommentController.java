package mk.ukim.finki.wp.recipeappbackend.controller;

import jakarta.validation.Valid;
import mk.ukim.finki.wp.recipeappbackend.model.createDTO.RecipeCommentCreateDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.RecipeCommentDisplayDto;
import mk.ukim.finki.wp.recipeappbackend.security.CurrentUserId;
import mk.ukim.finki.wp.recipeappbackend.service.CommentService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Comments live under their recipe for create/list, but edit/delete address
 * the comment directly — you already know its id from the list response.
 * Moderation policy (author OR recipe owner may delete) is enforced by
 * CommentService, not here.
 */
@RestController
@RequestMapping("/api")
public class CommentController {

    private final CommentService commentService;

    public CommentController(CommentService commentService) {
        this.commentService = commentService;
    }

    @GetMapping("/recipes/{recipeId}/comments")
    public Page<RecipeCommentDisplayDto> list(@PathVariable UUID recipeId, Pageable pageable) {
        return commentService.getForRecipe(recipeId, pageable);
    }

    @PostMapping("/recipes/{recipeId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public RecipeCommentDisplayDto add(@CurrentUserId UUID userId,
                                       @PathVariable UUID recipeId,
                                       @Valid @RequestBody RecipeCommentCreateDto dto) {
        return commentService.add(userId, recipeId, dto);
    }

    @PutMapping("/comments/{id}")
    public RecipeCommentDisplayDto edit(@CurrentUserId UUID userId,
                                        @PathVariable UUID id,
                                        @Valid @RequestBody RecipeCommentCreateDto dto) {
        return commentService.edit(userId, id, dto);
    }

    @DeleteMapping("/comments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@CurrentUserId UUID userId, @PathVariable UUID id) {
        commentService.delete(userId, id);
    }
}
