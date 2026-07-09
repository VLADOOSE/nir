package com.vladoose.nir.service;

import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/** Отправка письма КП: MimeMessage (UTF-8) с Reply-To на ящик ответов (по умолчанию — адрес отправки). */
@Service
public class EmailService {

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
        try {
            MimeMessage mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, false, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(body, false); // plain-text
            String rt = (replyTo != null && !replyTo.isBlank()) ? replyTo : fromAddress;
            if (rt != null && !rt.isBlank()) helper.setReplyTo(rt);
            mailSender.send(mime);
        } catch (jakarta.mail.MessagingException e) {
            throw new RuntimeException("Не удалось собрать письмо: " + e.getMessage(), e);
        }
    }
}
