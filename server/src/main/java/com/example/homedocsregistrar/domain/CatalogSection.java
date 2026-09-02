package com.example.homedocsregistrar.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * A section of the paper card catalog. Sections form a shallow two-level tree: a top-level section
 * (an owner like «Гриша»/«Общая», {@link #parent} == null) holds subsections (like «Медицина»/«Авто»).
 * A document is filed into a leaf subsection. A physical QR {@link #code} is optional — sections are
 * created by name from the bot; a QR sticker is bound to a drawer later, if ever.
 */
@Entity
@Table(name = "catalog_section")
public class CatalogSection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Content encoded in the section's QR code; unique when present, null until a drawer is labelled. */
    @Column(unique = true)
    private String code;

    /** Human-friendly name of the section (e.g. «Медицина и здоровье»). */
    @Column(nullable = false)
    private String label;

    /** The owning top-level section; null for a top-level section itself. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private CatalogSection parent;

    protected CatalogSection() { // for JPA
    }

    /** A top-level section carrying a physical QR code. */
    public CatalogSection(String code, String label) {
        this.code = code;
        this.label = label;
    }

    /** A section by name, optionally under a parent (null = top-level); no QR code yet. */
    public CatalogSection(String label, CatalogSection parent) {
        this.label = label;
        this.parent = parent;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public CatalogSection getParent() {
        return parent;
    }

    public void setParent(CatalogSection parent) {
        this.parent = parent;
    }

    public boolean isTopLevel() {
        return parent == null;
    }

    /** Display path: «Раздел / Подсекция», or just the label for a top-level section. */
    public String path() {
        return parent == null ? label : parent.getLabel() + " / " + label;
    }
}
