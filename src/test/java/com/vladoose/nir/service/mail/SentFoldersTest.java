package com.vladoose.nir.service.mail;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SentFoldersTest {

    static SentFolders.Candidate f(String full, String name, String... attrs) {
        return new SentFolders.Candidate(full, name, List.of(attrs));
    }

    /** Mail.ru: «Отправленные» под закодированным именем, но с признаком \\Sent (RFC 6154) — признак важнее имени. */
    @Test
    void sentAttributeWinsOverName() {
        assertThat(SentFolders.pick(List.of(f("INBOX", "INBOX"), f("Sent", "Sent"),
                f("Отправленные", "Отправленные", "\\HasNoChildren", "\\Sent")))).contains("Отправленные");
    }

    @Test
    void byNameWhenNoAttribute_caseInsensitive() {
        assertThat(SentFolders.pick(List.of(f("INBOX", "INBOX"), f("ОТПРАВЛЕННЫЕ", "ОТПРАВЛЕННЫЕ")))).contains("ОТПРАВЛЕННЫЕ");
        assertThat(SentFolders.pick(List.of(f("[Gmail]/Sent Mail", "Sent Mail")))).contains("[Gmail]/Sent Mail");
        assertThat(SentFolders.pick(List.of(f("Sent Items", "Sent Items")))).contains("Sent Items");
    }

    @Test
    void noneFound() {
        assertThat(SentFolders.pick(List.of(f("INBOX", "INBOX"), f("Drafts", "Drafts", "\\Drafts")))).isEmpty();
    }
}
