package com.example.homedocsregistrar.repository;

import com.example.homedocsregistrar.domain.CatalogSection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CatalogSectionRepository extends JpaRepository<CatalogSection, Long> {

    /** Resolve the section a scanned QR code refers to. */
    Optional<CatalogSection> findByCode(String code);

    /** Top-level sections (owners), oldest first — the roots of the two-level tree. */
    List<CatalogSection> findByParentIsNullOrderByIdAsc();

    /** Subsections of a given top-level section, oldest first. */
    List<CatalogSection> findByParentOrderByIdAsc(CatalogSection parent);

    /** All leaf subsections (parent set), oldest first — the buckets a document can be filed into. */
    List<CatalogSection> findByParentIsNotNullOrderByIdAsc();

    /** Near-duplicate guard for a typed-in top-level name (case-insensitive). */
    Optional<CatalogSection> findByParentIsNullAndLabelIgnoreCase(String label);

    /** Near-duplicate guard for a typed-in subsection name within one parent (case-insensitive). */
    Optional<CatalogSection> findByParentAndLabelIgnoreCase(CatalogSection parent, String label);
}
