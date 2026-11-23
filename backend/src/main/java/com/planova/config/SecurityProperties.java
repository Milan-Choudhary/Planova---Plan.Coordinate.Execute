package com.planova.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Typed view of the {@code planova.security.*} block in application.yml.
 *
 * <p>{@code @ConfigurationProperties} binds YAML/env values onto this immutable record;
 * {@code @Validated} makes Spring run Bean Validation at STARTUP, so a missing or too-short
 * JWT secret stops the application immediately instead of weakening security silently.
 */
@Validated
@ConfigurationProperties(prefix = "planova.security")
public record SecurityProperties(
        @NotBlank
        @Size(min = 32, message = "JWT_SECRET must be at least 32 characters (HS256 needs a 256-bit key)")
        String jwtSecret,

        @Min(1) @Max(1440)
        long jwtExpiryMinutes,

        @NotEmpty
        List<String> corsAllowedOrigins) {
}
