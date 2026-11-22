package com.planova.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.planova.common.SecurityErrorHandlers;
import com.planova.identity.DatabaseBackedJwtConverter;
import com.planova.identity.TokenService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Authentication + authorization wiring.
 *
 * <p>Model: stateless bearer tokens. The browser sends {@code Authorization: Bearer <jwt>}; the
 * backend verifies the signature and then loads the user from the database (see
 * {@link DatabaseBackedJwtConverter}). Authorization is enforced HERE and with
 * {@code @PreAuthorize} on the backend - hiding a button in React is never the control.
 *
 * <ul>
 *   <li>{@code @EnableWebSecurity}: turns on Spring Security's filter chain.</li>
 *   <li>{@code @EnableMethodSecurity}: makes {@code @PreAuthorize("hasRole('ADMIN')")} work.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http,
                                    DatabaseBackedJwtConverter jwtConverter,
                                    SecurityErrorHandlers errorHandlers) throws Exception {
        http
            // CSRF protects cookie-based sessions. We use no cookies (bearer header only), so a
            // forged cross-site request cannot carry credentials; leaving CSRF on would just break POSTs.
            .csrf(AbstractHttpConfigurer::disable)
            .cors(Customizer.withDefaults())                       // uses the CorsConfigurationSource bean below
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()      // CORS pre-flight
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/register").permitAll()
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                .requestMatchers("/api/**").authenticated()
                // Default-deny: any URL we forgot to classify is closed, not open.
                .anyRequest().denyAll())
            .oauth2ResourceServer(oauth -> oauth
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter))
                .authenticationEntryPoint(errorHandlers)
                .accessDeniedHandler(errorHandlers))
            .exceptionHandling(e -> e
                .authenticationEntryPoint(errorHandlers)
                .accessDeniedHandler(errorHandlers));
        return http.build();
    }

    /** BCrypt: slow, salted hashing. Strength 10 (default) = ~100ms per hash on a laptop. */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    private static SecretKey signingKey(SecurityProperties props) {
        return new SecretKeySpec(props.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    /** Signs tokens we issue at login. HS256 = HMAC-SHA256 with the shared secret. */
    @Bean
    JwtEncoder jwtEncoder(SecurityProperties props) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(signingKey(props)));
    }

    /**
     * Verifies tokens on every request: signature, expiry, AND issuer. Pinning the algorithm to
     * HS256 blocks "alg: none" / algorithm-confusion attacks.
     */
    @Bean
    JwtDecoder jwtDecoder(SecurityProperties props) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(signingKey(props))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(TokenService.ISSUER));
        return decoder;
    }

    /** Only the configured frontend origins may call the API from a browser. */
    @Bean
    CorsConfigurationSource corsConfigurationSource(SecurityProperties props) {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(props.corsAllowedOrigins());
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Correlation-Id", "Idempotency-Key"));
        cfg.setExposedHeaders(List.of("X-Correlation-Id", "Location"));
        cfg.setAllowCredentials(false);   // no cookies involved
        cfg.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }
}
