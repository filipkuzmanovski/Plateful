package mk.ukim.finki.wp.recipeappbackend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())// stateless API, no cookies/forms involved
                // BUGFIX (review hardening): the API is JWT-only, so Spring
                // should never create or read an HTTP session. Without this,
                // Spring Security may still create sessions "if required" —
                // pointless server-side state, and it can mask auth mistakes
                // (a request working because of a leftover session rather
                // than a valid token). STATELESS = every request must carry
                // a valid JWT, full stop.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
        return http.build();
    }
}
//for now everything is locked down so we can confirm verification works
