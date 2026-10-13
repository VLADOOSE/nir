package com.vladoose.nir.entity;

import lombok.*;

import java.io.Serializable;

/** Ключ курсора почты (V29): ящик + папка. */
@Getter @NoArgsConstructor @AllArgsConstructor @EqualsAndHashCode @ToString
public class MailCursorKey implements Serializable {

    private String mailbox;
    private String folder;

    /** «Входящие» ящика — у zakup@ единственная читаемая папка. */
    public static MailCursorKey inbox(String mailbox) {
        return new MailCursorKey(mailbox, MailCursor.INBOX);
    }
}
