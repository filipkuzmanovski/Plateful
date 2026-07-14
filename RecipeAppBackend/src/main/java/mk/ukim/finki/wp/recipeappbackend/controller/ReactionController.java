package mk.ukim.finki.wp.recipeappbackend.controller;

import jakarta.validation.Valid;
import mk.ukim.finki.wp.recipeappbackend.model.createDTO.CommentReactionCreateDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.CommentReactionDisplayDto;
import mk.ukim.finki.wp.recipeappbackend.security.CurrentUserId;
import mk.ukim.finki.wp.recipeappbackend.service.ReactionService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/comments/{commentId}/reactions")
public class ReactionController {

    private final ReactionService reactionService;

    public ReactionController(ReactionService reactionService) {
        this.reactionService = reactionService;
    }

    @GetMapping
    public Page<CommentReactionDisplayDto> list(@PathVariable UUID commentId, Pageable pageable) {
        return reactionService.getForComment(commentId, pageable);
    }

    /** Like/dislike — the service upserts and flips the same row, so PUT is idempotent. */
    @PutMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void react(@CurrentUserId UUID userId,
                      @PathVariable UUID commentId,
                      @Valid @RequestBody CommentReactionCreateDto dto) {
        reactionService.react(userId, commentId, dto);
    }

    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMyReaction(@CurrentUserId UUID userId, @PathVariable UUID commentId) {
        reactionService.removeMyReaction(userId, commentId);
    }
}
