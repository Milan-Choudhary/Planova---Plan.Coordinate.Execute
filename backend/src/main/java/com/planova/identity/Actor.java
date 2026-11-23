package com.planova.identity;

import java.util.UUID;
import org.springframework.security.core.Authentication;

/**
 * The authenticated caller as the services see it: id + CURRENT role.
 *
 * <p>Built from the {@link Authentication} that {@link DatabaseBackedJwtConverter} produced, so the
 * role here was read from the database on this very request - never taken from the token.
 * Services receive an Actor instead of a raw JWT, which keeps them free of web/security types.
 */
public record Actor(UUID id, Role role) {

    public static Actor of(Authentication auth) {
        UUID id = UUID.fromString(auth.getName());
        Role role = auth.getAuthorities().stream()
                .map(a -> a.getAuthority())
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> Role.valueOf(a.substring("ROLE_".length())))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Authenticated user without a role"));
        return new Actor(id, role);
    }

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }

    /** VIEWERs are read-only everywhere, whatever per-plan membership they were given. */
    public boolean canWrite() {
        return role != Role.VIEWER;
    }

    public boolean canCreatePlans() {
        return role == Role.ADMIN || role == Role.ORGANIZER;
    }
}
