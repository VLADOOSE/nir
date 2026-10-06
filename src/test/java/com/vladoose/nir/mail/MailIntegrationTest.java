package com.vladoose.nir.mail;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.lang.annotation.*;

/**
 * Общий контекст почтовых интеграционных тестов: ОДИН набор свойств на все классы (каждый особый набор — ещё 10
 * соединений к nirdb, CLAUDE.md §14). IMAP — GreenMail :3143; планировщик не тикает (начальная задержка — сутки),
 * иначе его проход коммитил бы письма GreenMail в nirdb мимо отката теста; Telegram — на заглушку 127.0.0.1:7798
 * (тест, которому она нужна, поднимает её сам; остальным отправка честно не удаётся — это пишется в их откатываемую строку).
 * После такого сбоя бин MailTelegramNotifier держит паузу (нарастающую, до 30 мин) на весь контекст — тест, которому
 * нужна настоящая отправка, берёт свой экземпляр отправителя (см. telegramEndToEnd_sendsToThread_marksSent).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest
@Transactional
@TestPropertySource(properties = {
        "mail.imap.enabled=true",
        "mail.imap.host=127.0.0.1",
        "mail.imap.port=3143",
        "mail.imap.username=zakup@westmed.kz",
        "mail.imap.password=secret",
        "mail.imap.protocol=imap",
        "mail.imap.market=KZ",
        "mail.imap.initial-delay-ms=86400000",
        "spring.mail.username=zakup@westmed.kz",
        "notify.telegram.enabled=true",
        "notify.telegram.api-url=http://127.0.0.1:7798",
        "notify.telegram.bot-token=123456:TEST-TOKEN-SECRET",
        "notify.telegram.chat-id=-1001",
        "notify.telegram.mail-thread-id=77",
        "ais.public-url=https://ais.example"
})
public @interface MailIntegrationTest {
}
