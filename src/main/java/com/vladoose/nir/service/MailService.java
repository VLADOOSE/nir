package com.vladoose.nir.service;

import com.vladoose.nir.dto.ReportRequest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.stereotype.Service;

@Service
public class MailService {
    private final JavaMailSender mailSender;
    public MailService(JavaMailSender mailSender){ this.mailSender = mailSender; }

    public void sendReportToEmail(ReportRequest req) {
        if (req.getToEmail() == null || req.getToEmail().isBlank()) return;
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setTo(req.getToEmail());
        msg.setSubject(req.getTitle() == null ? "Report" : req.getTitle());
        StringBuilder sb = new StringBuilder();
        sb.append("Организация: ").append(req.getOrganization()).append("\n\n");
        sb.append(req.getText());
        msg.setText(sb.toString());
        mailSender.send(msg);
    }
}
