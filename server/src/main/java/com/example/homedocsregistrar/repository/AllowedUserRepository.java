package com.example.homedocsregistrar.repository;

import com.example.homedocsregistrar.domain.AllowedUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AllowedUserRepository extends JpaRepository<AllowedUser, Long> {

    /** True once at least one admin exists (so {@code /claim} self-disables after the first claim). */
    boolean existsByAdminTrue();

    /** Admins who should receive access requests. */
    List<AllowedUser> findByAdminTrue();
}
