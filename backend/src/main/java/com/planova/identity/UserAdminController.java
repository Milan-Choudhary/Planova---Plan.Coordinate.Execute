package com.planova.identity;

import com.planova.common.PageResponse;
import com.planova.identity.IdentityDtos.CreateUserRequest;
import com.planova.identity.IdentityDtos.UpdateUserRequest;
import com.planova.identity.IdentityDtos.UserResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administrator-only user management.
 * {@code @PreAuthorize("hasRole('ADMIN')")} on the CLASS guards every method; it is checked on the
 * server for every call, so a VIEWER calling this URL directly (curl, Postman) gets 403.
 */
@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class UserAdminController {

    private static final int MAX_PAGE_SIZE = 100;

    private final UserService userService;

    public UserAdminController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public PageResponse<UserResponse> list(@RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        // Clamp client-supplied paging so nobody can request a million rows.
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return PageResponse.of(userService.list(
                PageRequest.of(safePage, safeSize, Sort.by("createdAt").descending().and(Sort.by("id")))));
    }

    @PostMapping
    public ResponseEntity<UserResponse> create(@AuthenticationPrincipal Jwt jwt,
                                               @Valid @RequestBody CreateUserRequest request) {
        UserResponse created = userService.create(UUID.fromString(jwt.getSubject()), request);
        return ResponseEntity.created(URI.create("/api/v1/admin/users/" + created.id())).body(created);
    }

    @PatchMapping("/{id}")
    public UserResponse update(@AuthenticationPrincipal Jwt jwt,
                               @PathVariable UUID id,
                               @RequestBody UpdateUserRequest request) {
        return userService.update(UUID.fromString(jwt.getSubject()), id, request);
    }
}
