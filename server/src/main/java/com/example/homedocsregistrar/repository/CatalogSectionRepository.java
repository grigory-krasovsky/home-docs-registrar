package com.example.homedocsregistrar.repository;

import com.example.homedocsregistrar.domain.CatalogSection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CatalogSectionRepository extends JpaRepository<CatalogSection, Long> {

    /** Resolve the section a scanned QR code refers to. */
    Optional<CatalogSection> findByCode(String code);
}
