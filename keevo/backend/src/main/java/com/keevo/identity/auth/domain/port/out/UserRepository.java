package com.keevo.identity.auth.domain.port.out;

import com.keevo.identity.auth.domain.model.User;

import java.util.Optional;
import java.util.UUID;

/**
 * UserRepository — Driven port for user persistence.
 *
 * <p>Interface — implemented in the persistence adapter layer.
 * Operations target the PUBLIC schema (global user registry).
 */
public interface UserRepository {

    /** Persist a new user. Returns the saved user (with generated ID if any). */
    User save(User user);

    /** Find user by ID. */
    Optional<User> findById(UUID id);

    /** Find user by phone number. Returns empty if not found. */
    Optional<User> findByPhoneNumber(String phoneNumber);

    /** Check if a phone number is already registered (for fast uniqueness check). */
    boolean existsByPhoneNumber(String phoneNumber);
}
