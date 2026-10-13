package com.vladoose.nir.service.mail;

import com.vladoose.nir.entity.MailCursor;

/** Папка ящика, которую читает приём: роль, а не имя на сервере (имя у почты своё и бывает локализованным). */
public enum MailFolder {
    INBOX(MailCursor.INBOX, "Входящие"),
    SENT(MailCursor.SENT, "Отправленные");

    private final String cursorKey;
    private final String label;

    MailFolder(String cursorKey, String label) {
        this.cursorKey = cursorKey;
        this.label = label;
    }

    /** Ключ папки в курсоре (mail_cursor.folder). */
    public String cursorKey() { return cursorKey; }

    /** Подпись для людей и лога. */
    public String label() { return label; }
}
