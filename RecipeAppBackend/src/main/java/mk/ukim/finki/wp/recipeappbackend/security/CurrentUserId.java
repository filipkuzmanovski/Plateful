package mk.ukim.finki.wp.recipeappbackend.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injects the authenticated user's id (JWT "sub" claim) as a UUID handler
 * parameter. The ONLY sanctioned way for controllers to learn who is calling
 * — identity never comes from a request body or path variable.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUserId {
}
