package com.example.homedocsregistrar.telegram;

import com.example.homedocsregistrar.domain.Document;
import com.example.homedocsregistrar.extraction.ReminderHint;
import com.example.homedocsregistrar.reminder.NotifierApiException;
import com.example.homedocsregistrar.reminder.NotifierClient;
import com.example.homedocsregistrar.reminder.NotifierErrorCode;
import com.example.homedocsregistrar.reminder.NotifierUnavailableException;
import com.example.homedocsregistrar.reminder.NotifierUser;
import com.example.homedocsregistrar.reminder.ReminderService;
import com.example.homedocsregistrar.reminder.ReminderService.ReminderDraft;
import com.example.homedocsregistrar.retrieval.DocumentRetrievalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The reminder dialog's state handling: stable externalIds, surviving failures, the not-connected hint, the form. */
class ReminderDialogTest {

    private static final long CHAT = 123456789L;
    private static final long DOC = 42L;
    private static final int CARD_MESSAGE = 500;
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 26);
    private static final NotifierUser USER = new NotifierUser(CHAT, "Europe/Moscow", LocalTime.of(9, 0), LocalTime.of(22, 0));

    private final TelegramSender sender = mock(TelegramSender.class);
    private final ReminderService reminders = mock(ReminderService.class);
    private final DocumentRetrievalService retrieval = mock(DocumentRetrievalService.class);
    private final ReminderDialog dialog = new ReminderDialog(sender, reminders, retrieval);
    private final Document document = mock(Document.class);

    @BeforeEach
    void setUp() {
        when(reminders.isEnabled()).thenReturn(true);
        when(reminders.suggestionsEnabled()).thenReturn(true);
        when(reminders.today(any())).thenReturn(TODAY);
        when(reminders.now(any())).thenReturn(TODAY.atTime(12, 0));
        when(reminders.zone(any())).thenReturn(ZoneId.of("Europe/Moscow"));
        when(reminders.connectHint()).thenReturn("Чтобы получать напоминания, запусти @NotifierBot и нажми /start");
        when(document.getId()).thenReturn(DOC);
        when(document.getTitle()).thenReturn("Холодильник Bosch");
        when(document.getDocDate()).thenReturn(LocalDate.of(2025, 3, 22));
        when(retrieval.byId(DOC)).thenReturn(Optional.of(document));
        when(sender.send(eq(CHAT), anyString(), any())).thenReturn(CARD_MESSAGE);
    }

    private static CallbackQuery tap(String data, int messageId) {
        Message message = new Message();
        message.setChat(Chat.builder().id(CHAT).type("private").build());
        message.setMessageId(messageId);
        CallbackQuery callback = new CallbackQuery();
        callback.setId("cb-" + data);
        callback.setData(data);
        callback.setMessage(message);
        return callback;
    }

    private void sendCardWithWarrantySuggestion() {
        List<InlineKeyboardRow> base = List.of(new InlineKeyboardRow(
                InlineKeyboardButton.builder().text("📎 Открыть файл").callbackData("get:42").build()));
        dialog.sendSavedCard(CHAT, document, "Сохранено ✅ id=42", base,
                List.of(new ReminderHint("WARRANTY", "Истекает гарантия: холодильник Bosch", "2027-03-22")));
    }

    private static List<String> callbackData(InlineKeyboardMarkup keyboard) {
        return keyboard.getKeyboard().stream().flatMap(List::stream).map(InlineKeyboardButton::getCallbackData).toList();
    }

    @Test
    void suggestionRidesOnTheSavedCardAndRetriesReuseItsExternalId() {
        when(reminders.user(CHAT)).thenReturn(Optional.of(USER));
        when(reminders.create(eq(CHAT), eq(DOC), any()))
                .thenThrow(new NotifierUnavailableException("read timed out", null))
                .thenReturn(new NotifierClient.CreateResult(null, true));

        sendCardWithWarrantySuggestion();

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<InlineKeyboardMarkup> keyboard = ArgumentCaptor.forClass(InlineKeyboardMarkup.class);
        verify(sender).send(eq(CHAT), text.capture(), keyboard.capture());
        assertThat(text.getValue()).startsWith("Сохранено ✅ id=42")
                .contains("⏰ Предлагаю напомнить:", "08.03.2027 — Истекает гарантия: холодильник Bosch (срок 22.03.2027)");
        assertThat(callbackData(keyboard.getValue())).containsExactly("get:42", "rem:s:ok:42:0", "rem:s:ed:42:0", "rem:s:no:42:0");

        // notifier times out: the suggestion stays; the second tap succeeds with the SAME externalId.
        dialog.onCallback(tap("rem:s:ok:42:0", CARD_MESSAGE));
        verify(sender).answerCallback(eq("cb-rem:s:ok:42:0"), contains("не ответил"));
        dialog.onCallback(tap("rem:s:ok:42:0", CARD_MESSAGE));

        ArgumentCaptor<ReminderDraft> drafts = ArgumentCaptor.forClass(ReminderDraft.class);
        verify(reminders, times(2)).create(eq(CHAT), eq(DOC), drafts.capture());
        ReminderDraft first = drafts.getAllValues().get(0);
        assertThat(drafts.getAllValues().get(1).externalId()).isEqualTo(first.externalId());
        assertThat(first.date()).isEqualTo(LocalDate.of(2027, 3, 8)); // 14 days before the warranty ends
        assertThat(first.time()).isNull();
        assertThat(first.nagIntervalMinutes()).isEqualTo(1440);

        // The card is re-rendered in place: created line, no suggestion buttons left.
        verify(sender).edit(eq(CHAT), eq(CARD_MESSAGE), contains("✅ 08.03.2027"), keyboard.capture());
        assertThat(callbackData(keyboard.getValue())).containsExactly("get:42");
    }

    @Test
    void notConnectedUserSeesTheHintInsteadOfTheCreateButton() {
        when(reminders.user(CHAT)).thenReturn(Optional.empty());

        sendCardWithWarrantySuggestion();

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<InlineKeyboardMarkup> keyboard = ArgumentCaptor.forClass(InlineKeyboardMarkup.class);
        verify(sender).send(eq(CHAT), text.capture(), keyboard.capture());
        assertThat(text.getValue()).contains("запусти @NotifierBot и нажми /start");
        assertThat(callbackData(keyboard.getValue())).containsExactly("get:42", "rem:s:chk:42", "rem:s:none:42");

        // After /start in notifier, «🔄 Я подключился» brings the create buttons back.
        when(reminders.user(CHAT)).thenReturn(Optional.of(USER));
        dialog.onCallback(tap("rem:s:chk:42", CARD_MESSAGE));
        verify(sender).edit(eq(CHAT), eq(CARD_MESSAGE), anyString(), keyboard.capture());
        assertThat(callbackData(keyboard.getValue())).contains("rem:s:ok:42:0");
        verify(reminders, never()).create(anyLong(), anyLong(), any());
    }

    @Test
    void noSuggestionsMeansThePlainCard() {
        when(reminders.suggestionsEnabled()).thenReturn(false);

        sendCardWithWarrantySuggestion();

        verify(sender).send(eq(CHAT), eq("Сохранено ✅ id=42"), any());
        verify(reminders, never()).user(anyLong());
    }

    @Test
    void formWalksThroughTheStepsAndCreatesOnce() {
        when(reminders.user(CHAT)).thenReturn(Optional.of(USER));
        when(reminders.list(CHAT, DOC)).thenReturn(List.of());
        when(reminders.create(eq(CHAT), eq(DOC), any())).thenReturn(new NotifierClient.CreateResult(null, true));
        int list = 700;

        assertThat(dialog.onCallback(tap("rem:new:42", list))).isTrue();      // name step waits for text
        dialog.onCallback(tap("rem:f:keep", list));                           // keep «Холодильник Bosch»
        assertThat(dialog.onText(CHAT, 801, "31.02.2027")).isTrue();          // invalid -> stays on the date step
        verify(sender).edit(eq(CHAT), eq(list), contains("Не понял дату"), any());
        assertThat(dialog.onText(CHAT, 802, "15.11.2026")).isTrue();
        assertThat(dialog.onText(CHAT, 803, "10:30")).isTrue();
        assertThat(dialog.onText(CHAT, 804, "поиск по документам")).isFalse(); // repeat step takes buttons, not text
        dialog.onCallback(tap("rem:f:nag:60", list));
        dialog.onCallback(tap("rem:f:ok", 999));                              // a stale message's button is ignored
        dialog.onCallback(tap("rem:f:ok", list));

        ArgumentCaptor<ReminderDraft> draft = ArgumentCaptor.forClass(ReminderDraft.class);
        verify(reminders).create(eq(CHAT), eq(DOC), draft.capture());
        assertThat(draft.getValue().name()).isEqualTo("Холодильник Bosch");
        assertThat(draft.getValue().date()).isEqualTo(LocalDate.of(2026, 11, 15));
        assertThat(draft.getValue().time()).isEqualTo(LocalTime.of(10, 30));
        assertThat(draft.getValue().nagIntervalMinutes()).isEqualTo(60);
        verify(sender).deleteMessage(CHAT, 802); // typed values are removed from the chat
        verify(sender).edit(eq(CHAT), eq(list), contains("✅ Создано: «Холодильник Bosch»"), any());
    }

    @Test
    void cancelEndsTheForm() {
        when(reminders.user(CHAT)).thenReturn(Optional.of(USER));
        dialog.onCallback(tap("rem:new:42", 700));

        assertThat(dialog.onText(CHAT, 801, "/cancel")).isTrue();

        verify(sender).edit(CHAT, 700, "Отменено.", null);
        assertThat(dialog.onText(CHAT, 802, "Новое название")).isFalse(); // no form any more -> plain search
    }

    @Test
    void pastTimeTodayIsRejectedAndFireTimeInPastReturnsToTheDate() {
        when(reminders.user(CHAT)).thenReturn(Optional.of(USER));
        when(reminders.create(eq(CHAT), eq(DOC), any()))
                .thenThrow(new NotifierApiException(NotifierErrorCode.FIRE_TIME_IN_PAST, 422, "past"));
        dialog.onCallback(tap("rem:new:42", 700));
        dialog.onCallback(tap("rem:f:keep", 700));
        dialog.onText(CHAT, 801, "26.10.2026");                  // today
        dialog.onText(CHAT, 802, "11:00");                       // «now» is 12:00 -> already passed
        verify(sender).edit(eq(CHAT), eq(700), contains("Это время уже прошло"), any());

        dialog.onCallback(tap("rem:f:deft", 700));               // no time -> notifier's working-hours default
        dialog.onCallback(tap("rem:f:nag:0", 700));
        dialog.onCallback(tap("rem:f:ok", 700));

        verify(sender).edit(eq(CHAT), eq(700), contains("Это время уже прошло — выберите другую дату"), any()); // back to the date step
        assertThat(dialog.onText(CHAT, 803, "27.10.2026")).isTrue();       // and it takes a new date
    }

    @Test
    void disabledIntegrationOffersNothing() {
        when(reminders.isEnabled()).thenReturn(false);
        when(reminders.suggestionsEnabled()).thenReturn(false);

        assertThat(dialog.listButton(DOC)).isEmpty();
        assertThat(dialog.suggestionsAsOf()).isNull();
        dialog.openList(CHAT, DOC);
        verify(sender).send(CHAT, "Напоминания не настроены.");
        verify(sender, never()).send(eq(CHAT), anyString(), isNull());
    }

    @Test
    void reminderListShowsEventsWithDeleteButtons() {
        when(reminders.user(CHAT)).thenReturn(Optional.of(USER));
        when(reminders.list(CHAT, DOC)).thenReturn(List.of(new com.example.homedocsregistrar.reminder.NotifierEvent(
                17L, "home-docs", "ext-17", "document:42", CHAT, "Истекает гарантия",
                com.example.homedocsregistrar.reminder.NotifierEvent.Status.ACTIVE, null,
                LocalDateTime.of(2027, 3, 8, 9, 0), null, 1440, null)));

        dialog.openList(CHAT, DOC);

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<InlineKeyboardMarkup> keyboard = ArgumentCaptor.forClass(InlineKeyboardMarkup.class);
        verify(sender).send(eq(CHAT), text.capture(), keyboard.capture());
        assertThat(text.getValue()).contains("⏰ 08.03.2027 09:00 — Истекает гарантия · 🔁 каждый день");
        assertThat(callbackData(keyboard.getValue())).containsExactly("rem:del:42:ext-17", "rem:new:42", "rem:close");
    }
}
