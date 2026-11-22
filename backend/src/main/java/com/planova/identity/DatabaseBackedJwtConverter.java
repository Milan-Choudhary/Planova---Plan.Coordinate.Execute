package com.planova.identity;

import java.util.List;
import java.util.UUID;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Runs AFTER a token's signature and expiry were verified. It turns the token into the
 * authenticated principal by consulting the DATABASE:
 *
 * <ul>
 *   <li>unknown or DISABLED user -> 401 on the very next request (no waiting for token expiry)</li>
 *   <li>authorities come from the user's CURRENT role in the database, not from the token, so a
 *       demoted admin loses admin rights immediately and a forged "role" claim is worthless</li>
 * </ul>
 *
 * Cost: one primary-key lookup per request. Accepted for this project's scale (DECISIONS.md D-005).
 *
 * <p>Must throw an AuthenticationException subtype (InvalidBearerTokenException): any other
 * exception would surface as HTTP 500 instead of 401.
 */
@Component
public class DatabaseBackedJwtConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final UserRepository users;

    public DatabaseBackedJwtConverter(UserRepository users) {
        this.users = users;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        UUID userId;
        try {
            userId = UUID.fromString(jwt.getSubject());
        } catch (RuntimeException e) {
            throw new InvalidBearerTokenException("Token subject is not a valid user id");
        }

        User user = users.findById(userId)
                .filter(User::isActive)
                .orElseThrow(() -> new InvalidBearerTokenException("Unknown or disabled user"));

        // "ROLE_" prefix is what hasRole('ADMIN') expects.
        List<SimpleGrantedAuthority> authorities =
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
        // principal = the Jwt itself, name = user id; controllers read the id via @AuthenticationPrincipal Jwt.
        return new JwtAuthenticationToken(jwt, authorities, user.getId().toString());
    }
}
