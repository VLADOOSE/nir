package com.vladoose.nir.controller;

import com.vladoose.nir.dto.ReportRequest;
import com.vladoose.nir.service.MailService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reports")
@CrossOrigin(origins = "http://localhost:5173")
public class ReportsController {
    private final MailService mailService;
    public ReportsController(MailService mailService) { this.mailService = mailService; }

    @PostMapping("/send")
    public ResponseEntity<Void> sendReport(@RequestBody ReportRequest req) {
        mailService.sendReportToEmail(req);
        return ResponseEntity.ok().build();
    }
}
