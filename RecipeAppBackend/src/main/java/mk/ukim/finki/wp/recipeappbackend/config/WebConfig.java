package mk.ukim.finki.wp.recipeappbackend.config;

import mk.ukim.finki.wp.recipeappbackend.security.CurrentUserIdArgumentResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

import static org.springframework.data.web.config.EnableSpringDataWebSupport.PageSerializationMode.VIA_DTO;

/**
 * VIA_DTO pins the Page JSON contract to the stable shape
 * {"content":[...],"page":{size,number,totalElements,totalPages}} instead of
 * serializing PageImpl internals (which Spring warns may change release to
 * release). The frontend codes against this shape.
 */
@Configuration
@EnableSpringDataWebSupport(pageSerializationMode = VIA_DTO)
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentUserIdArgumentResolver());
    }
}
