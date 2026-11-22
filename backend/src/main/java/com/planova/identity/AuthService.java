package com.planova.identity;

import com.planova.common.ApiException;
import com.planova.identity.IdentityDtos.LoginResponse;
import com.planova.identity.IdentityDtos.UserResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    /**
     * A real BCrypt hash of a throw-away value. When the email is unknown we still run one BCrypt
     * comparison against it, so "no such user" and "wrong password" take about the same time -
     * otherwise response timing would reveal which emails are registered.
     */
    private final String dummyHash;

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokens;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, TokenService tokens) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.dummyHash = passwordEncoder.encode("planova-timing-equalisation-value");
    }

    @Transactional(readOnly = true)
    public LoginResponse login(String email, String password) {
        User user = users.findByEmail(email.trim()).orElse(null);

        boolean passwordOk = passwordEncoder.matches(password, user != null ? user.getPasswordHash() : dummyHash);

        // ONE generic failure for every cause (unknown email, wrong password, disabled account):
        // the response must not tell an attacker which part was wrong.
        if (user == null || !passwordOk || !user.isActive()) {
            throw ApiException.unauthorized("INVALID_CREDENTIALS", "Invalid email or password");
        }

        TokenService.IssuedToken token = tokens.issue(user);
        return new LoginResponse(token.value(), "Bearer", token.expiresInSeconds(), UserResponse.from(user));
    }
}
