package com.vladoose.nir.mail;

import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;

/**
 * НЕ тест: локальный почтовый сервер для живой проверки приёма (`./gradlew devMail`). SMTP 127.0.0.1:3025,
 * IMAP 127.0.0.1:3143, ящики zakup@westmed.kz и info@westmed.kz (у обоих — папка «Sent»), пароль secret.
 * Письма — `python3 scripts/dev-mail.py …`.
 */
public final class DevMailServer {

    public static void main(String[] args) throws Exception {
        GreenMail mail = new GreenMail(ServerSetupTest.SMTP_IMAP);
        mail.start();
        GreenMailUser zakup = mail.setUser("zakup@westmed.kz", "zakup@westmed.kz", "secret");
        mail.getManagers().getImapHostManager().createMailbox(zakup, "Sent");  // копии писем поставщикам (SentCopy)
        GreenMailUser info = mail.setUser("info@westmed.kz", "info@westmed.kz", "secret");
        mail.getManagers().getImapHostManager().createMailbox(info, "Sent");   // «Отправленные» ящика писем клиентов
        System.out.println("GreenMail: SMTP 127.0.0.1:3025, IMAP 127.0.0.1:3143, zakup@westmed.kz и info@westmed.kz / secret");
        Thread.currentThread().join();
    }
}
