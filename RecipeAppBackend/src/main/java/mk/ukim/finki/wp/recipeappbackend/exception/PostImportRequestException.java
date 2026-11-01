package mk.ukim.finki.wp.recipeappbackend.exception;

/**
 * The post import request itself is wrong: no caption and no screenshots, a caption over 2,200 characters, or
 * more than 2 screenshots. Maps to 400 {@code invalid_post}. The detail is the user-facing sentence and never
 * holds the caption.
 */
public class PostImportRequestException extends RuntimeException {

    public PostImportRequestException(String detail) {
        super(detail);
    }
}
