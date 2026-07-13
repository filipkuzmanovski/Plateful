package mk.ukim.finki.wp.recipeappbackend.service;

import mk.ukim.finki.wp.recipeappbackend.exception.ForbiddenOperationException;
import mk.ukim.finki.wp.recipeappbackend.exception.ResourceNotFoundException;
import mk.ukim.finki.wp.recipeappbackend.model.createDTO.RecipeCommentCreateDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.RecipeCommentDisplayDto;
import mk.ukim.finki.wp.recipeappbackend.model.entities.Recipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.RecipeComment;
import mk.ukim.finki.wp.recipeappbackend.model.entities.User;
import mk.ukim.finki.wp.recipeappbackend.model.projection.CommentReactionSummary;
import mk.ukim.finki.wp.recipeappbackend.repository.CommentReactionRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeCommentRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Comments. Moderation policy (product decision): a comment can be deleted
 * by its AUTHOR or by the OWNER of the recipe it sits on ("my recipe, my
 * comment section"). Editing stays author-only — moderators remove content,
 * they don't rewrite other people's words.
 */
@Service
public class CommentService {

    private final RecipeCommentRepository commentRepository;
    private final CommentReactionRepository reactionRepository;
    private final RecipeRepository recipeRepository;
    private final UserRepository userRepository;

    public CommentService(RecipeCommentRepository commentRepository,
                          CommentReactionRepository reactionRepository,
                          RecipeRepository recipeRepository,
                          UserRepository userRepository) {
        this.commentRepository = commentRepository;
        this.reactionRepository = reactionRepository;
        this.recipeRepository = recipeRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public RecipeCommentDisplayDto add(UUID userId, UUID recipeId, RecipeCommentCreateDto dto) {
        Recipe recipe = recipeRepository.findById(recipeId)
                .orElseThrow(() -> new ResourceNotFoundException("Recipe", recipeId));
        User author = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        RecipeComment saved = commentRepository.save(dto.toEntity(recipe, author));
        return RecipeCommentDisplayDto.fromEntity(saved, 0, 0); // brand new: no reactions yet
    }

    /** Author-only. */
    @Transactional
    public RecipeCommentDisplayDto edit(UUID userId, UUID commentId, RecipeCommentCreateDto dto) {
        RecipeComment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new ResourceNotFoundException("Comment", commentId));
        if (!comment.getUser().getId().equals(userId)) {
            throw new ForbiddenOperationException("Only the author can edit a comment");
        }
        comment.setBody(dto.body()); // @UpdateTimestamp bumps updated_at
        RecipeComment savedComment=commentRepository.save(comment);
        return RecipeCommentDisplayDto.fromEntity(savedComment,
                reactionRepository.countLikesByCommentId(commentId),
                reactionRepository.countDislikesByCommentId(commentId));
    }

    /**
     * Author OR recipe owner (moderation). For Spoonacular recipes the owner
     * is null, so those comment sections are author-moderated only.
     * Reactions on the comment vanish via ON DELETE CASCADE.
     */
    @Transactional
    public void delete(UUID userId, UUID commentId) {
        RecipeComment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new ResourceNotFoundException("Comment", commentId));

        boolean isAuthor = comment.getUser().getId().equals(userId);
        User recipeOwner = comment.getRecipe().getUser();
        boolean isRecipeOwner = recipeOwner != null && recipeOwner.getId().equals(userId);

        if (!isAuthor && !isRecipeOwner) {
            throw new ForbiddenOperationException(
                    "Only the comment's author or the recipe's owner can delete it");
        }
        commentRepository.delete(comment);
    }

    /**
     * Paged comments with like/dislike counts. Fixed query count per page:
     * comments (+authors via @EntityGraph) in one, reaction summary GROUP BY
     * in one — never per-comment queries (see N+1 BUGFIX notes in the repos).
     */
    @Transactional(readOnly = true)
    public Page<RecipeCommentDisplayDto> getForRecipe(UUID recipeId, Pageable pageable) {
        if (!recipeRepository.existsById(recipeId)) {
            throw new ResourceNotFoundException("Recipe", recipeId);
        }
        Page<RecipeComment> page =
                commentRepository.findByRecipeIdOrderByCreatedAtDesc(recipeId, pageable);

        List<UUID> ids = page.getContent().stream().map(RecipeComment::getId).toList();
        Map<UUID, CommentReactionSummary> summaries = ids.isEmpty() ? Map.of()
                : reactionRepository.findReactionSummaries(ids).stream()
                        .collect(Collectors.toMap(CommentReactionSummary::commentId, Function.identity()));

        return page.map(comment -> {
            CommentReactionSummary s = summaries.get(comment.getId());
            // absent from GROUP BY result = zero reactions
            return RecipeCommentDisplayDto.fromEntity(comment,
                    s != null ? s.likeCount() : 0L,
                    s != null ? s.dislikeCount() : 0L);
        });
    }
}
