package com.vladoose.nir.controller;

import com.vladoose.nir.dto.response.CustomerContacts;
import com.vladoose.nir.dto.response.LotResultView;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.integration.goszakup.dto.SubjectInfo;
import com.vladoose.nir.repository.TenderLotRepository;
import com.vladoose.nir.service.TenderService;
import com.vladoose.nir.service.tenderresults.GoszakupSubjects;
import com.vladoose.nir.service.tenderresults.TenderResultsQuery;
import com.vladoose.nir.service.tenderresults.TenderResultsScheduler;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Итоги тендеров (спека 2026-10-22-tender-results-market-prices §13): читают все вошедшие, проверку запускает администратор. */
@RestController
@RequestMapping("/api")
public class TenderResultsController {

    private final TenderService tenders;
    private final TenderLotRepository lots;
    private final TenderResultsQuery query;
    private final TenderResultsScheduler scheduler;
    private final GoszakupSubjects subjects;

    public TenderResultsController(TenderService tenders, TenderLotRepository lots, TenderResultsQuery query,
                                   TenderResultsScheduler scheduler, GoszakupSubjects subjects) {
        this.tenders = tenders;
        this.lots = lots;
        this.query = query;
        this.scheduler = scheduler;
        this.subjects = subjects;
    }

    /** Полный итог лота; лот чужого рынка или частной заявки — 404 (гард по тендеру, §6 CLAUDE.md). */
    @GetMapping("/lots/{id}/result")
    public LotResultView lotResult(@PathVariable Long id) {
        TenderLot lot = lots.findById(id).orElseThrow(() -> new NotFoundException("Лот не найден"));
        tenders.findPublic(lot.getTender().getId());
        return query.view(id);
    }

    @PostMapping("/tenders/{id}/results/check")
    @PreAuthorize("hasRole('ADMIN')")
    public TenderResultsScheduler.CheckResult check(@PathVariable Long id) {
        tenders.findPublic(id);
        return scheduler.check(id);
    }

    /** Контакты заказчика: справочник площадки (раз в 30 дней); площадка отказала — что есть у себя. */
    @GetMapping("/tenders/{id}/customer")
    public CustomerContacts customer(@PathVariable Long id) {
        Tender t = tenders.findPublic(id);
        if (t.getMarket() != Market.KZ) throw new BadRequestException("Только для Казахстана");
        String bin = t.getCustomerBin();
        if (bin == null || !bin.matches("\\d{12}")) return new CustomerContacts(bin, t.getCustomerName(), null, null);
        SubjectInfo s;
        try {
            s = subjects.get(bin);
        } catch (RuntimeException e) {
            s = subjects.cached(bin);
        }
        return s == null ? new CustomerContacts(bin, t.getCustomerName(), null, null)
                : new CustomerContacts(bin, s.nameRu() != null ? s.nameRu() : t.getCustomerName(), s.phone(), s.email());
    }

    @GetMapping("/tender-results/status")
    @PreAuthorize("hasRole('ADMIN')")
    public TenderResultsScheduler.Status status() {
        return scheduler.status();
    }
}
