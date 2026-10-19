package com.vladoose.nir.service.mail;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * SMTP ящика писем клиентам (спека 2026-10-19-client-mail-reply §3.1, §3.5): свой отправитель — почта поставщикам
 * (spring.mail.*, zakup@) не меняется. Таймауты — 10 с. Тексты сбоев — свои: адреса и тексты исключений почты наружу не идут.
 */
@Component
public class ClientMailSender {

    static final int TIMEOUT_MS = 10_000;
    public static final String AUTH = "Письмо не ушло: почта отклонила вход — проверьте пароль info@";
    public static final String DOWN = "Письмо не ушло: почтовый сервер не отвечает — попробуйте ещё раз";
    public static final String ADDRESS = "Письмо не ушло: почта не приняла адрес";

    private final JavaMailSenderImpl sender = new JavaMailSenderImpl();

    public ClientMailSender(ClientSmtpSettings s) {
        sender.setHost(s.host());
        sender.setPort(s.port());
        sender.setUsername(s.username());
        sender.setPassword(s.password());
        sender.setDefaultEncoding("UTF-8");
        Properties p = sender.getJavaMailProperties();
        p.put("mail.smtp.auth", "true");
        p.put("mail.smtp.ssl.enable", String.valueOf(s.ssl()));
        p.put("mail.smtp.connectiontimeout", String.valueOf(TIMEOUT_MS));
        p.put("mail.smtp.timeout", String.valueOf(TIMEOUT_MS));
        p.put("mail.smtp.writetimeout", String.valueOf(TIMEOUT_MS));
    }

    /** Сессия для сборки письма (ClientLetterBuilder.build) — с настройками этого SMTP. */
    public Session session() {
        return sender.getSession();
    }

    public void send(MimeMessage m) {
        sender.send(m);
    }

    /** Свой текст сбоя: вход, соединение, адрес; остальное — классом исключения (§3.5). */
    public static String failureText(Throwable e) {
        for (Throwable c : causes(e)) {
            if (c instanceof MailAuthenticationException || c instanceof AuthenticationFailedException) return AUTH;
            if (c instanceof SendFailedException sf && sf.getInvalidAddresses() != null
                    && sf.getInvalidAddresses().length > 0) return ADDRESS;
            if (c instanceof ConnectException || c instanceof SocketTimeoutException || c instanceof UnknownHostException) {
                return DOWN;
            }
        }
        return "Письмо не ушло (" + e.getClass().getSimpleName() + ")";
    }

    /** Причины по цепочке, следующие исключения JavaMail и исключения по письмам MailSendException (они не в getCause). */
    private static List<Throwable> causes(Throwable e) {
        List<Throwable> out = new ArrayList<>();
        List<Throwable> queue = new ArrayList<>();
        queue.add(e);
        while (!queue.isEmpty() && out.size() < 50) {
            Throwable t = queue.remove(0);
            if (t == null || out.contains(t)) continue;
            out.add(t);
            queue.add(t.getCause());
            if (t instanceof MessagingException me) queue.add(me.getNextException());
            if (t instanceof MailSendException ms) queue.addAll(List.of(ms.getMessageExceptions()));
        }
        return out;
    }
}
