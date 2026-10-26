package com.example.homedocsregistrar.reminder;

import java.time.LocalDate;

/**
 * A validated reminder suggestion, ready to offer to the user.
 *
 * @param name     the reminder name (≤ 255), mentioning the deadline when it fires earlier
 * @param dueDate  the deadline itself (e.g. the warranty's last day)
 * @param fireDate when to remind: the deadline minus the kind's lead time, but never before today
 */
public record ReminderProposal(ReminderKind kind, String name, LocalDate dueDate, LocalDate fireDate) {
}
