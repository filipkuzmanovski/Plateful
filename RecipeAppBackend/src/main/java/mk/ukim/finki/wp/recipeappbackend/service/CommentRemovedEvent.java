package mk.ukim.finki.wp.recipeappbackend.service;

import java.util.UUID;

/**
 * Published inside the transaction in which an admin removes a comment. The recipe title and the
 * comment text are captured here because the comment is gone by the time the listener runs.
 */
public record CommentRemovedEvent(
        UUID commentId,
        String recipeTitle,
        String commentText,
        String reason,
        String authorEmail,
        UUID authorId,
        UUID adminId
) {
}
