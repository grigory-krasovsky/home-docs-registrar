package com.example.homedocsregistrar.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A physical section of the paper card catalog, identified by the QR code stuck on it. */
@Entity
@Table(name = "catalog_section")
public class CatalogSection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Content encoded in the section's QR code; unique. */
    @Column(nullable = false, unique = true)
    private String code;

    /** Human-friendly name of the section. */
    @Column(nullable = false)
    private String label;

    protected CatalogSection() { // for JPA
    }

    public CatalogSection(String code, String label) {
        this.code = code;
        this.label = label;
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
}
