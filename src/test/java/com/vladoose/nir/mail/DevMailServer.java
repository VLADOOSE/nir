package com.vladoose.nir.mail;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;

/**
 * НЕ тест: локальный почтовый сервер для живой проверки приёма (`./gradlew devMail`). SMTP 127.0.0.1:3025,
 * IMAP 127.0.0.1:3143, ящик zakup@westmed.kz / secret. Письма — `python3 scripts/dev-mail.py …`.
 */
public final class DevMailServer {

    public static void main(String[] args) throws Exception {
        GreenMail mail = new GreenMail(ServerSetupTest.SMTP_IMAP);
        mail.start();
        mail.setUser("zakup@westmed.kz", "zakup@westmed.kz", "secret");
        System.out.println("GreenMail: SMTP 127.0.0.1:3025, IMAP 127.0.0.1:3143, zakup@westmed.kz / secret");
        Thread.currentThread().join();
    }
}
