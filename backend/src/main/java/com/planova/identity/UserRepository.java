package com.planova.identity;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data generates the implementation at startup; we only declare queries.
 * The explicit {@code lower(...) = lower(...)} JPQL matches the functional index
 * {@code ux_app_user_email_lower} created in V1, so lookups are case-insensitive AND indexed.
 */
public interface UserRepository extends JpaRepository<User, UUID> {

    @Query("select u from User u where lower(u.email) = lower(:email)")
    Optional<User> findByEmail(@Param("email") String email);

    @Query("select count(u) > 0 from User u where lower(u.email) = lower(:email)")
    boolean existsByEmail(@Param("email") String email);

    long countByRoleAndStatus(Role role, UserStatus status);
}
