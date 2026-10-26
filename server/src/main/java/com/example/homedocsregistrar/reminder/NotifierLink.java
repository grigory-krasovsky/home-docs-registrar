package com.example.homedocsregistrar.reminder;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** A link notifier shows as a button on the reminder (label ≤ 64, url ≤ 512 and https only). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NotifierLink(String label, String url) {
}
