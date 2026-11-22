package com.planova.identity;

import com.planova.identity.IdentityDtos.LoginRequest;
import com.planova.identity.IdentityDtos.LoginResponse;
import com.planova.identity.IdentityDtos.RegisterRequest;
import com.planova.identity.IdentityDtos.UserResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Controllers only translate HTTP <-> service calls; business rules live in the services.
 * {@code @RestController} = {@code @Controller} + {@code @ResponseBody} (return values become JSON).
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final UserService userService;

    public AuthController(AuthService authService, UserService userService) {
        this.authService = authService;
        this.userService = userService;
    }

    /** Public endpoint (see SecurityConfig). {@code @Valid} triggers Bean Validation on the body. */
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request.email(), request.password());
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@Valid @RequestBody RegisterRequest request) {
        return userService.register(request);
    }

    /**
     * Returns the CURRENT user, read from the database. The frontend calls this on page load to
     * restore the session and to learn the real role.
     * {@code @AuthenticationPrincipal Jwt} injects the verified token; its subject is the user id.
     */
    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        return userService.get(UUID.fromString(jwt.getSubject()));
    }
}
