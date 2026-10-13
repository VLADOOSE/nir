package com.vladoose.nir.service.mail;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Выбор папки «Отправленные» по списку папок ящика — чистая функция (тестируется без IMAP). */
public final class SentFolders {

    /** Папка из LIST: полное имя (по нему открывают), имя (последний сегмент) и признаки (\Sent, \HasNoChildren…). */
    public record Candidate(String fullName, String name, List<String> attributes) {}

    /** Имена «Отправленных» у почтовых служб без признака \Sent. */
    static final List<String> NAMES = List.of("отправленные", "sent", "sent items", "sent messages", "sent mail");

    private SentFolders() {}

    /** Признак \Sent (RFC 6154) — первым: у Mail.ru он есть, а имя закодировано; без признака — по имени. */
    public static Optional<String> pick(List<Candidate> folders) {
        for (Candidate c : folders) {
            if (c.attributes().stream().anyMatch(a -> a.equalsIgnoreCase("\\Sent"))) return Optional.of(c.fullName());
        }
        for (Candidate c : folders) {
            String n = c.name() == null ? "" : c.name().strip().toLowerCase(Locale.ROOT);
            if (NAMES.contains(n)) return Optional.of(c.fullName());
        }
        return Optional.empty();
    }
}
