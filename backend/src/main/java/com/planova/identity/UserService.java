package com.planova.identity;

import com.planova.audit.AuditService;
import com.planova.common.ApiException;
import com.planova.identity.IdentityDtos.CreateUserRequest;
import com.planova.identity.IdentityDtos.RegisterRequest;
import com.planova.identity.IdentityDtos.UpdateUserRequest;
import com.planova.identity.IdentityDtos.UserResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * User administration. Each method is ONE transaction: the user change and its audit row
 * commit together or not at all.
 */
@Service
public class UserService {

    static final String PLATFORM_ADMIN_EMAIL = "ajnr@planovaplaform";
    private static final int BCRYPT_MAX_BYTES = 72;

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;

    public UserService(UserRepository users, PasswordEncoder passwordEncoder, AuditService audit) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public UserResponse get(UUID id) {
        return UserResponse.from(find(id));
    }

    @Transactional(readOnly = true)
    public Page<UserResponse> list(Pageable pageable) {
        return users.findAll(pageable).map(UserResponse::from);
    }

    @Transactional
    public UserResponse create(UUID actorId, CreateUserRequest req) {
        if (req.role() == Role.ADMIN) {
            throw ApiException.forbidden("SYSTEM_ROLE", "Platform admins are provisioned by the system");
        }
        return createAccount(actorId, req.email(), req.name(), req.password(), req.role());
    }

    @Transactional
    public UserResponse register(RegisterRequest req) {
        Role role = req.role() == null ? Role.VIEWER : req.role();
        if (role == Role.ADMIN) {
            throw ApiException.forbidden("SYSTEM_ROLE", "Platform admins are provisioned by the system");
        }
        return createAccount(null, req.email(), req.name(), req.password(), role);
    }

    private UserResponse createAccount(UUID actorId, String rawEmail, String name, String password, Role role) {
        // BCrypt silently ignores bytes past 72; characters like "é" use 2+ bytes, so the
        // @Size(max=72) on characters is not enough. Check the real byte length.
        if (password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
            throw ApiException.badRequest("PASSWORD_TOO_LONG", "Password must be at most 72 bytes");
        }
        String email = rawEmail.trim().toLowerCase(Locale.ROOT);
        if (PLATFORM_ADMIN_EMAIL.equals(email)) {
            throw ApiException.conflict("EMAIL_TAKEN", "This email is reserved");
        }
        if (users.existsByEmail(email)) {
            throw ApiException.conflict("EMAIL_TAKEN", "A user with this email already exists");
        }

        // saveAndFlush (not save) sends the INSERT now, so a concurrent duplicate that slipped past
        // the exists-check hits the unique index HERE and becomes a clean 409, not a late commit failure.
        User saved = users.saveAndFlush(
                new User(email, name.trim(), passwordEncoder.encode(password), role));

        audit.record(actorId == null ? saved.getId() : actorId, "USER_CREATED", "USER",
                saved.getId(), null, null, snapshot(saved));
        return UserResponse.from(saved);
    }

    @Transactional
    public UserResponse update(UUID actorId, UUID targetId, UpdateUserRequest req) {
        if (req.role() == Role.ADMIN) {
            throw ApiException.forbidden("SYSTEM_ROLE", "Platform admins are provisioned by the system");
        }
        if (req.role() == null && req.status() == null) {
            throw ApiException.badRequest("EMPTY_UPDATE", "Provide a role and/or a status to change");
        }
        // An admin cannot demote/disable themselves: prevents accidental lock-out.
        if (actorId.equals(targetId)) {
            throw ApiException.conflict("CANNOT_MODIFY_SELF", "You cannot change your own role or status");
        }

        User user = find(targetId);
        if (PLATFORM_ADMIN_EMAIL.equals(user.getEmail())) {
            throw ApiException.forbidden("SYSTEM_ROLE", "This platform admin is managed by the system");
        }
        Map<String, Object> before = snapshot(user);

        Role newRole = req.role() != null ? req.role() : user.getRole();
        UserStatus newStatus = req.status() != null ? req.status() : user.getStatus();

        // Never leave the system without an active administrator.
        boolean losesAdminPower = user.getRole() == Role.ADMIN && user.isActive()
                && (newRole != Role.ADMIN || newStatus != UserStatus.ACTIVE);
        if (losesAdminPower && users.countByRoleAndStatus(Role.ADMIN, UserStatus.ACTIVE) <= 1) {
            throw ApiException.conflict("LAST_ADMIN", "At least one active administrator must remain");
        }

        user.changeRole(newRole);
        user.changeStatus(newStatus);
        // @Version: if a second admin edited this row concurrently, the flush below throws
        // OptimisticLockingFailureException -> HTTP 409 STALE_VERSION.
        users.saveAndFlush(user);

        audit.record(actorId, "USER_UPDATED", "USER", user.getId(), null, before, snapshot(user));
        return UserResponse.from(user);
    }

    private User find(UUID id) {
        return users.findById(id)
                .orElseThrow(() -> ApiException.notFound("USER_NOT_FOUND", "User not found"));
    }

    /** Audit snapshot. Deliberately EXCLUDES the password hash. */
    private static Map<String, Object> snapshot(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("email", u.getEmail());
        m.put("name", u.getName());
        m.put("role", u.getRole().name());
        m.put("status", u.getStatus().name());
        return m;
    }
}
