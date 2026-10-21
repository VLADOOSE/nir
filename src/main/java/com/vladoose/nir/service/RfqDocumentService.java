package com.vladoose.nir.service;

import com.vladoose.nir.dto.response.PreviewPagesResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.repository.EmailTemplateRepository;
import com.vladoose.nir.service.document.KpDocument;
import com.vladoose.nir.service.document.KpDocxRenderer;
import com.vladoose.nir.service.document.KpHtmlRenderer;
import com.vladoose.nir.service.document.KpPdfRenderer;
import com.vladoose.nir.service.document.KpPreviewRenderer;
import com.vladoose.nir.service.document.RfqDocumentBuilder;
import com.vladoose.nir.util.DocFormat;
import com.vladoose.nir.util.LotDescriptiveText;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Base64;
import java.util.List;

/**
 * Запрос цен на бланке (спека 2026-10-12 §6.4) из запроса — сохранённого («Исх. № <id>», дата создания запроса) или
 * черновика без id («№ —», сегодня). Позиции — по тем же правилам, что строки письма: модель с РУ или лот с брендом,
 * подтверждённым РУ и санитизированным ТЗ той же длины. Печать — по отметке запроса, если она загружена. Файл — Word
 * (вложение письма) или PDF (переслать в WhatsApp): один и тот же документ, разные рендереры, как у КП клиенту.
 */
@Service
public class RfqDocumentService {

    public static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    // для enum ниже: в его теле имена DOCX и PDF — его собственные константы
    private static final String DOCX_TYPE = DOCX;
    private static final String PDF_TYPE = "application/pdf";

    /** Формат файла: «docx» / «pdf» из запроса; пусто — Word (как было до PDF). */
    public enum Format {
        DOCX(DOCX_TYPE, "docx"), PDF(PDF_TYPE, "pdf");

        final String contentType;
        final String extension;

        Format(String contentType, String extension) {
            this.contentType = contentType;
            this.extension = extension;
        }

        public static Format parse(String s) {
            if (s == null || s.isBlank() || s.trim().equalsIgnoreCase("docx")) return DOCX;
            if (s.trim().equalsIgnoreCase("pdf")) return PDF;
            throw new BadRequestException("Формат документа — pdf или docx");
        }
    }

    public record RfqFile(String fileName, byte[] bytes, String contentType) {}

    /** Страниц в предпросмотре — как у КП клиенту; запрос длиннее 20 страниц оператор дочитает в скачанном PDF. */
    public static final int PREVIEW_MAX_PAGES = 20;

    private final CompanyProfileService profiles;
    private final EmailTemplateRepository templates;
    private final RfqDocumentBuilder builder;
    private final KpDocxRenderer renderer;
    private final KpHtmlRenderer html;
    private final KpPdfRenderer pdf;
    private final KpPreviewRenderer previews;
    private final String replyTo;

    public RfqDocumentService(CompanyProfileService profiles, EmailTemplateRepository templates, RfqDocumentBuilder builder,
                              KpDocxRenderer renderer, KpHtmlRenderer html, KpPdfRenderer pdf, KpPreviewRenderer previews,
                              @Value("${mail.kp.reply-to:}") String replyTo, @Value("${spring.mail.username:}") String from) {
        this.profiles = profiles;
        this.templates = templates;
        this.builder = builder;
        this.renderer = renderer;
        this.html = html;
        this.pdf = pdf;
        this.previews = previews;
        this.replyTo = replyTo != null && !replyTo.isBlank() ? replyTo.trim() : (from == null ? "" : from.trim());
    }

    /** Word — вложение письма. */
    public RfqFile render(PriceRequest pr) {
        return render(pr, Format.DOCX);
    }

    public RfqFile render(PriceRequest pr, Format format) {
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
        KpDocument doc = builder.build(new RfqDocumentBuilder.Input(profile, pr.getId(), date, d.getName(), contact(d), texts,
                client, lines, replyTo, pr.getReplyBy(), pr.isWithStamp()));
        byte[] bytes = format == Format.PDF ? pdf.render(html.render(doc)) : renderer.render(doc);
        return new RfqFile(fileName(pr.getId(), date, profile, format), bytes, format.contentType);
    }

    /**
     * Страницы того же PDF картинками — предпросмотр в окне «Запросить цены»: оператор видит ровно то, что скачается
     * или уйдёт во вложении (у Word та же модель документа). Подсказки «таблица тесная» у запроса нет — ориентацию
     * в нём не выбирают.
     */
    public PreviewPagesResponse preview(PriceRequest pr) {
        List<String> pages = previews.pages(render(pr, Format.PDF).bytes(), PREVIEW_MAX_PAGES).stream()
                .map(b -> Base64.getEncoder().encodeToString(b)).toList();
        return new PreviewPagesResponse(pages, false);
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

    /** «Запрос КП № 534 от 12.10.2026 – West-Med.docx»: компания — надпись бренда без кавычек, иначе краткое название. */
    static String fileName(Long id, LocalDate date, CompanyProfile p, Format format) {
        String company = !blank(p.getBrandText()) ? p.getBrandText() : p.getShortName();
        company = company == null ? "" : company.replaceAll("[\"«»]", "").replaceAll("\\s+", " ").trim();
        return "Запрос КП № " + (id == null ? DocFormat.DASH : id) + " от " + DocFormat.date(date)
                + (company.isEmpty() ? "" : " " + DocFormat.DASH + " " + company) + "." + format.extension;
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

    /** PDF и страницы картинками (не больше двух — понять, одна ли страница) — просмотр письма в боте (спека 2026-10-21 §7). */
    public record PreviewFiles(byte[] pdf, String pdfName, List<byte[]> pages) {}

    public PreviewFiles previewFiles(PriceRequest pr) {
        RfqFile f = render(pr, Format.PDF);
        return new PreviewFiles(f.bytes(), f.fileName(), previews.pages(f.bytes(), 2));
    }
}
