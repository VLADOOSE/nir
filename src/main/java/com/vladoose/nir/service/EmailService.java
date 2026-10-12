package com.vladoose.nir.service;

import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Отправка письма КП: MimeMessage (UTF-8) с Reply-To на ящик ответов (по умолчанию — адрес отправки). Один или
 * несколько адресатов («Кому»), по отметке — вложения (запрос на бланке, спека 2026-10-12 §6.4).
 */
@Service
public class EmailService {

    /** Вложение письма: имя файла (кириллица кодируется), содержимое, тип. */
    public record Attachment(String fileName, byte[] bytes, String contentType) {}

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username}")
    private String fromAddress;

    @Value("${mail.kp.reply-to:}")
    private String replyTo;

    public EmailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    public boolean isConfigured() {
        return fromAddress != null && !fromAddress.isBlank();
    }

    public void sendEmail(String to, String subject, String body) {
        sendEmail(List.of(to), subject, body, List.of());
    }

    /**
     * Письмо на все адреса (в «Кому»), текст — plain UTF-8, вложения — multipart. Имена вложений кодируются
     * (setEncodeFilenames): в Spring 6.2 по умолчанию выключено, и «Запрос КП № 534 от 12.10.2026 — West-Med.docx» в части
     * почтовых программ пришёл бы нечитаемым.
     */
    public void sendEmail(List<String> to, String subject, String body, List<Attachment> attachments) {
        boolean multipart = attachments != null && !attachments.isEmpty();
        try {
            MimeMessage mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, multipart, "UTF-8");
            helper.setEncodeFilenames(true);
            helper.setFrom(fromAddress);
            helper.setTo(to.toArray(String[]::new));
            helper.setSubject(subject);
            helper.setText(body, false); // plain-text
            String rt = (replyTo != null && !replyTo.isBlank()) ? replyTo : fromAddress;
            if (rt != null && !rt.isBlank()) helper.setReplyTo(rt);
            if (multipart) {
                for (Attachment a : attachments) {
                    helper.addAttachment(a.fileName(), new ByteArrayResource(a.bytes()), a.contentType());
                }
            }
            mailSender.send(mime);
        } catch (jakarta.mail.MessagingException e) {
            // текст исключения JavaMail может нести адрес — наружу только класс (спека §10)
            throw new RuntimeException("Не удалось собрать письмо: " + e.getClass().getSimpleName(), e);
        }
    }
}
