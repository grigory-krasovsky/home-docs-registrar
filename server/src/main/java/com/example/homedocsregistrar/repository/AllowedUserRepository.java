package com.example.homedocsregistrar.repository;

import com.example.homedocsregistrar.domain.AllowedUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AllowedUserRepository extends JpaRepository<AllowedUser, Long> {

    /** Admins who should receive access requests. */
    List<AllowedUser> findByAdminTrue();
}
