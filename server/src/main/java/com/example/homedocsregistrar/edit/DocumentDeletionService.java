package com.example.homedocsregistrar.edit;

import com.example.homedocsregistrar.domain.DocumentPage;
import com.example.homedocsregistrar.reminder.ReminderService;
import com.example.homedocsregistrar.reminder.ReminderService.ReminderCleanup;
import com.example.homedocsregistrar.repository.DocumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Deletes a document from the registry, drops its reminders in notifier, and reports what to clean up on
 * Telegram. A bot can delete a channel message only within 48h of posting, so the caller removes the
 * archived file(s) only when {@link DeletionInfo#withinDeleteWindow()} is true; for older documents the
 * file is left in the channel (the bot couldn't delete it anyway) and only the registry row goes.
 */
@Service
public class DocumentDeletionService {

    /** Telegram lets a bot delete a message only within 48h of posting. */
    private static final long DELETE_WINDOW_HOURS = 48;

    private final DocumentRepository documents;
    private final ReminderService reminders;
    private final TransactionTemplate transactions;

    public DocumentDeletionService(DocumentRepository documents, ReminderService reminders,
                                   PlatformTransactionManager transactionManager) {
        this.documents = documents;
        this.reminders = reminders;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * The info needed to confirm and finish a deletion: the title, the archived channel message ids, and
     * whether they're still deletable (document posted less than 48h before {@code now}).
     */
    @Transactional(readOnly = true)
    public Optional<DeletionInfo> info(long documentId, Instant now) {
        return documents.findWithPagesById(documentId).map(doc -> {
            List<Integer> channelMessageIds = doc.getPages().stream()
                    .map(DocumentPage::getChannelMessageId)
                    .filter(Objects::nonNull)
                    .map(Long::intValue)
                    .toList();
            boolean within = doc.getCreatedAt() != null
                    && doc.getCreatedAt().isAfter(now.minus(Duration.ofHours(DELETE_WINDOW_HOURS)));
            return new DeletionInfo(doc.getId(), doc.getTitle(), channelMessageIds, within);
        });
    }

    /**
     * Delete the document row (its pages cascade), then — best-effort, outside the transaction so a slow
     * notifier never holds it — every user's reminders for it. The document is deleted even when notifier
     * is down; the result then says the reminders may remain.
     */
    public DeletionResult delete(long documentId) {
        boolean deleted = Boolean.TRUE.equals(transactions.execute(status -> {
            if (!documents.existsById(documentId)) {
                return false;
            }
            documents.deleteById(documentId);
            return true;
        }));
        if (!deleted) {
            return new DeletionResult(false, ReminderCleanup.ofSkipped());
        }
        return new DeletionResult(true, reminders.deleteAllForDocument(documentId));
    }

    /** Title (for the confirmation) plus the archive-cleanup plan. */
    public record DeletionInfo(long id, String title, List<Integer> channelMessageIds, boolean withinDeleteWindow) {
    }

    /** Whether the document existed (and is now gone), and how the notifier reminder cleanup went. */
    public record DeletionResult(boolean deleted, ReminderCleanup reminders) {
    }
}
