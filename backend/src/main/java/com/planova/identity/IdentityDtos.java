package com.planova.identity;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Request/response shapes of the identity API (the contract is mirrored in API_CONTRACTS.md).
 * DTOs, not entities, cross the HTTP boundary: the password hash can never leak by accident
 * and the JSON stays stable when the table changes.
 */
public final class IdentityDtos {

    private IdentityDtos() {
    }

    public record LoginRequest(
            @NotBlank @Size(max = 254) String email,
            @NotBlank @Size(max = 200) String password) {
    }

    public record UserResponse(UUID id, String email, String name, Role role, UserStatus status) {
        public static UserResponse from(User u) {
            return new UserResponse(u.getId(), u.getEmail(), u.getName(), u.getRole(), u.getStatus());
        }
    }

    public record LoginResponse(String accessToken, String tokenType, long expiresInSeconds, UserResponse user) {
    }

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Size(min = 12, max = 72) String password,
            Role role) {
        public RegisterRequest {
            if (email != null) {
                email = email.trim();
            }
        }
    }

    public record CreateUserRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 120) String name,
            @NotNull Role role,
            // 72 = BCrypt's hard limit. Multi-byte characters are re-checked in UserService.
            @NotBlank @Size(min = 12, max = 72, message = "must be between 12 and 72 characters") String password) {
        public CreateUserRequest {
            if (email != null) {
                email = email.trim();
            }
        }
    }

    /** Partial update: only non-null fields are applied. At least one must be present. */
    public record UpdateUserRequest(Role role, UserStatus status) {
    }
}
