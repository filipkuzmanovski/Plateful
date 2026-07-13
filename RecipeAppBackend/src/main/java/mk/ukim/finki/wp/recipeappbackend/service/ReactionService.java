package mk.ukim.finki.wp.recipeappbackend.service;

import mk.ukim.finki.wp.recipeappbackend.exception.ResourceNotFoundException;
import mk.ukim.finki.wp.recipeappbackend.model.createDTO.CommentReactionCreateDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.CommentReactionDisplayDto;
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
 * checks needed.
 */
@Service
public class ReactionService {

    private final CommentReactionRepository reactionRepository;
    private final RecipeCommentRepository commentRepository;

    public ReactionService(CommentReactionRepository reactionRepository,
                           RecipeCommentRepository commentRepository) {
        this.reactionRepository = reactionRepository;
        this.commentRepository = commentRepository;
    }

    /**
     * React or switch reaction — one atomic upsert (see the race-condition
     * BUGFIX note on the repository method). First like inserts; clicking
     * dislike later flips the same row's is_like flag.
     */
    @Transactional
    public void react(UUID userId, UUID commentId, CommentReactionCreateDto dto) {
        if (!commentRepository.existsById(commentId)) {
            throw new ResourceNotFoundException("Comment", commentId);
        }
        reactionRepository.upsertReaction(commentId, userId, dto.isLike());
    }

    /** Un-react (clicking the same button again in the UI). */
    @Transactional
    public void removeMyReaction(UUID userId, UUID commentId) {
        reactionRepository.deleteByCommentIdAndUserId(commentId, userId);
    }

    /** "Who reacted" list — reactor JOIN-fetched via @EntityGraph, no N+1. */
    @Transactional(readOnly = true)
    public Page<CommentReactionDisplayDto> getForComment(UUID commentId, Pageable pageable) {
        if (!commentRepository.existsById(commentId)) {
            throw new ResourceNotFoundException("Comment", commentId);
        }
        return reactionRepository.findByCommentId(commentId, pageable)
                .map(CommentReactionDisplayDto::fromEntity);
    }
}
