package com.vladoose.nir.controller;

import com.vladoose.nir.service.EmailInboxService;
import com.vladoose.nir.service.EmailService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/email")
public class EmailController {

    private final EmailService emailService;
    private final EmailInboxService emailInboxService;

    public EmailController(EmailService emailService, EmailInboxService emailInboxService) {
        this.emailService = emailService;
        this.emailInboxService = emailInboxService;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("configured", emailService.isConfigured());
    }

    @GetMapping("/inbox")
    public List<Map<String, String>> inbox(@RequestParam(defaultValue = "20") int count) {
        return emailInboxService.getInbox(count);
    }

    @PostMapping("/send")
    public Map<String, String> send(@RequestBody Map<String, String> request) {
        String to = request.get("to");
        String subject = request.get("subject");
        String body = request.get("body");
        try {
            emailService.sendEmail(to, subject, body);
            return Map.of("status", "OK", "message", "Письмо отправлено");
        } catch (Exception e) {
            return Map.of("status", "ERROR", "message", e.getMessage() == null ? "Неизвестная ошибка" : e.getMessage());
        }
    }
}
