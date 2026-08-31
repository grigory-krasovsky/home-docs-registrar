package com.example.homedocsregistrar.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A Telegram user allowed to use the bot. The whole allow-list lives here (not in config) so people can
 * be added/removed at runtime without a restart. {@code admin} users can approve access requests. An
 * empty table means nobody is allowed yet — bootstrap the first admin with {@code /claim}.
 */
@Entity
@Table(name = "allowed_user")
public class AllowedUser {

    @Id
    @Column(name = "telegram_user_id")
    private Long telegramUserId;

    @Column(nullable = false)
    private boolean admin;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "added_at", nullable = false)
    private Instant addedAt = Instant.now();

    protected AllowedUser() { // for JPA
    }

    public AllowedUser(Long telegramUserId, boolean admin, String displayName) {
        this.telegramUserId = telegramUserId;
        this.admin = admin;
        this.displayName = displayName;
    }

    public Long getTelegramUserId() {
        return telegramUserId;
    }

    public boolean isAdmin() {
        return admin;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Instant getAddedAt() {
        return addedAt;
    }
}
