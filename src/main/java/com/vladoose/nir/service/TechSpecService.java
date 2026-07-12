package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.entity.TenderPlatform;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.exception.UnprocessableException;
import com.vladoose.nir.exception.UpstreamException;
import com.vladoose.nir.integration.goszakup.GoszakupClient;
import com.vladoose.nir.integration.goszakup.dto.LotTechSpecRef;
import com.vladoose.nir.integration.skpharmacy.SkTechSpecClient;
import com.vladoose.nir.integration.skpharmacy.SkTechSpecRef;
import com.vladoose.nir.util.PdfTextExtractor;
import com.vladoose.nir.util.SpecConstraintExtractor;
import com.vladoose.nir.util.TechSpecExtractor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * On-demand разбор «Технической спецификации» импортного лота → PDF → текст → русская секция →
 * габариты/вес → запись в лот (TechSpecWriter, транзакция только на запись; сеть вне tx, §6).
 * Ветка по площадке: goszakup (v3 Lots.Files) / СК-Фармация (documents-tab → modal → per-lot PDF).
 */
@Service
public class TechSpecService {

    public record ParseResult(TenderLot lot, boolean dimsFound, boolean weightFound,
                              boolean ambiguous, String source) {}

    private final TenderLotService tenderLotService;
    private final GoszakupClient client;
    private final SkTechSpecClient skClient;
    private final TechSpecWriter writer;

    public TechSpecService(TenderLotService tenderLotService, GoszakupClient client,
                           SkTechSpecClient skClient, TechSpecWriter writer) {
        this.tenderLotService = tenderLotService;
        this.client = client;
        this.skClient = skClient;
        this.writer = writer;
    }

    public ParseResult parse(Long lotId) {
        TenderLot lot = tenderLotService.findById(lotId);
        // findById = em.find обходит фильтр рынка → явный гард
        if (lot.getTender().getMarket() != null && lot.getTender().getMarket() != MarketContext.get()) {
            throw new NotFoundException("Лот не найден: id=" + lotId);
        }
        // Диспетч по площадке ПЕРВЫМ (до goszakup-проверки токена): у СК-Ф свой конвейер, токен goszakup не нужен.
        if (lot.getTender().getPlatform() == TenderPlatform.SK_PHARMACY) {
            return parseSk(lot);
        }
        return parseGoszakup(lot);
    }

    /** goszakup: v3 GraphQL Lots.Files (файл «Техническая спецификация», матч лота по equipName). */
    private ParseResult parseGoszakup(TenderLot lot) {
        String anno = lot.getTender().getSourceExtId();
        if (anno == null || anno.isBlank()) {
            throw new BadRequestException("ТЗ доступно только у импортированных с goszakup тендеров");
        }
        if (!client.isConfigured()) {
            throw new BadRequestException("Токен goszakup не настроен (GOSZAKUP_TOKEN)");
        }
        LotTechSpecRef ref;
        byte[] pdf;
        try {
            ref = client.fetchLotTechSpec(anno, lot.getEquipName());
            if (ref == null || ref.filePath() == null) {
                throw new NotFoundException("Файл «Техническая спецификация» не найден у лота на goszakup");
            }
            pdf = client.downloadFile(ref.filePath());
        } catch (IllegalStateException e) {
            throw new UpstreamException("goszakup недоступен: " + e.getMessage(), e);
        }
        if (pdf == null) { // 404 при скачивании (hash протух/файл удалён)
            throw new NotFoundException("Файл «Техническая спецификация» недоступен для скачивания на goszakup");
        }
        return finishParse(lot.getId(), pdf, ref.ambiguous(), ref.originalName());
    }

    /** СК-Фармация: documents-tab → docReqId «Техническая спецификация» → modal → PDF по коду лота. */
    private ParseResult parseSk(TenderLot lot) {
        String code = lot.getSourceLotCode();
        if (code == null || code.isBlank()) {
            throw new BadRequestException("Переимпортируйте тендер СК-Фармации («Обновить тендеры»), чтобы разобрать ТЗ лота");
        }
        String announceId = beforeDash(lot.getTender().getSourceExtId());
        if (announceId == null || announceId.isBlank()) {
            throw new BadRequestException("У тендера нет идентификатора объявления СК-Фармации");
        }
        List<SkTechSpecRef> refs = skClient.fetchTechSpecRefs(announceId); // сеть/не-200 → UpstreamException (502)
        List<SkTechSpecRef> forLot = refs.stream()
                .filter(r -> code.equalsIgnoreCase(r.lotCode()))
                .toList();
        if (forLot.isEmpty()) {
            throw new NotFoundException("Файл «Техническая спецификация» не найден для лота " + code + " на СК-Фармации");
        }
        SkTechSpecRef ref = forLot.get(0); // мультифайл на лот → первый + ambiguous
        byte[] pdf = skClient.downloadFile(ref.pdfUrl());
        if (pdf == null) {
            throw new NotFoundException("Файл ТЗ недоступен для скачивания на СК-Фармации");
        }
        return finishParse(lot.getId(), pdf, forLot.size() > 1, ref.fileName());
    }

    /** Общий хвост: PDF → русская секция → габариты/вес → запись в лот (TechSpecWriter). */
    private ParseResult finishParse(Long lotId, byte[] pdf, boolean ambiguous, String source) {
        String fullText = PdfTextExtractor.extract(pdf);
        String text = TechSpecExtractor.russianSection(fullText);
        if (text == null || text.isBlank()) {
            throw new UnprocessableException("Не удалось извлечь текст из PDF техспецификации");
        }
        SpecConstraintExtractor.SpecConstraints c = SpecConstraintExtractor.extract(text);
        TenderLot saved = writer.apply(lotId, text, c);
        return new ParseResult(saved, c.maxLengthMm() != null, c.maxWeightKg() != null, ambiguous, source);
    }

    /** «521464-1» → «521464» (числовой id объявления для модалки СК-Ф). */
    private static String beforeDash(String s) {
        if (s == null) return null;
        int i = s.indexOf('-');
        return i > 0 ? s.substring(0, i) : s;
    }
}
