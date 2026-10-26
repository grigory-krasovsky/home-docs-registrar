package com.example.homedocsregistrar.edit;

import com.example.homedocsregistrar.domain.Document;
import com.example.homedocsregistrar.edit.DocumentDeletionService.DeletionResult;
import com.example.homedocsregistrar.reminder.NotifierClient;
import com.example.homedocsregistrar.reminder.NotifierUnavailableException;
import com.example.homedocsregistrar.reminder.ReminderService;
import com.example.homedocsregistrar.reminder.ReminderService.ReminderCleanup.Outcome;
import com.example.homedocsregistrar.repository.DocumentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Deleting a document also drops its notifier reminders — best-effort, never blocking the deletion. */
@SpringBootTest
@Transactional
class DocumentDeletionRemindersTest {

    @Autowired
    private DocumentDeletionService service;

    @Autowired
    private DocumentRepository documents;

    @MockitoBean
    private NotifierClient notifier;

    private Document save(String hash) {
        Document doc = new Document();
        doc.setTitle("Гарантия");
        doc.setContentHash(hash);
        return documents.save(doc);
    }

    @Test
    void deletesTheDocumentsRemindersInNotifier() {
        Document doc = save("rem-del-ok");
        when(notifier.isEnabled()).thenReturn(true);
        when(notifier.deleteEventsByRef(ReminderService.SOURCE, "document:" + doc.getId())).thenReturn(2);

        DeletionResult result = service.delete(doc.getId());

        assertThat(result.deleted()).isTrue();
        assertThat(result.reminders().outcome()).isEqualTo(Outcome.DONE);
        assertThat(result.reminders().deleted()).isEqualTo(2);
        verify(notifier).deleteEventsByRef(ReminderService.SOURCE, "document:" + doc.getId());
    }

    @Test
    void documentIsDeletedEvenWhenNotifierIsDown() {
        Document doc = save("rem-del-down");
        when(notifier.isEnabled()).thenReturn(true);
        when(notifier.deleteEventsByRef(ReminderService.SOURCE, "document:" + doc.getId()))
                .thenThrow(new NotifierUnavailableException("connection refused", null));

        DeletionResult result = service.delete(doc.getId());

        assertThat(result.deleted()).isTrue();
        assertThat(result.reminders().isFailed()).isTrue(); // -> the bot warns the user
        assertThat(documents.findById(doc.getId())).isEmpty();
    }
}
