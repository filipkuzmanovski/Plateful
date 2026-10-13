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
@Target(ElementType.PARAMETER)//can only be placed as a parameter like on controller arguments
@Retention(RetentionPolicy.RUNTIME)//doesnt allow the id to come from anywhere else except the JWT token
public @interface CurrentUserId {
}
