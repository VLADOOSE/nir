package com.vladoose.nir.service.mail;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Разобранные письма для тестов классификации, текста уведомления и записи — без IMAP и MIME. */
public final class TestMails {

    private TestMails() {}

    public static Builder mail() { return new Builder(); }

    public static final class Builder {
        private long uid = 1;
        private String messageId = "<t-" + System.nanoTime() + "@x.kz>";
        private String from = "Поставщик <s@x.kz>";
        private String subject = "Тема";
        private OffsetDateTime receivedAt = OffsetDateTime.parse("2026-10-05T09:00:00Z");
        private String contentType = "text/plain; charset=utf-8";
        private Map<String, String> autoHeaders = Map.of();
        private String text = "Текст письма";
        private String html = "";
        private List<String> attachments = List.of();
        private byte[] excel;
        private String excelName;
        private ParsedMail.Bounce bounce;

        public Builder uid(long v) { uid = v; return this; }
        public Builder messageId(String v) { messageId = v; return this; }
        public Builder from(String v) { from = v; return this; }
        public Builder subject(String v) { subject = v; return this; }
        public Builder receivedAt(OffsetDateTime v) { receivedAt = v; return this; }
        public Builder contentType(String v) { contentType = v; return this; }
        public Builder header(String name, String value) {
            Map<String, String> m = new HashMap<>(autoHeaders);
            m.put(name.toLowerCase(Locale.ROOT), value.toLowerCase(Locale.ROOT));
            autoHeaders = Map.copyOf(m);
            return this;
        }
        public Builder text(String v) { text = v; return this; }
        public Builder html(String v) { html = v; text = ""; return this; }
        public Builder attachments(String... v) { attachments = List.of(v); return this; }
        public Builder excel(String name) { excel = new byte[]{1, 2, 3}; excelName = name; attachments = List.of(name); return this; }
        public Builder bounce(String recipient, String status, String diagnostic, String originalSubject) {
            bounce = new ParsedMail.Bounce(recipient, status, diagnostic, originalSubject);
            return this;
        }

        public ParsedMail build() {
            return new ParsedMail(uid, messageId, from, MailParser.addressPart(from), subject, receivedAt, contentType,
                    autoHeaders, text, html, attachments, excel, excelName, bounce);
        }
    }
}
