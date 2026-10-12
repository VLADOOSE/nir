package com.vladoose.nir.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.lang.annotation.*;

/**
 * Общий контекст тестов отправки писем через GreenMail SMTP 127.0.0.1:3025: ОДИН набор свойств на все такие классы —
 * каждый особый набор поднимает свой контекст и ещё 10 соединений к nirdb (CLAUDE.md §14). Класс сам регистрирует
 * GreenMailExtension(ServerSetupTest.SMTP) с пользователем zakup-test@westmed.local / secret.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest
@Transactional
@TestPropertySource(properties = {
        "spring.mail.host=127.0.0.1",
        "spring.mail.port=3025",
        "spring.mail.username=zakup-test@westmed.local",
        "spring.mail.password=secret"
})
public @interface SmtpIntegrationTest {
}
