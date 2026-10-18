package mk.ukim.finki.wp.recipeappbackend.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Like {@link CurrentUserId}, but for public GET routes: the UUID parameter is the JWT "sub" claim
 * when a valid token came with the request and null when it did not. An invalid token never gets
 * this far (the resource-server filter answers 401 first).
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface OptionalCurrentUserId {
}
