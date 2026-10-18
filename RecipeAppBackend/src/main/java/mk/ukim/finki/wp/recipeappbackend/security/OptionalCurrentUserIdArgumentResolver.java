package mk.ukim.finki.wp.recipeappbackend.security;

import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.UUID;

/**
 * Resolves @OptionalCurrentUserId UUID parameters: null without a JWT principal (anonymous
 * request), otherwise exactly what @CurrentUserId would resolve, including the 401 for a
 * non-UUID subject.
 */
public class OptionalCurrentUserIdArgumentResolver implements HandlerMethodArgumentResolver {

    private final CurrentUserIdArgumentResolver required = new CurrentUserIdArgumentResolver();

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(OptionalCurrentUserId.class)
                && UUID.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt)) {
            return null;
        }
        return required.resolveArgument(parameter, mavContainer, webRequest, binderFactory);
    }
}
