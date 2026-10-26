package com.example.homedocsregistrar.reminder;

import com.example.homedocsregistrar.reminder.ReminderService.ReminderCleanup.Outcome;
import com.example.homedocsregistrar.reminder.ReminderService.ReminderDraft;
import com.example.homedocsregistrar.telegram.TelegramSender;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** What the registrar sends to notifier: source, ref, deep link; and the best-effort document cleanup. */
class ReminderServiceTest {

    private final NotifierClient client = mock(NotifierClient.class);
    private final TelegramSender sender = mock(TelegramSender.class);
    private final ReminderService service = new ReminderService(client,
            new NotifierProperties("http://notifier.test", "t", "@NotifierBot", Duration.ofSeconds(2), Duration.ofSeconds(5)),
            sender, true, "Europe/Moscow");

    @Test
    void createSendsSourceRefAndDeepLink() {
        when(client.createEvent(any())).thenReturn(new NotifierClient.CreateResult(null, true));
        when(sender.botUsername()).thenReturn("HomeDocsBot");

        service.create(123456789L, 42L, new ReminderDraft("ext-1", "  Истекает   гарантия ",
                LocalDate.of(2027, 3, 8), LocalTime.of(9, 5), 1440));

        ArgumentCaptor<NewEvent> sent = ArgumentCaptor.forClass(NewEvent.class);
        verify(client).createEvent(sent.capture());
        NewEvent event = sent.getValue();
        assertThat(event.telegramUserId()).isEqualTo(123456789L);
        assertThat(event.source()).isEqualTo("home-docs");
        assertThat(event.externalId()).isEqualTo("ext-1");
        assertThat(event.ref()).isEqualTo("document:42");
        assertThat(event.name()).isEqualTo("Истекает гарантия");
        assertThat(event.fireDate()).isEqualTo("2027-03-08");
        assertThat(event.fireTime()).isEqualTo("09:05");
        assertThat(event.nagIntervalMinutes()).isEqualTo(1440);
        assertThat(event.link()).isEqualTo(new NotifierLink("Документ #42", "https://t.me/HomeDocsBot?start=doc_42"));
    }

    @Test
    void createWithoutTimeRepeatOrKnownBotUsernameOmitsThem() {
        when(client.createEvent(any())).thenReturn(new NotifierClient.CreateResult(null, true));
        when(sender.botUsername()).thenReturn(null); // getMe failed -> no link rather than a broken one

        service.create(1L, 7L, new ReminderDraft("ext-2", "Оплата", LocalDate.of(2027, 1, 10), null, null));

        ArgumentCaptor<NewEvent> sent = ArgumentCaptor.forClass(NewEvent.class);
        verify(client).createEvent(sent.capture());
        assertThat(sent.getValue().fireTime()).isNull();
        assertThat(sent.getValue().nagIntervalMinutes()).isNull();
        assertThat(sent.getValue().link()).isNull();
    }

    @Test
    void cleanupReportsTheCountOrAFailureWithoutThrowing() {
        when(client.isEnabled()).thenReturn(true);
        when(client.deleteEventsByRef("home-docs", "document:42")).thenReturn(3);
        when(client.deleteEventsByRef("home-docs", "document:43"))
                .thenThrow(new NotifierUnavailableException("read timed out", null));

        assertThat(service.deleteAllForDocument(42L)).isEqualTo(new ReminderService.ReminderCleanup(Outcome.DONE, 3));
        assertThat(service.deleteAllForDocument(43L).isFailed()).isTrue();
    }

    @Test
    void cleanupIsSkippedWhenTheIntegrationIsOff() {
        when(client.isEnabled()).thenReturn(false);

        assertThat(service.deleteAllForDocument(42L).outcome()).isEqualTo(Outcome.SKIPPED);
        verify(client).isEnabled();
        verifyNoInteractions(sender);
        assertThat(service.suggestionsEnabled()).isFalse();
    }

    @Test
    void connectHintNamesTheNotifierBotAndZoneFallsBack() {
        assertThat(service.connectHint()).isEqualTo("Чтобы получать напоминания, запусти @NotifierBot и нажми /start");
        assertThat(service.zone(null)).isEqualTo(ZoneId.of("Europe/Moscow"));
        assertThat(service.zone(new NotifierUser(1L, "Asia/Novosibirsk", null, null)))
                .isEqualTo(ZoneId.of("Asia/Novosibirsk"));
        assertThat(service.zone(new NotifierUser(1L, "Mars/Olympus", null, null)))
                .isEqualTo(ZoneId.of("Europe/Moscow"));

        ReminderService unnamed = new ReminderService(client,
                new NotifierProperties("http://n", "t", "", null, null), sender, true, "UTC");
        assertThat(unnamed.connectHint()).contains("бота-напоминалку");
        assertThat(ReminderService.newExternalId()).hasSize(36).isNotEqualTo(ReminderService.newExternalId());
    }
}
