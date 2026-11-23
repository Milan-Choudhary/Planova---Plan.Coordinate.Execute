package com.planova.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * Persistent user (table {@code app_user}; "user" is a reserved word in PostgreSQL).
 *
 * <p>Annotation guide:
 * <ul>
 *   <li>{@code @Entity} / {@code @Table}: maps the class to the table created by migration V1.</li>
 *   <li>{@code @Id @GeneratedValue(UUID)}: Hibernate generates the UUID in Java before INSERT.</li>
 *   <li>{@code @Enumerated(STRING)}: stores "ADMIN" not the ordinal 0 - reordering the enum later
 *       must never silently change what is in the database.</li>
 *   <li>{@code @Version}: optimistic locking. Every UPDATE is {@code WHERE version = ?}; if another
 *       transaction changed the row first, Hibernate throws OptimisticLockingFailureException
 *       (mapped to HTTP 409). It is a Long WRAPPER so "null" cleanly means "not saved yet".</li>
 *   <li>{@code @PrePersist/@PreUpdate}: lifecycle hooks keeping the timestamps current.</li>
 * </ul>
 *
 * <p>No setters: state changes go through intention-revealing methods so invariants stay in one place.
 */
@Entity
@Table(name = "app_user")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 254)
    private String email;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status = UserStatus.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    /** Required by JPA; protected so application code cannot create half-initialised users. */
    protected User() {
    }

    public User(String email, String name, String passwordHash, Role role) {
        this.email = email;
        this.name = name;
        this.passwordHash = passwordHash;
        this.role = role;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public void changeRole(Role newRole) {
        this.role = newRole;
    }

    public void changeStatus(UserStatus newStatus) {
        this.status = newStatus;
    }

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getName() { return name; }
    public String getPasswordHash() { return passwordHash; }
    public Role getRole() { return role; }
    public UserStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }
}
