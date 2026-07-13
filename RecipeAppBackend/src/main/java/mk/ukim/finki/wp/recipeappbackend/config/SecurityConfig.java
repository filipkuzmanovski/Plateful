package mk.ukim.finki.wp.recipeappbackend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Route-level security only: which routes are public vs. need a valid
 * Supabase JWT. OWNERSHIP checks (may this user edit THIS recipe?) live in
 * the services — the backend's DB connection bypasses RLS, so those service
 * checks are the only per-row authorization layer.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final List<String> allowedOrigins;

    public SecurityConfig(@Value("${app.cors.allowed-origins:http://localhost:5173}") List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable()) // stateless API, no cookies/forms involved
                .cors(Customizer.withDefaults())
                // JWT-only API: never create or read an HTTP session.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // "me" routes are authenticated and MUST be declared
                        // before the broader public GET patterns that would
                        // otherwise match them.
                        .requestMatchers(HttpMethod.GET,
                                "/api/users/me",
                                "/api/recipes/*/ratings/me").authenticated()
                        // Public reads: browsing content needs no account.
                        .requestMatchers(HttpMethod.GET,
                                "/api/recipes/**",
                                "/api/users/*",
                                "/api/users/*/recipes",
                                "/api/comments/*/reactions").permitAll()
                        // Deny-by-default: every mutation and unknown route.
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
