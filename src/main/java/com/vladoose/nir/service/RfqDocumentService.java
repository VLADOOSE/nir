package com.vladoose.nir.service;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.EmailTemplateRepository;
import com.vladoose.nir.service.document.KpDocxRenderer;
import com.vladoose.nir.service.document.RfqDocumentBuilder;
import com.vladoose.nir.util.DocFormat;
import com.vladoose.nir.util.LotDescriptiveText;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * Запрос цен на бланке (спека 2026-10-12 §6.4) из запроса — сохранённого («Исх. № <id>», дата создания запроса) или
 * черновика без id («№ —», сегодня). Позиции — по тем же правилам, что строки письма: модель с РУ или лот с брендом,
 * подтверждённым РУ и санитизированным ТЗ той же длины. Печать — по отметке запроса, если она загружена.
 */
@Service
public class RfqDocumentService {

    public static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    public record RfqFile(String fileName, byte[] bytes) {}

    private final CompanyProfileService profiles;
    private final EmailTemplateRepository templates;
    private final RfqDocumentBuilder builder;
    private final KpDocxRenderer renderer;
    private final String replyTo;

    public RfqDocumentService(CompanyProfileService profiles, EmailTemplateRepository templates, RfqDocumentBuilder builder,
                              KpDocxRenderer renderer, @Value("${mail.kp.reply-to:}") String replyTo,
                              @Value("${spring.mail.username:}") String from) {
        this.profiles = profiles;
        this.templates = templates;
        this.builder = builder;
        this.renderer = renderer;
        this.replyTo = replyTo != null && !replyTo.isBlank() ? replyTo.trim() : (from == null ? "" : from.trim());
    }

    public RfqFile render(PriceRequest pr) {
        Market market = pr.getMarket() != null ? pr.getMarket() : Market.RF;
        CompanyProfile profile = profiles.forMarket(market);
        RfqTexts texts = RfqTexts.of(templates.findByMarket(market).orElse(null), market);
        LocalDate date = pr.getCreatedAt() != null
                ? pr.getCreatedAt().atZoneSameInstant(market.zone()).toLocalDate()
                : LocalDate.now(market.zone());
        String client = pr.isDiscloseClient()
                ? RfqClientLine.render(texts.clientLine(), pr.getTender(), profile.getShortName())
                : null;
        Distributor d = pr.getDistributor();
        List<RfqDocumentBuilder.Line> lines = pr.getItems().stream().map(RfqDocumentService::line).toList();
        byte[] bytes = renderer.render(builder.build(new RfqDocumentBuilder.Input(profile, pr.getId(), date, d.getName(),
                contact(d), texts, client, lines, replyTo, pr.getReplyBy(), pr.isWithStamp())));
        return new RfqFile(fileName(pr.getId(), date, profile), bytes);
    }

    /** Строка документа — по правилам строки письма (KpEmailComposer.buildPositions). */
    static RfqDocumentBuilder.Line line(PriceRequestItem it) {
        TenderLot lot = it.getTenderLot();
        String qty = it.getRequestedQuantity() == null ? "уточняется" : String.valueOf(it.getRequestedQuantity());
        String unit = blank(lot.getUnit()) ? "шт" : lot.getUnit().trim();
        MedEquipment eq = it.getMedEquipment();
        if (eq != null) {
            String reg = eq.getRegistration() == null ? null : "РУ № " + eq.getRegistration().getRegNumber();
            return new RfqDocumentBuilder.Line(eq.getName() + (blank(eq.getManufact()) ? "" : " (" + eq.getManufact().trim() + ")"),
                    null, qty, unit, reg);
        }
        String name = lot.getEquipName() + (blank(lot.getManufact()) ? "" : " (" + lot.getManufact().trim() + ")");
        String reg = lot.getRegStatus() == LineRegStatus.CONFIRMED && lot.getRegNumber() != null
                ? "РУ № " + lot.getRegNumber() : null;
        String spec = LotDescriptiveText.requirementsForEmail(lot.getRequiredSpec());
        return new RfqDocumentBuilder.Line(name, blank(spec) ? null : KpEmailComposer.trimSpec(spec), qty, unit, reg);
    }

    /** «Запрос КП № 534 от 12.10.2026 — West-Med.docx»: компания — надпись бренда без кавычек, иначе краткое название. */
    static String fileName(Long id, LocalDate date, CompanyProfile p) {
        String company = !blank(p.getBrandText()) ? p.getBrandText() : p.getShortName();
        company = company == null ? "" : company.replaceAll("[\"«»]", "").replaceAll("\\s+", " ").trim();
        return "Запрос КП № " + (id == null ? "—" : id) + " от " + DocFormat.date(date)
                + (company.isEmpty() ? "" : " — " + company) + ".docx";
    }

    private static String contact(Distributor d) {
        String s = String.join(" ", nz(d.getLastName()), nz(d.getFirstName()), nz(d.getMiddleName())).replaceAll("\\s+", " ").trim();
        return s.isEmpty() ? null : s;
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
