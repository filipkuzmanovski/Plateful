package mk.ukim.finki.wp.recipeappbackend.config;

import mk.ukim.finki.wp.recipeappbackend.security.AdminGuard;
import mk.ukim.finki.wp.recipeappbackend.security.AdminGuardInterceptor;
import mk.ukim.finki.wp.recipeappbackend.security.CurrentUserIdArgumentResolver;
import mk.ukim.finki.wp.recipeappbackend.security.OptionalCurrentUserIdArgumentResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * The stable Page JSON shape {"content":[...],"page":{size,number,
 * totalElements,totalPages}} (VIA_DTO) is configured via the
 * spring.data.web.pageable.serialization-mode property in
 * application.properties rather than the @EnableSpringDataWebSupport
 * annotation — the annotation makes Spring Boot's Spring-Data-web
 * auto-configuration back off, which silently disabled the
 * max-page-size property.
 */
//extracts users ID from the JWT token
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final ObjectProvider<AdminGuard> adminGuard;

    public WebConfig(ObjectProvider<AdminGuard> adminGuard) {
        this.adminGuard = adminGuard;
    }

    /** Every /api/admin route is admin-only, enforced here rather than in each handler. */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AdminGuardInterceptor(adminGuard)).addPathPatterns("/api/admin/**");
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentUserIdArgumentResolver());
        resolvers.add(new OptionalCurrentUserIdArgumentResolver());
    }
}
