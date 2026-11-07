package mk.ukim.finki.wp.recipeappbackend.service;

import mk.ukim.finki.wp.recipeappbackend.exception.ForbiddenOperationException;
import mk.ukim.finki.wp.recipeappbackend.exception.ResourceNotFoundException;
import mk.ukim.finki.wp.recipeappbackend.model.createDTO.CommentReactionCreateDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.CommentReactionDisplayDto;
import mk.ukim.finki.wp.recipeappbackend.model.entities.RecipeComment;
import mk.ukim.finki.wp.recipeappbackend.repository.CommentReactionRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeCommentRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Like/dislike on comments. Like ratings, every operation is scoped to the
 * caller's own (commentId, userId) row by construction — no ownership
 * checks needed. The recipe is reached through the comment: reads need it visible, writes (un-reacting included)
 * need it approved (spec 4.2). A comment hidden by reports takes no new reactions, though one can still be taken
 * back, and its reaction list is a 404 for anyone but its author and admins (spec 3.2).
 */
@Service
public class ReactionService {

    private final CommentReactionRepository reactionRepository;
    private final RecipeCommentRepository commentRepository;
    private final RecipeVisibility visibility;

    public ReactionService(CommentReactionRepository reactionRepository,
                           RecipeCommentRepository commentRepository,
                           RecipeVisibility visibility) {
        this.reactionRepository = reactionRepository;
        this.commentRepository = commentRepository;
        this.visibility = visibility;
    }

    /**
     * React or switch reaction — one atomic upsert (see the race-condition
     * BUGFIX note on the repository method). First like inserts; clicking
     * dislike later flips the same row's is_like flag.
     */
    @Transactional
    public void react(UUID userId, UUID commentId, CommentReactionCreateDto dto) {
        RecipeComment comment = findComment(commentId);
        visibility.requireApprovedForInteraction(comment.getRecipe(), userId);
        if (comment.getHiddenAt() != null) {
            throw new ForbiddenOperationException("A hidden comment can't take reactions");
        }
        reactionRepository.upsertReaction(commentId, userId, dto.isLike());
    }

    /** Un-react (clicking the same button again in the UI). */
    @Transactional
    public void removeMyReaction(UUID userId, UUID commentId) {
        visibility.requireApprovedForInteraction(findComment(commentId).getRecipe(), userId);
        reactionRepository.deleteByCommentIdAndUserId(commentId, userId);
    }

    /** "Who reacted" list — reactor JOIN-fetched via @EntityGraph, no N+1. */
    @Transactional(readOnly = true)
    public Page<CommentReactionDisplayDto> getForComment(UUID viewerId, UUID commentId, Pageable pageable) {
        RecipeComment comment = findComment(commentId);
        visibility.requireVisible(comment.getRecipe(), viewerId);
        if (comment.getHiddenAt() != null && !isCommentAuthor(comment, viewerId) && !visibility.isAdmin(viewerId)) {
            throw new ResourceNotFoundException("Comment", commentId);
        }
        return reactionRepository.findByCommentId(commentId, pageable)
                .map(CommentReactionDisplayDto::fromEntity);
    }

    private static boolean isCommentAuthor(RecipeComment comment, UUID viewerId) {
        return viewerId != null && comment.getUser() != null && viewerId.equals(comment.getUser().getId());
    }

    private RecipeComment findComment(UUID commentId) {
        return commentRepository.findById(commentId)
                .orElseThrow(() -> new ResourceNotFoundException("Comment", commentId));
    }
}
