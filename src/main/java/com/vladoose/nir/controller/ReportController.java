package com.vladoose.nir.controller;

import com.vladoose.nir.service.JasperReportService;
import com.vladoose.nir.service.ReportService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportService service;
    private final JasperReportService jasperReportService;

    public ReportController(ReportService service, JasperReportService jasperReportService) {
        this.service = service;
        this.jasperReportService = jasperReportService;
    }

    @GetMapping("/tender-stats")
    public Map<String, Long> tenderStats() {
        return service.getTenderStatsByStatus();
    }

    @GetMapping("/equipment-demand")
    public Map<String, Long> equipmentDemand() {
        return service.getEquipmentDemand();
    }

    @GetMapping("/distributor-stats")
    public List<Map<String, Object>> distributorStats() {
        return service.getDistributorStats();
    }

    @GetMapping("/distributor-pr-stats")
    public List<Map<String, Object>> distributorPrStats() {
        return service.getDistributorPriceRequestStats();
    }

    @GetMapping(value = "/tender-pdf", produces = "application/pdf")
    public ResponseEntity<byte[]> tenderPdf(@RequestParam(required = false) String status) {
        try {
            byte[] pdf = jasperReportService.generateTenderReport(status);
            return ResponseEntity.ok()
                    .header("Content-Disposition", "attachment; filename=tender_report.pdf")
                    .header("Content-Type", "application/pdf")
                    .body(pdf);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}
