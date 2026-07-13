package mk.ukim.finki.wp.recipeappbackend.config;

import mk.ukim.finki.wp.recipeappbackend.security.CurrentUserIdArgumentResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
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
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentUserIdArgumentResolver());
    }
}
