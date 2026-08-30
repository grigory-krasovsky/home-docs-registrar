package com.example.homedocsregistrar.domain;

/** Where a document's file stands relative to the owned home-PC backup (Telegram is the primary store). */
public enum BackupStatus {

    /** On Telegram (primary) but not yet mirrored to the home PC. */
    PENDING_BACKUP,

    /** Confirmed written to the home-PC backup folder. */
    BACKED_UP
}
