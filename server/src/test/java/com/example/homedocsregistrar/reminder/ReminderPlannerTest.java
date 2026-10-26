package com.example.homedocsregistrar.reminder;

import com.example.homedocsregistrar.extraction.ReminderHint;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Lead time + validation of the model's suggested deadlines, and parsing of the reminder form's input. */
class ReminderPlannerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 26);

    private static List<ReminderProposal> plan(ReminderHint... hints) {
        return ReminderPlanner.plan(List.of(hints), "Холодильник Bosch", LocalDate.of(2025, 3, 22), TODAY);
    }

    @Test
    void appliesTheKindsLeadTime() {
        List<ReminderProposal> proposals = plan(
                new ReminderHint("WARRANTY", "Истекает гарантия: холодильник Bosch", "2027-03-22"),
                new ReminderHint("CONTRACT_END", "Заканчивается договор", "2027-06-30"),
                new ReminderHint("PAYMENT", "Оплатить счёт", "2026-11-10"));

        assertThat(proposals).extracting(ReminderProposal::kind)
                .containsExactly(ReminderKind.PAYMENT, ReminderKind.WARRANTY, ReminderKind.CONTRACT_END); // soonest first
        assertThat(proposals.get(0).fireDate()).isEqualTo(LocalDate.of(2026, 11, 7));  // 3 days before
        assertThat(proposals.get(1).fireDate()).isEqualTo(LocalDate.of(2027, 3, 8));   // 14 days before
        assertThat(proposals.get(2).fireDate()).isEqualTo(LocalDate.of(2027, 5, 31));  // 30 days before
        assertThat(proposals.get(1).dueDate()).isEqualTo(LocalDate.of(2027, 3, 22));
    }

    @Test
    void leadNeverGoesBeforeToday() {
        // Warranty ends in 5 days; 14 days' lead would be in the past -> remind today.
        assertThat(ReminderPlanner.fireDate(ReminderKind.WARRANTY, TODAY.plusDays(5), TODAY)).isEqualTo(TODAY);
        assertThat(ReminderPlanner.fireDate(ReminderKind.WARRANTY, TODAY, TODAY)).isEqualTo(TODAY);
    }

    @Test
    void dropsPastFarAndPreDocumentDeadlines() {
        List<ReminderProposal> proposals = plan(
                new ReminderHint("WARRANTY", "Прошедшая", "2026-10-25"),         // yesterday
                new ReminderHint("EXPIRY", "Слишком далеко", "2036-10-27"),      // beyond today + 10 years
                new ReminderHint("SERVICE", "Сегодня — ещё можно", "2026-10-26"),
                new ReminderHint("EXPIRY", "Ровно 10 лет", "2036-10-26"));

        assertThat(proposals).extracting(ReminderProposal::name)
                .containsExactly("Сегодня — ещё можно", "Ровно 10 лет (срок 26.10.2036)");

        // A future deadline that precedes the document's own date is a misreading.
        LocalDate docDate = LocalDate.of(2027, 1, 1);
        assertThat(ReminderPlanner.plan(List.of(new ReminderHint("SERVICE", "ТО", "2026-12-01")), null, docDate, TODAY))
                .isEmpty();
    }

    @Test
    void acceptsOnlyAbsoluteIsoDates() {
        assertThat(plan(
                new ReminderHint("WARRANTY", "a", "через год"),
                new ReminderHint("WARRANTY", "b", "22.03.2027"),
                new ReminderHint("WARRANTY", "c", "2027-02-30"),
                new ReminderHint("WARRANTY", "d", null),
                new ReminderHint("WARRANTY", "e", " 2027-03-22 "))).extracting(ReminderProposal::name)
                .containsExactly("e (срок 22.03.2027)");
    }

    @Test
    void dedupesAndCapsAtThree() {
        List<ReminderHint> hints = new ArrayList<>();
        hints.add(new ReminderHint("WARRANTY", "Гарантия", "2027-03-22"));
        hints.add(new ReminderHint("warranty", "Гарантия ещё раз", "2027-03-22")); // same kind + date
        hints.add(new ReminderHint("SERVICE", "ТО 1", "2027-01-10"));
        hints.add(new ReminderHint("SERVICE", "ТО 2", "2027-07-10"));
        hints.add(new ReminderHint("SERVICE", "ТО 3", "2028-01-10"));
        hints.add(null);

        List<ReminderProposal> proposals = ReminderPlanner.plan(hints, null, null, TODAY);

        assertThat(proposals).hasSize(ReminderPlanner.MAX_SUGGESTIONS);
        assertThat(proposals).extracting(ReminderProposal::dueDate).containsExactly(
                LocalDate.of(2027, 1, 10), LocalDate.of(2027, 3, 22), LocalDate.of(2027, 7, 10));
        assertThat(proposals.get(1).name()).startsWith("Гарантия (срок"); // the first of the duplicates wins
    }

    @Test
    void unknownKindIsOtherAndBlankTitleGetsADefault() {
        List<ReminderProposal> proposals = plan(new ReminderHint("SOMETHING_NEW", "  ", "2027-01-31"));

        assertThat(proposals).singleElement().satisfies(p -> {
            assertThat(p.kind()).isEqualTo(ReminderKind.OTHER);
            assertThat(p.name()).isEqualTo("Срок по документу: Холодильник Bosch (срок 31.01.2027)");
            assertThat(p.fireDate()).isEqualTo(LocalDate.of(2027, 1, 24));
        });
        assertThat(ReminderKind.of("insurance-end")).isEqualTo(ReminderKind.INSURANCE_END);
        assertThat(ReminderKind.of(null)).isEqualTo(ReminderKind.OTHER);
    }

    @Test
    void nameIsTruncatedToNotifiersLimitKeepingTheDeadline() {
        String longTitle = "Гарантия ".repeat(60);

        ReminderProposal proposal = plan(new ReminderHint("WARRANTY", longTitle, "2027-03-22")).get(0);

        assertThat(proposal.name()).hasSize(ReminderPlanner.MAX_NAME_LENGTH).endsWith("… (срок 22.03.2027)");
        assertThat(ReminderPlanner.formatName("  Чек   из  магазина ")).isEqualTo("Чек из магазина");
    }

    @Test
    void emptyWithoutHints() {
        assertThat(ReminderPlanner.plan(null, null, null, TODAY)).isEmpty();
        assertThat(ReminderPlanner.plan(List.of(), null, null, TODAY)).isEmpty();
    }

    @Test
    void parsesTypedDates() {
        assertThat(ReminderPlanner.parseUserDate("08.03.2027")).contains(LocalDate.of(2027, 3, 8));
        assertThat(ReminderPlanner.parseUserDate(" 8.3.2027 ")).contains(LocalDate.of(2027, 3, 8));
        assertThat(ReminderPlanner.parseUserDate("08-03-2027")).contains(LocalDate.of(2027, 3, 8));
        assertThat(ReminderPlanner.parseUserDate("31.02.2027")).isEmpty();
        assertThat(ReminderPlanner.parseUserDate("2027-03-08")).isEmpty();
        assertThat(ReminderPlanner.parseUserDate("завтра")).isEmpty();
        assertThat(ReminderPlanner.parseUserDate(null)).isEmpty();
    }

    @Test
    void parsesTypedTimes() {
        assertThat(ReminderPlanner.parseUserTime("10:30")).contains(LocalTime.of(10, 30));
        assertThat(ReminderPlanner.parseUserTime("9:05")).contains(LocalTime.of(9, 5));
        assertThat(ReminderPlanner.parseUserTime("09.30")).contains(LocalTime.of(9, 30));
        assertThat(ReminderPlanner.parseUserTime("25:00")).isEmpty();
        assertThat(ReminderPlanner.parseUserTime("10:7")).isEmpty();
        assertThat(ReminderPlanner.parseUserTime("утром")).isEmpty();
    }

    @Test
    void validatesTypedDateAndTimeAgainstNow() {
        assertThat(ReminderPlanner.dateProblem(TODAY.minusDays(1), TODAY)).isEqualTo("Эта дата уже прошла.");
        assertThat(ReminderPlanner.dateProblem(TODAY, TODAY)).isNull();
        assertThat(ReminderPlanner.dateProblem(TODAY.plusYears(11), TODAY)).startsWith("Слишком далеко");

        LocalDateTime now = TODAY.atTime(10, 0, 30);
        assertThat(ReminderPlanner.isPast(TODAY, LocalTime.of(10, 0), now)).isTrue();   // this minute = passed
        assertThat(ReminderPlanner.isPast(TODAY, LocalTime.of(9, 59), now)).isTrue();
        assertThat(ReminderPlanner.isPast(TODAY, LocalTime.of(10, 1), now)).isFalse();
        assertThat(ReminderPlanner.isPast(TODAY.plusDays(1), LocalTime.of(8, 0), now)).isFalse();
    }
}
